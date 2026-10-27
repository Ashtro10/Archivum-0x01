package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.config.*
import ai.clu.core.livekit.*
import ai.clu.core.tools.HttpResponse
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Test

/** Unsigned test JWT with the given claims (the app only checks claims; the LiveKit server verifies signatures). */
fun testJwt(claims: JsonObject): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    return enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray()) + "." + enc.encodeToString(claims.toString().toByteArray()) + ".c2ln"
}

fun roomClaims(identity: String, room: String, expSec: Long, extraVideo: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
    put("sub", identity); put("exp", expSec); put("iss", "devkey")
    putJsonObject("video") { put("roomJoin", true); put("room", room); extraVideo() }
}

class LiveKitSettingsTest {
    private val platform = TestPlatform()
    private val core = newCore(platform = platform)
    private val s = core.settings
    private val nowSec = core.clock.now() / 1000

    private fun configure(url: String = "https://clu.example.net/token") {
        assertEquals(SettingResult.Ok, s.set(Settings.LK_TOKEN_URL, url, verified = true)); platform.secrets[CluCore.LIVEKIT_DEVICE_KEY] = "dev-key-123"
    }

    private fun serverReturns(serverUrl: String = "wss://lk.example.net", room: String = "clu-room", identity: String = "clu-phone", exp: Long = nowSec + 3600,
                              extra: JsonObjectBuilder.() -> Unit = {}) {
        platform.fakeHttp.routes["/token"] = HttpResponse(200, buildJsonObject {
            put("server_url", serverUrl); put("participant_token", testJwt(roomClaims(identity, room, exp, extra)))
        }.toString())
    }

    private fun token() = runBlocking { core.liveKitTokens.token("clu-room", "clu-phone", "Clu (phone)") }

    @Test fun mode() {
        assertEquals("offline", s.string(Settings.VOICE_MODE), "offline is the default")
        assertEquals(SettingScope.DEVICE, Settings[Settings.VOICE_MODE]!!.scope, "each device chooses its own path")
        assertEquals(SettingResult.Ok, s.set(Settings.VOICE_MODE, "livekit"))
        assertEquals("livekit", s.string(Settings.VOICE_MODE))
        assertIs<SettingResult.Invalid>(s.set(Settings.VOICE_MODE, "cloud"))
    }

    @Test fun tokenEndpoint() {
        assertTrue(Settings[Settings.LK_TOKEN_URL]!!.sensitive, "changing where voice goes requires owner verification")
        assertIs<SettingResult.Invalid>(s.set(Settings.LK_TOKEN_URL, "not a url", verified = true))
        assertEquals(SettingResult.RequiresVerification, s.set(Settings.LK_TOKEN_URL, "https://evil.example/token"), "unverified change refused")
        assertFalse(core.liveKitTokens.isConfigured())
        configure()
        assertTrue(core.liveKitTokens.isConfigured())
        serverReturns()
        val t = token()
        assertEquals("wss://lk.example.net", t.serverUrl); assertEquals("clu-room", t.room); assertEquals("clu-phone", t.identity)
        val req = platform.fakeHttp.requests.single { it.startsWith("POST https://clu.example.net/token") }
        val body = Json.parseToJsonElement(req.substringAfter("/token ")).jsonObject
        assertEquals("clu-room", body["room_name"]!!.jsonPrimitive.content)
        assertEquals("clu-phone", body["participant_identity"]!!.jsonPrimitive.content)
        assertEquals("Bearer dev-key-123", platform.fakeHttp.postHeaders.single()["Authorization"], "device key sent as bearer token")
        assertFalse(req.contains("dev-key-123"), "device key is not in the request body")
        // Cached until close to expiry.
        token(); assertEquals(1, platform.fakeHttp.requests.count { it.startsWith("POST") })
    }

    @Test fun tokensAreValidatedBeforeUse() {
        configure()
        fun rejects(why: String, setup: () -> Unit) {
            core.liveKitTokens.invalidate(); setup()
            val e = assertFailsWith<LiveKitException>(why) { token() }
            assertTrue(e.message!!.isNotBlank())
        }
        rejects("expired") { serverReturns(exp = nowSec - 10) }
        rejects("other room") { serverReturns(room = "someone-else") }
        rejects("other identity") { serverReturns(identity = "intruder") }
        rejects("admin grant") { serverReturns { put("roomAdmin", true) } }
        rejects("unencrypted internet server") { serverReturns(serverUrl = "ws://lk.example.net") }
        rejects("device key refused") { platform.fakeHttp.routes["/token"] = HttpResponse(401, "") }
        fun message(code: Int, body: String): String {
            core.liveKitTokens.invalidate(); platform.fakeHttp.routes["/token"] = HttpResponse(code, body)
            return assertFailsWith<LiveKitException> { token() }.message!!
        }
        assertTrue(message(400, """{"error": "unknown agent; this server runs 'clu'"}""").contains("unknown agent; this server runs 'clu'"), "the server's reason is shown")
        assertTrue(message(429, """{"error": "too many attempts"}""").contains("about a minute"))
        assertTrue(message(403, """{"error": "this device may only join room 'clu-phone'"}""").contains("may only join room"))
        assertTrue(message(401, "").contains("Re-enter the key"))
        rejects("garbage") { platform.fakeHttp.routes["/token"] = HttpResponse(200, "<html>") }
        platform.secrets.remove(CluCore.LIVEKIT_DEVICE_KEY)
        assertFailsWith<LiveKitException> { core.liveKitTokens.invalidate(); token() }
    }

    @Test fun lanCleartext() {
        assertNull(EndpointPolicy.check("https://lk.example.net/token", allowLanCleartext = false))
        assertNull(EndpointPolicy.check("ws://192.168.43.10:7880", allowLanCleartext = true))
        assertNull(EndpointPolicy.check("http://10.0.0.5:8089/token", allowLanCleartext = true))
        assertNull(EndpointPolicy.check("ws://clu-server.local:7880", allowLanCleartext = true))
        assertNull(EndpointPolicy.check("ws://[fd12::5]:7880", allowLanCleartext = true))
        assertNotNull(EndpointPolicy.check("ws://192.168.43.10:7880", allowLanCleartext = false))
        assertNotNull(EndpointPolicy.check("ws://lk.example.net:7880", allowLanCleartext = true), "public names never in cleartext")
        assertNotNull(EndpointPolicy.check("http://8.8.8.8/token", allowLanCleartext = true), "public IPs never in cleartext")
        assertNotNull(EndpointPolicy.check("ftp://10.0.0.5/x", allowLanCleartext = true))
        // Through the provisioner: LAN token server + LAN LiveKit server work when allowed, fail when not.
        configure("http://192.168.43.10:8089/token"); serverReturns(serverUrl = "ws://192.168.43.10:7880")
        assertEquals("ws://192.168.43.10:7880", token().serverUrl)
        assertEquals(SettingResult.RequiresVerification, s.set(Settings.LK_LAN_CLEARTEXT, false))
        s.set(Settings.LK_LAN_CLEARTEXT, false, verified = true); core.liveKitTokens.invalidate()
        assertFailsWith<LiveKitException> { token() }
    }

    @Test fun agentName() {
        assertEquals("clu", s.string(Settings.LK_AGENT_NAME))
        assertIs<SettingResult.Invalid>(s.set(Settings.LK_AGENT_NAME, "bad name!"))
        s.set(Settings.LK_AGENT_NAME, "my-clu-agent")
        configure(); serverReturns(); token()
        val req = platform.fakeHttp.requests.single { it.startsWith("POST") }
        val agents = Json.parseToJsonElement(req.substringAfter("/token ")).jsonObject["room_config"]!!.jsonObject["agents"]!!.jsonArray
        assertEquals("my-clu-agent", agents.single().jsonObject["agent_name"]!!.jsonPrimitive.content)
    }
}
