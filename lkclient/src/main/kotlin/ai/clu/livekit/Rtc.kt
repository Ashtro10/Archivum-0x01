package ai.clu.livekit

/**
 * The small slice of WebRTC the LiveKit client needs. Implemented with org.webrtc on Android (app module) and with
 * webrtc-java in JVM integration tests, so the signalling/negotiation logic runs unchanged in both.
 */
data class IceServer(val urls: List<String>, val username: String = "", val credential: String = "")

data class IceCandidate(val sdp: String, val sdpMid: String?, val sdpMLineIndex: Int)

enum class SdpType { OFFER, ANSWER }

data class SessionDescription(val type: SdpType, val sdp: String)

enum class PeerState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

interface RtcObserver {
    fun onIceCandidate(candidate: IceCandidate)
    fun onConnectionState(state: PeerState)
    fun onDataChannel(channel: RtcDataChannel)
    /** A remote audio track arrived. On Android it plays automatically; tests may attach a sink via [handle]. */
    fun onRemoteAudioTrack(trackId: String, streamIds: List<String>, handle: Any?)
}

interface RtcDataChannel {
    val label: String
    val isOpen: Boolean
    fun send(data: ByteArray): Boolean
    /** Called on a WebRTC thread with each binary message; [onOpen] when the channel becomes usable. */
    fun setListener(onMessage: (ByteArray) -> Unit, onOpen: () -> Unit)
    fun close()
}

/** A local microphone track. */
interface RtcAudioTrack {
    val id: String
    var enabled: Boolean
    fun dispose()
}

interface RtcPeer {
    suspend fun createOffer(): SessionDescription
    suspend fun createAnswer(): SessionDescription
    suspend fun setLocalDescription(sd: SessionDescription)
    suspend fun setRemoteDescription(sd: SessionDescription)
    val hasRemoteDescription: Boolean
    fun addIceCandidate(candidate: IceCandidate)
    fun createDataChannel(label: String, ordered: Boolean, maxRetransmits: Int?): RtcDataChannel
    /** Adds [track] as a send-only audio transceiver. */
    fun addSendOnlyAudio(track: RtcAudioTrack, streamId: String)
    fun close()
}

interface RtcFactory {
    fun createPeer(iceServers: List<IceServer>, observer: RtcObserver): RtcPeer
    fun createMicrophoneTrack(id: String): RtcAudioTrack
    fun dispose()
}
