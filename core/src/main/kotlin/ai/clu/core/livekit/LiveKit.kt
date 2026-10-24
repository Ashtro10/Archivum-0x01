package ai.clu.core.livekit

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.util.CluJson
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.*

/**
 * Optional LiveKit voice path. LiveKit only transports audio and messages between this device and the owner's own
 * LiveKit server, where a Clu voice agent does speech recognition and synthesis. Clu's brain (local model, memory,
 * tools, permissions) stays on the device: the agent sends transcripts here and speaks the replies it gets back.
 *
 * Nothing in this package is needed for offline conversations, and nothing here holds a LiveKit API key or secret:
 * room tokens come from the owner's token endpoint, authorised by a revocable per-device key.
 */
enum class ConversationPath { OFFLINE, LIVEKIT }

data class PathDecision(val path: ConversationPath, val reason: String)

object VoiceModePolicy {
    /** Which path a new voice session uses. [configured] = token endpoint + device key present. */
    fun select(s: SettingsStore, online: Boolean, configured: Boolean, serverReachable: Boolean? = null): PathDecision {
        if (s.string(Settings.VOICE_MODE) != "livekit") return PathDecision(ConversationPath.OFFLINE, "offline mode selected")
        val problem = when {
            !configured -> "LiveKit is not set up (token endpoint or device key missing)"
            !online -> "no network connection"
            serverReachable == false -> "LiveKit server not reachable"
            else -> null
        } ?: return PathDecision(ConversationPath.LIVEKIT, "LiveKit mode")
        return if (s.bool(Settings.LK_FALLBACK)) PathDecision(ConversationPath.OFFLINE, "$problem – continuing offline")
        else throw LiveKitException("$problem (fallback to offline is turned off)")
    }
}

class LiveKitException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** TLS everywhere, except unencrypted connections to private-network addresses when the owner allows it. */
object EndpointPolicy {
    fun check(url: String, allowLanCleartext: Boolean): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "not a valid URL"
        val host = uri.host ?: return "URL has no host"
        return when (uri.scheme?.lowercase()) {
            "https", "wss" -> null
            "http", "ws" -> when {
                !allowLanCleartext -> "unencrypted connections are turned off; use https:// / wss://"
                !isPrivateHost(host) -> "unencrypted connections are only allowed to private-network addresses; use https:// / wss://"
                else -> null
            }
            else -> "unsupported scheme '${uri.scheme}'"
        }
    }

    /** Literal private/loopback/link-local IPs and mDNS .local names. Names are not resolved (no DNS lookup). */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h == "localhost" || h.endsWith(".local")) return true
        val isLiteral = h.matches(Regex("[0-9.]+")) || (':' in h && h.matches(Regex("[0-9a-f:.]+")))
        if (!isLiteral) return false
        val a = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        val b = a.address
        // 100.64.0.0/10 (carrier-grade NAT, also used by some hotspots/VPNs) and IPv6 unique-local fc00::/7.
        return (b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64) ||
            (b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc)
    }
}

data class RoomToken(val serverUrl: String, val token: String, val identity: String, val room: String, val expiresAtMs: Long)

/**
 * Obtains room tokens from the owner's token endpoint (LiveKit "endpoint token source" format):
 * POST {room_name, participant_identity, participant_name, room_config:{agents:[{agent_name}]}} with
 * `Authorization: Bearer <device key>` -> {server_url, participant_token}. The token is checked before use: it must
 * be unexpired and grant exactly the requested identity and room.
 */
class TokenProvisioner(
    private val settings: SettingsStore,
    private val http: HttpFetcher,
    private val deviceKey: () -> String?,
    private val now: () -> Long,
) {
    @Volatile private var cached: RoomToken? = null

    fun isConfigured() = settings.string(Settings.LK_TOKEN_URL).isNotBlank() && !deviceKey().isNullOrBlank()

    fun invalidate() { cached = null }

    suspend fun token(room: String, identity: String, displayName: String): RoomToken {
        cached?.let { if (it.room == room && it.identity == identity && it.expiresAtMs - now() > 60_000) return it }
        val url = settings.string(Settings.LK_TOKEN_URL).trim()
        if (url.isEmpty()) throw LiveKitException("No token endpoint configured")
        val key = deviceKey()?.takeIf { it.isNotBlank() } ?: throw LiveKitException("No device key configured")
        val lan = settings.bool(Settings.LK_LAN_CLEARTEXT)
        EndpointPolicy.check(url, lan)?.let { throw LiveKitException("Token endpoint rejected: $it") }
        val body = buildJsonObject {
            put("room_name", room); put("participant_identity", identity); put("participant_name", displayName)
            putJsonObject("room_config") { putJsonArray("agents") { addJsonObject { put("agent_name", settings.string(Settings.LK_AGENT_NAME)) } } }
        }
        val resp = runCatching { http.postJson(url, body.toString(), mapOf("Authorization" to "Bearer $key")) }
            .getOrElse { throw LiveKitException("Token server not reachable: ${it.message}", it) }
        when (resp.code) {
            in 200..299 -> {}
            401, 403 -> throw LiveKitException("Token server rejected this device key (HTTP ${resp.code})")
            else -> throw LiveKitException("Token server error (HTTP ${resp.code})")
        }
        val json = runCatching { CluJson.parseToJsonElement(resp.body).jsonObject }.getOrElse { throw LiveKitException("Token server returned invalid JSON") }
        val serverUrl = json["server_url"]?.jsonPrimitive?.contentOrNull ?: throw LiveKitException("Token response has no server_url")
        val jwt = json["participant_token"]?.jsonPrimitive?.contentOrNull ?: throw LiveKitException("Token response has no participant_token")
        EndpointPolicy.check(serverUrl, lan)?.let { throw LiveKitException("LiveKit server URL rejected: $it") }
        val t = validate(jwt, serverUrl, room, identity)
        cached = t
        return t
    }

    /** Checks claims only; the signature is verified by the LiveKit server (the app never holds the secret). */
    fun validate(jwt: String, serverUrl: String, room: String, identity: String): RoomToken {
        val parts = jwt.split('.')
        if (parts.size != 3) throw LiveKitException("Malformed participant token")
        val claims = runCatching { CluJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1].padBase64()))).jsonObject }
            .getOrElse { throw LiveKitException("Malformed participant token") }
        val exp = claims["exp"]?.jsonPrimitive?.longOrNull ?: throw LiveKitException("Participant token has no expiry")
        if (exp * 1000 <= now()) throw LiveKitException("Participant token already expired")
        val sub = claims["sub"]?.jsonPrimitive?.contentOrNull
        if (sub != identity) throw LiveKitException("Participant token is for '$sub', expected '$identity'")
        val video = claims["video"]?.jsonObject ?: throw LiveKitException("Participant token has no room grant")
        val grantRoom = video["room"]?.jsonPrimitive?.contentOrNull
        if (video["roomJoin"]?.jsonPrimitive?.booleanOrNull != true || grantRoom != room)
            throw LiveKitException("Participant token does not grant joining room '$room'")
        if (video["roomAdmin"]?.jsonPrimitive?.booleanOrNull == true || video["roomCreate"]?.jsonPrimitive?.booleanOrNull == true)
            throw LiveKitException("Participant token is over-privileged (admin/create); refusing to use it")
        return RoomToken(serverUrl.trim(), jwt, identity, room, exp * 1000)
    }

    private fun String.padBase64() = this + "=".repeat((4 - length % 4) % 4)
}
