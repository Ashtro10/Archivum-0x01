package ai.clu.livekit

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSink
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [RtcFactory] on webrtc-java (native WebRTC for desktop JVMs) with a headless audio device, for integration tests:
 * the same LiveKit client code as on Android, with a microphone fed from PCM and remote audio delivered to sinks.
 */
class JvmRtcFactory : RtcFactory {
    private val adm = HeadlessAudioDeviceModule()
    private val factory = PeerConnectionFactory(adm)
    val mics = mutableListOf<JvmMicTrack>()

    override fun createPeer(iceServers: List<IceServer>, observer: RtcObserver): RtcPeer {
        val cfg = RTCConfiguration().apply {
            this.iceServers = iceServers.map { s -> RTCIceServer().apply { urls = s.urls; username = s.username; password = s.credential } }
            bundlePolicy = RTCBundlePolicy.MAX_BUNDLE
            rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE
        }
        lateinit var peer: JvmPeer
        val pc = factory.createPeerConnection(cfg, object : PeerConnectionObserver {
            override fun onIceCandidate(c: RTCIceCandidate) = observer.onIceCandidate(IceCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex))
            override fun onConnectionChange(state: RTCPeerConnectionState) = observer.onConnectionState(PeerState.valueOf(state.name))
            override fun onDataChannel(dc: RTCDataChannel) = observer.onDataChannel(JvmDataChannel(dc))
            override fun onTrack(t: RTCRtpTransceiver) {
                val track = t.receiver.track
                if (track is AudioTrack) observer.onRemoteAudioTrack(track.id, emptyList(), track)
            }
        })
        peer = JvmPeer(pc)
        return peer
    }

    override fun createMicrophoneTrack(id: String): RtcAudioTrack {
        val src = CustomAudioSource()
        return JvmMicTrack(id, src, factory.createAudioTrack(id, src)).also { mics += it }
    }

    override fun dispose() { runCatching { factory.dispose() }; runCatching { adm.dispose() } }
}

class JvmMicTrack(override val id: String, private val source: CustomAudioSource, val track: AudioTrack) : RtcAudioTrack {
    override var enabled: Boolean
        get() = track.isEnabled
        set(v) { track.isEnabled = v }

    /** Pushes 10 ms of 16-bit mono PCM at [rate] Hz. */
    fun push(pcm: ShortArray, rate: Int) {
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(pcm) }.array()
        source.pushAudio(bytes, 16, rate, 1, pcm.size)
    }

    override fun dispose() { runCatching { track.dispose() }; runCatching { source.dispose() } }
}

private class JvmDataChannel(private val dc: RTCDataChannel) : RtcDataChannel {
    override val label: String get() = dc.label
    override val isOpen: Boolean get() = dc.state == RTCDataChannelState.OPEN
    override fun send(data: ByteArray): Boolean = runCatching { dc.send(RTCDataChannelBuffer(ByteBuffer.wrap(data), true)) }.isSuccess
    override fun setListener(onMessage: (ByteArray) -> Unit, onOpen: () -> Unit) {
        dc.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() { if (dc.state == RTCDataChannelState.OPEN) onOpen() }
            override fun onMessage(buffer: RTCDataChannelBuffer) {
                val b = ByteArray(buffer.data.remaining()); buffer.data.get(b); onMessage(b)
            }
        })
        if (isOpen) onOpen()
    }
    override fun close() { runCatching { dc.unregisterObserver(); dc.close() } }
}

private class JvmPeer(private val pc: RTCPeerConnection) : RtcPeer {
    override val hasRemoteDescription: Boolean get() = pc.remoteDescription != null

    override suspend fun createOffer() = describe { o -> pc.createOffer(RTCOfferOptions(), o) }
    override suspend fun createAnswer() = describe { o -> pc.createAnswer(RTCAnswerOptions(), o) }

    private suspend fun describe(call: (CreateSessionDescriptionObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { k ->
        call(object : CreateSessionDescriptionObserver {
            override fun onSuccess(d: RTCSessionDescription) = k.resume(SessionDescription(if (d.sdpType == RTCSdpType.OFFER) SdpType.OFFER else SdpType.ANSWER, d.sdp))
            override fun onFailure(e: String) = k.resumeWithException(IllegalStateException(e))
        })
    }

    override suspend fun setLocalDescription(sd: SessionDescription) = apply { o -> pc.setLocalDescription(sd.native(), o) }
    override suspend fun setRemoteDescription(sd: SessionDescription) = apply { o -> pc.setRemoteDescription(sd.native(), o) }

    private suspend fun apply(call: (SetSessionDescriptionObserver) -> Unit): Unit = suspendCancellableCoroutine { k ->
        call(object : SetSessionDescriptionObserver {
            override fun onSuccess() = k.resume(Unit)
            override fun onFailure(e: String) = k.resumeWithException(IllegalStateException(e))
        })
    }

    private fun SessionDescription.native() = RTCSessionDescription(if (type == SdpType.OFFER) RTCSdpType.OFFER else RTCSdpType.ANSWER, sdp)

    override fun addIceCandidate(candidate: IceCandidate) = pc.addIceCandidate(RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))

    override fun createDataChannel(label: String, ordered: Boolean, maxRetransmits: Int?): RtcDataChannel =
        JvmDataChannel(pc.createDataChannel(label, RTCDataChannelInit().apply { this.ordered = ordered; maxRetransmits?.let { this.maxRetransmits = it } }))

    override fun addSendOnlyAudio(track: RtcAudioTrack, streamId: String) {
        pc.addTransceiver((track as JvmMicTrack).track, RTCRtpTransceiverInit().apply { direction = RTCRtpTransceiverDirection.SEND_ONLY; streamIds = listOf(streamId) })
    }

    override fun close() = pc.close()
}

/** Collects remote audio levels for latency/interruption measurements. */
class AudioMeter : AudioTrackSink {
    val loudAt = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    val frames = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, Boolean>>()
    override fun onData(data: ByteArray, bitsPerSample: Int, sampleRate: Int, channels: Int, frames: Int) {
        val bb = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var peak = 0
        while (bb.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(bb.get().toInt()))
        val now = System.nanoTime() / 1_000_000
        val loud = peak > 300
        this.frames += now to loud
        if (loud) loudAt += now
    }
}
