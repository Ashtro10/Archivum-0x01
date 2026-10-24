package ai.clu.livekit

import kotlinx.coroutines.*
import livekit.LivekitModels
import livekit.LivekitRtc
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class RemoteParticipant(val sid: String, val identity: String, val name: String, val isAgent: Boolean)

sealed class RoomEvent {
    data class Connected(val room: String, val identity: String, val serverVersion: String) : RoomEvent()
    data class ParticipantJoined(val participant: RemoteParticipant) : RoomEvent()
    data class ParticipantLeft(val participant: RemoteParticipant) : RoomEvent()
    /** A user data packet (topic + payload) from [from]. */
    data class Data(val from: String?, val topic: String?, val payload: ByteArray) : RoomEvent()
    data class RemoteAudio(val trackId: String, val handle: Any?) : RoomEvent()
    data class MediaState(val publisher: PeerState, val subscriber: PeerState) : RoomEvent()
    data class Disconnected(val reason: String) : RoomEvent()
}

/**
 * Minimal LiveKit participant: joins one room over the LiveKit signalling protocol (WebSocket + protobuf),
 * negotiates the publisher and subscriber peer connections, publishes the microphone, plays subscribed audio and
 * exchanges reliable data packets. Deliberately small: no video, no simulcast, no resume after a dropped
 * connection (the session ends and the app may start a new one).
 *
 * All state changes run on one internal thread, so WebRTC and WebSocket callbacks never race each other.
 */
class LiveKitRoom(
    private val rtc: RtcFactory,
    private val listener: (RoomEvent) -> Unit,
    private val http: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build(),
) {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "livekit-room").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + thread.asCoroutineDispatcher() + CoroutineExceptionHandler { _, e -> fail("internal error: ${e.message}") })

    private var ws: WebSocket? = null
    private var publisher: RtcPeer? = null
    private var subscriber: RtcPeer? = null
    private var reliable: RtcDataChannel? = null
    private var mic: RtcAudioTrack? = null
    private var micCid: String? = null
    private var pubState = PeerState.NEW
    private var subState = PeerState.NEW
    private val pendingCandidates = mapOf(LivekitRtc.SignalTarget.PUBLISHER to mutableListOf<IceCandidate>(), LivekitRtc.SignalTarget.SUBSCRIBER to mutableListOf())
    private val pendingData = ArrayDeque<ByteArray>()
    private val participants = java.util.concurrent.ConcurrentHashMap<String, RemoteParticipant>()
    private val sequence = AtomicInteger(0)
    private var pingJob: Job? = null
    private var lastPong = 0L
    private var negotiating = false
    private var renegotiate = false
    @Volatile var localIdentity: String? = null; private set
    @Volatile var localSid: String? = null; private set
    @Volatile var closed = false; private set
    private val joined = CompletableDeferred<RoomEvent.Connected>()

    val remoteParticipants: List<RemoteParticipant> get() = participants.values.toList()

    /** Connects and waits for the join response (not for media). Throws on failure or timeout. */
    suspend fun connect(url: String, token: String, timeoutMs: Long = 15_000): RoomEvent.Connected {
        val base = url.trimEnd('/').let { if (it.endsWith("/rtc")) it else "$it/rtc" }
        val q = "access_token=${URLEncoder.encode(token, "UTF-8")}&auto_subscribe=1&adaptive_stream=0&protocol=$PROTOCOL&sdk=android&version=$SDK_VERSION"
        val req = Request.Builder().url(base.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://") + "?" + q).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val msg = runCatching { LivekitRtc.SignalResponse.parseFrom(bytes.toByteArray()) }.getOrNull() ?: return
                scope.launch { onSignal(msg) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = response?.let { r -> "HTTP ${r.code}" + (r.body?.string()?.take(200)?.let { ": $it" } ?: "") } ?: (t.message ?: t::class.simpleName)
                scope.launch { fail("signalling connection failed: $why") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch { fail("signalling closed ($code ${reason.ifBlank { "" }})".trim()) } }
        })
        return withTimeoutOrNull(timeoutMs) { joined.await() } ?: run { disconnect().join(); throw IllegalStateException("timed out joining the room") }
    }

    /** Publishes the microphone (send-only Opus). Audio flows once the publisher connection is up. */
    fun publishMicrophone() = scope.launch {
        if (mic != null || closed) return@launch
        val cid = "TR_mic_" + UUID.randomUUID().toString().take(8)
        micCid = cid
        mic = rtc.createMicrophoneTrack(cid)
        send(LivekitRtc.SignalRequest.newBuilder().setAddTrack(LivekitRtc.AddTrackRequest.newBuilder()
            .setCid(cid).setName("microphone").setType(LivekitModels.TrackType.AUDIO).setSource(LivekitModels.TrackSource.MICROPHONE)))
        // The transceiver is added once the server confirms the track (track_published), then the publisher negotiates.
    }

    fun setMicrophoneEnabled(enabled: Boolean) = scope.launch { mic?.enabled = enabled }

    /** Sends a reliable data packet. Queued until the data channel is open. */
    fun sendData(topic: String, payload: ByteArray, destinations: List<String> = emptyList()) = scope.launch {
        if (closed) return@launch
        val user = LivekitModels.UserPacket.newBuilder().setPayload(com.google.protobuf.ByteString.copyFrom(payload)).setTopic(topic)
        val pkt = LivekitModels.DataPacket.newBuilder().setKind(LivekitModels.DataPacket.Kind.RELIABLE).setUser(user)
            .addAllDestinationIdentities(destinations).setSequence(sequence.incrementAndGet()).setParticipantIdentity(localIdentity ?: "")
            .setParticipantSid(localSid ?: "").build().toByteArray()
        val dc = reliable
        if (dc != null && dc.isOpen) dc.send(pkt) else pendingData.addLast(pkt)
    }

    /** Leaves the room. Safe to call from any thread, including room event callbacks. */
    fun disconnect(): Job = scope.launch {
        if (closed) return@launch
        runCatching { send(LivekitRtc.SignalRequest.newBuilder().setLeave(LivekitRtc.LeaveRequest.newBuilder()
            .setCanReconnect(false).setReason(LivekitModels.DisconnectReason.CLIENT_INITIATED))) }
        teardown("left the room")
    }

    // ------------------------------------------------------------------------------------------------ signalling
    private fun send(b: LivekitRtc.SignalRequest.Builder) {
        ws?.send(b.build().toByteArray().toByteString())
    }

    private suspend fun onSignal(m: LivekitRtc.SignalResponse) {
        if (closed) return
        when (m.messageCase) {
            LivekitRtc.SignalResponse.MessageCase.JOIN -> onJoin(m.join)
            LivekitRtc.SignalResponse.MessageCase.OFFER -> onSubscriberOffer(m.offer)
            LivekitRtc.SignalResponse.MessageCase.ANSWER -> onPublisherAnswer(m.answer)
            LivekitRtc.SignalResponse.MessageCase.TRICKLE -> onTrickle(m.trickle)
            LivekitRtc.SignalResponse.MessageCase.UPDATE -> m.update.participantsList.forEach(::onParticipant)
            LivekitRtc.SignalResponse.MessageCase.TRACK_PUBLISHED -> if (m.trackPublished.cid == micCid) addMicTransceiver()
            LivekitRtc.SignalResponse.MessageCase.LEAVE -> teardown("server ended the session (${m.leave.reason})")
            LivekitRtc.SignalResponse.MessageCase.PONG_RESP, LivekitRtc.SignalResponse.MessageCase.PONG -> lastPong = System.currentTimeMillis()
            else -> {}
        }
    }

    private fun onJoin(j: LivekitRtc.JoinResponse) {
        localIdentity = j.participant.identity; localSid = j.participant.sid
        val ice = j.iceServersList.map { IceServer(it.urlsList, it.username, it.credential) }
        publisher = rtc.createPeer(ice, peerObserver(LivekitRtc.SignalTarget.PUBLISHER))
        subscriber = rtc.createPeer(ice, peerObserver(LivekitRtc.SignalTarget.SUBSCRIBER))
        reliable = publisher!!.createDataChannel(RELIABLE_LABEL, ordered = true, maxRetransmits = null).also { dc ->
            dc.setListener(onMessage = {}, onOpen = { scope.launch { flushPendingData() } })
        }
        publisher!!.createDataChannel(LOSSY_LABEL, ordered = false, maxRetransmits = 0)
        j.otherParticipantsList.forEach(::onParticipant)
        startPing(j.pingInterval, j.pingTimeout)
        if (!j.subscriberPrimary) negotiatePublisher()
        joined.complete(RoomEvent.Connected(j.room.name, j.participant.identity, j.serverVersion).also(listener))
    }

    private fun startPing(intervalS: Int, timeoutS: Int) {
        if (intervalS <= 0) return
        lastPong = System.currentTimeMillis()
        pingJob = scope.launch {
            while (isActive) {
                delay(intervalS * 1000L)
                send(LivekitRtc.SignalRequest.newBuilder().setPingReq(LivekitRtc.Ping.newBuilder().setTimestamp(System.currentTimeMillis())))
                if (timeoutS > 0 && System.currentTimeMillis() - lastPong > timeoutS * 1000L + intervalS * 1000L) { fail("server stopped responding"); return@launch }
            }
        }
    }

    private fun onParticipant(p: LivekitModels.ParticipantInfo) {
        if (p.sid == localSid) return
        val rp = RemoteParticipant(p.sid, p.identity, p.name, p.kind == LivekitModels.ParticipantInfo.Kind.AGENT)
        if (p.state == LivekitModels.ParticipantInfo.State.DISCONNECTED) {
            participants.remove(p.sid)?.let { listener(RoomEvent.ParticipantLeft(it)) }
        } else if (participants.put(p.sid, rp) == null) {
            listener(RoomEvent.ParticipantJoined(rp))
        }
    }

    private suspend fun onSubscriberOffer(sd: LivekitRtc.SessionDescription) {
        val sub = subscriber ?: return
        sub.setRemoteDescription(SessionDescription(SdpType.OFFER, sd.sdp))
        drainCandidates(LivekitRtc.SignalTarget.SUBSCRIBER, sub)
        val answer = sub.createAnswer()
        sub.setLocalDescription(answer)
        send(LivekitRtc.SignalRequest.newBuilder().setAnswer(LivekitRtc.SessionDescription.newBuilder().setType("answer").setSdp(answer.sdp).setId(sd.id)))
    }

    private suspend fun onPublisherAnswer(sd: LivekitRtc.SessionDescription) {
        val pub = publisher ?: return
        pub.setRemoteDescription(SessionDescription(SdpType.ANSWER, sd.sdp))
        drainCandidates(LivekitRtc.SignalTarget.PUBLISHER, pub)
        negotiating = false
        if (renegotiate) { renegotiate = false; negotiatePublisher() }
    }

    private fun onTrickle(t: LivekitRtc.TrickleRequest) {
        val c = parseCandidate(t.candidateInit) ?: return
        val pc = if (t.target == LivekitRtc.SignalTarget.PUBLISHER) publisher else subscriber
        if (pc == null || !pc.hasRemoteDescription) pendingCandidates.getValue(t.target) += c else pc.addIceCandidate(c)
    }

    private fun drainCandidates(target: LivekitRtc.SignalTarget, pc: RtcPeer) {
        val q = pendingCandidates.getValue(target)
        q.forEach(pc::addIceCandidate); q.clear()
    }

    private fun addMicTransceiver() {
        val pub = publisher ?: return
        val track = mic ?: return
        pub.addSendOnlyAudio(track, localSid ?: "local")
        negotiatePublisher()
    }

    private fun negotiatePublisher() {
        if (negotiating) { renegotiate = true; return }
        val pub = publisher ?: return
        negotiating = true
        scope.launch {
            val offer = pub.createOffer()
            pub.setLocalDescription(offer)
            send(LivekitRtc.SignalRequest.newBuilder().setOffer(LivekitRtc.SessionDescription.newBuilder().setType("offer").setSdp(offer.sdp)))
        }
    }

    private fun flushPendingData() {
        val dc = reliable ?: return
        while (dc.isOpen && pendingData.isNotEmpty()) dc.send(pendingData.removeFirst())
    }

    // ------------------------------------------------------------------------------------------------ WebRTC callbacks
    private fun peerObserver(target: LivekitRtc.SignalTarget) = object : RtcObserver {
        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch {
                send(LivekitRtc.SignalRequest.newBuilder().setTrickle(LivekitRtc.TrickleRequest.newBuilder()
                    .setCandidateInit(candidateJson(candidate)).setTarget(target)))
            }
        }
        override fun onConnectionState(state: PeerState) {
            scope.launch {
                if (target == LivekitRtc.SignalTarget.PUBLISHER) pubState = state else subState = state
                listener(RoomEvent.MediaState(pubState, subState))
                if (state == PeerState.FAILED) fail("media connection failed (${target.name.lowercase()})")
            }
        }
        override fun onDataChannel(channel: RtcDataChannel) {
            // Server -> client data arrives on the data channels the server opens on the subscriber connection.
            channel.setListener(onMessage = { bytes -> scope.launch { onDataPacket(bytes) } }, onOpen = {})
        }
        override fun onRemoteAudioTrack(trackId: String, streamIds: List<String>, handle: Any?) {
            scope.launch { listener(RoomEvent.RemoteAudio(trackId, handle)) }
        }
    }

    private fun onDataPacket(bytes: ByteArray) {
        val p = runCatching { LivekitModels.DataPacket.parseFrom(bytes) }.getOrNull() ?: return
        if (!p.hasUser()) return
        @Suppress("DEPRECATION")
        val from = p.participantIdentity.ifEmpty { p.user.participantIdentity }.ifEmpty { null }
        listener(RoomEvent.Data(from, if (p.user.hasTopic()) p.user.topic else null, p.user.payload.toByteArray()))
    }

    private fun fail(reason: String) {
        if (!closed) teardown(reason)
    }

    private fun teardown(reason: String) {
        if (closed) return
        closed = true
        pingJob?.cancel()
        runCatching { ws?.close(1000, "bye") }; ws = null
        runCatching { reliable?.close() }
        runCatching { publisher?.close() }; runCatching { subscriber?.close() }
        runCatching { mic?.dispose() }
        publisher = null; subscriber = null; reliable = null; mic = null
        if (!joined.isCompleted) joined.completeExceptionally(IllegalStateException(reason))
        listener(RoomEvent.Disconnected(reason))
        scope.launch { delay(100); thread.shutdown() }
    }

    companion object {
        const val PROTOCOL = 15
        const val SDK_VERSION = "2.0.0"
        const val RELIABLE_LABEL = "_reliable"
        const val LOSSY_LABEL = "_lossy"

        fun candidateJson(c: IceCandidate): String {
            fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            return "{\"candidate\":${q(c.sdp)},\"sdpMid\":${c.sdpMid?.let(::q) ?: "null"},\"sdpMLineIndex\":${c.sdpMLineIndex}}"
        }

        /** Parses the JSON candidate init the server sends (minimal parser: three known keys). */
        fun parseCandidate(json: String): IceCandidate? {
            fun str(key: String): String? = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            val cand = str("candidate") ?: return null
            val idx = Regex("\"sdpMLineIndex\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return IceCandidate(cand, str("sdpMid"), idx)
        }
    }
}
