package ai.clu.voice

import ai.clu.livekit.*
import ai.clu.livekit.IceCandidate as LkIceCandidate
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [RtcFactory] on the WebRTC Android SDK (org.webrtc). Microphone capture uses the voice-communication source with
 * hardware echo cancellation and noise suppression, so the agent's voice from the speaker is not sent back as speech;
 * remote audio (Clu's voice from the agent) plays through WebRTC's own audio device.
 */
class AndroidRtcFactory(context: Context) : RtcFactory {
    private val adm: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory

    init {
        initialize(context.applicationContext)
        adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
    }

    override fun createPeer(iceServers: List<IceServer>, observer: RtcObserver): RtcPeer {
        val cfg = PeerConnection.RTCConfiguration(iceServers.map { s ->
            PeerConnection.IceServer.builder(s.urls).setUsername(s.username).setPassword(s.credential).createIceServer()
        }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(c: org.webrtc.IceCandidate) = observer.onIceCandidate(LkIceCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex))
            override fun onIceCandidatesRemoved(c: Array<out org.webrtc.IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) = observer.onDataChannel(AndroidDataChannel(dc))
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) = observer.onConnectionState(PeerState.valueOf(s.name))
            override fun onTrack(t: RtpTransceiver) {
                val track = t.receiver.track() ?: return
                if (track is AudioTrack) observer.onRemoteAudioTrack(track.id(), emptyList(), track)
            }
        }) ?: throw IllegalStateException("WebRTC could not create a peer connection")
        return AndroidPeer(pc)
    }

    override fun createMicrophoneTrack(id: String): RtcAudioTrack {
        val source = factory.createAudioSource(MediaConstraints())
        return AndroidMicTrack(id, source, factory.createAudioTrack(id, source))
    }

    override fun dispose() {
        runCatching { factory.dispose() }
        runCatching { adm.release() }
    }

    companion object {
        @Volatile private var initialized = false
        @Synchronized fun initialize(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            initialized = true
        }
    }
}

private class AndroidMicTrack(override val id: String, private val source: AudioSource, private val track: AudioTrack) : RtcAudioTrack {
    override var enabled: Boolean
        get() = track.enabled()
        set(v) { track.setEnabled(v) }
    override fun dispose() { runCatching { track.dispose() }; runCatching { source.dispose() } }
    val native: AudioTrack get() = track
}

private class AndroidDataChannel(private val dc: DataChannel) : RtcDataChannel {
    override val label: String get() = dc.label()
    override val isOpen: Boolean get() = dc.state() == DataChannel.State.OPEN
    override fun send(data: ByteArray) = dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), true))
    override fun setListener(onMessage: (ByteArray) -> Unit, onOpen: () -> Unit) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() { if (dc.state() == DataChannel.State.OPEN) onOpen() }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val b = ByteArray(buffer.data.remaining()); buffer.data.get(b); onMessage(b)
            }
        })
        if (isOpen) onOpen()
    }
    override fun close() { runCatching { dc.unregisterObserver(); dc.close(); dc.dispose() } }
}

private class AndroidPeer(private val pc: PeerConnection) : RtcPeer {
    override val hasRemoteDescription: Boolean get() = pc.remoteDescription != null

    override suspend fun createOffer() = create { o -> pc.createOffer(o, MediaConstraints()) }
    override suspend fun createAnswer() = create { o -> pc.createAnswer(o, MediaConstraints()) }

    private suspend fun create(call: (SdpObserver) -> Unit): ai.clu.livekit.SessionDescription = suspendCancellableCoroutine { k ->
        call(object : SdpObserver {
            override fun onCreateSuccess(d: org.webrtc.SessionDescription) =
                k.resume(ai.clu.livekit.SessionDescription(if (d.type == org.webrtc.SessionDescription.Type.OFFER) SdpType.OFFER else SdpType.ANSWER, d.description))
            override fun onCreateFailure(e: String?) = k.resumeWithException(IllegalStateException(e ?: "createDescription failed"))
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        })
    }

    override suspend fun setLocalDescription(sd: ai.clu.livekit.SessionDescription) = set { o -> pc.setLocalDescription(o, sd.native()) }
    override suspend fun setRemoteDescription(sd: ai.clu.livekit.SessionDescription) = set { o -> pc.setRemoteDescription(o, sd.native()) }

    private suspend fun set(call: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { k ->
        call(object : SdpObserver {
            override fun onCreateSuccess(d: org.webrtc.SessionDescription) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() = k.resume(Unit)
            override fun onSetFailure(e: String?) = k.resumeWithException(IllegalStateException(e ?: "setDescription failed"))
        })
    }

    private fun ai.clu.livekit.SessionDescription.native() =
        org.webrtc.SessionDescription(if (type == SdpType.OFFER) org.webrtc.SessionDescription.Type.OFFER else org.webrtc.SessionDescription.Type.ANSWER, sdp)

    override fun addIceCandidate(candidate: LkIceCandidate) { pc.addIceCandidate(org.webrtc.IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)) }

    override fun createDataChannel(label: String, ordered: Boolean, maxRetransmits: Int?): RtcDataChannel =
        AndroidDataChannel(pc.createDataChannel(label, DataChannel.Init().apply { this.ordered = ordered; maxRetransmits?.let { this.maxRetransmits = it } }))

    override fun addSendOnlyAudio(track: RtcAudioTrack, streamId: String) {
        pc.addTransceiver((track as AndroidMicTrack).native,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf(streamId)))
    }

    override fun close() { runCatching { pc.close() }; runCatching { pc.dispose() } }
}
