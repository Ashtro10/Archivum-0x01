package ai.clu.desktop.voice

import kotlinx.coroutines.*
import livekit.proto.AudioFrame
import livekit.proto.DataStream
import livekit.proto.Ffi
import livekit.proto.Participant as LkParticipant
import livekit.proto.Room
import livekit.proto.Rpc
import livekit.proto.Track
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class LiveKitError(message: String) : Exception(message)

/**
 * The one connection to LiveKit's Rust SDK in this process. Requests go out synchronously; their results arrive as
 * events matched by the async id we chose. Room and audio-stream events are handed to whoever registered for that handle.
 */
object LiveKitFfi {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Ffi.FfiEvent>>()
    private val roomListeners = ConcurrentHashMap<Long, (Room.RoomEvent) -> Unit>()
    private val streamListeners = ConcurrentHashMap<Long, (AudioFrame.AudioStreamEvent) -> Unit>()
    private val rpcListeners = ConcurrentHashMap<Long, (Rpc.RpcMethodInvocationEvent) -> Unit>()
    @Volatile private var started = false

    @Synchronized fun start(): Boolean {
        if (started) return true
        if (!NativeVoice.load()) return false
        NativeVoice.eventSink = ::dispatch
        NativeVoice.lkInit("clu-0.7")
        started = true
        return true
    }

    fun asyncId(): Long = nextId.getAndIncrement()

    fun request(req: Ffi.FfiRequest): Ffi.FfiResponse {
        check(started) { "LiveKit runtime not started" }
        return Ffi.FfiResponse.parseFrom(NativeVoice.lkRequest(req.toByteArray()))
    }

    /** Sends a request whose result comes back as a callback event with [id]; waits for it (bounded). */
    suspend fun call(id: Long, timeoutMs: Long = 20_000, req: Ffi.FfiRequest): Ffi.FfiEvent {
        val d = CompletableDeferred<Ffi.FfiEvent>(); pending[id] = d
        try {
            request(req)
            return withTimeout(timeoutMs) { d.await() }
        } catch (e: TimeoutCancellationException) { throw LiveKitError("LiveKit did not answer in ${timeoutMs / 1000} s")
        } finally { pending.remove(id) }
    }

    fun drop(handle: Long) { if (handle != 0L) runCatching { NativeVoice.lkDropHandle(handle) } }

    fun onRoom(handle: Long, l: (Room.RoomEvent) -> Unit) { roomListeners[handle] = l }
    fun offRoom(handle: Long) { roomListeners.remove(handle) }
    fun onStream(handle: Long, l: (AudioFrame.AudioStreamEvent) -> Unit) { streamListeners[handle] = l }
    fun offStream(handle: Long) { streamListeners.remove(handle) }
    fun onRpc(participantHandle: Long, l: (Rpc.RpcMethodInvocationEvent) -> Unit) { rpcListeners[participantHandle] = l }
    fun offRpc(participantHandle: Long) { rpcListeners.remove(participantHandle) }

    private fun dispatch(bytes: ByteArray) {
        val ev = runCatching { Ffi.FfiEvent.parseFrom(bytes) }.getOrNull() ?: return
        when (ev.messageCase) {
            Ffi.FfiEvent.MessageCase.ROOM_EVENT -> roomListeners[ev.roomEvent.roomHandle]?.invoke(ev.roomEvent)
            Ffi.FfiEvent.MessageCase.AUDIO_STREAM_EVENT -> {
                val l = streamListeners[ev.audioStreamEvent.streamHandle]
                if (l != null) l(ev.audioStreamEvent) else if (ev.audioStreamEvent.hasFrameReceived()) drop(ev.audioStreamEvent.frameReceived.frame.handle.id)
            }
            Ffi.FfiEvent.MessageCase.RPC_METHOD_INVOCATION -> rpcListeners[ev.rpcMethodInvocation.localParticipantHandle]?.invoke(ev.rpcMethodInvocation)
            else -> asyncIdOf(ev)?.let { pending[it]?.complete(ev) }
        }
    }

    private fun asyncIdOf(ev: Ffi.FfiEvent): Long? = when (ev.messageCase) {
        Ffi.FfiEvent.MessageCase.CONNECT -> ev.connect.asyncId
        Ffi.FfiEvent.MessageCase.DISCONNECT -> ev.disconnect.asyncId
        Ffi.FfiEvent.MessageCase.PUBLISH_TRACK -> ev.publishTrack.asyncId
        Ffi.FfiEvent.MessageCase.CAPTURE_AUDIO_FRAME -> ev.captureAudioFrame.asyncId
        Ffi.FfiEvent.MessageCase.PUBLISH_DATA -> ev.publishData.asyncId
        Ffi.FfiEvent.MessageCase.SET_LOCAL_ATTRIBUTES -> ev.setLocalAttributes.asyncId
        Ffi.FfiEvent.MessageCase.SEND_TEXT -> ev.sendText.asyncId
        Ffi.FfiEvent.MessageCase.TEXT_STREAM_READER_READ_ALL -> ev.textStreamReaderReadAll.asyncId
        Ffi.FfiEvent.MessageCase.TEXT_STREAM_OPEN -> ev.textStreamOpen.asyncId
        Ffi.FfiEvent.MessageCase.TEXT_STREAM_WRITER_WRITE -> ev.textStreamWriterWrite.asyncId
        Ffi.FfiEvent.MessageCase.TEXT_STREAM_WRITER_CLOSE -> ev.textStreamWriterClose.asyncId
        else -> null
    }
}

/** What the room tells CLU's participant. */
sealed class LkEvent {
    data class AudioTrack(val participant: String, val trackSid: String, val trackHandle: Long) : LkEvent()
    data class TrackGone(val participant: String, val trackSid: String) : LkEvent()
    data class Joined(val participant: String, val attributes: Map<String, String>) : LkEvent()
    data class Left(val participant: String) : LkEvent()
    data class Text(val participant: String, val topic: String, val text: String, val attributes: Map<String, String>) : LkEvent()
    data class Data(val participant: String, val topic: String, val bytes: ByteArray) : LkEvent()
    data class Rpc(val caller: String, val method: String, val payload: String, val respond: (String?, String?) -> Unit) : LkEvent()
    data class ConnectionState(val state: String) : LkEvent()
    data class Disconnected(val reason: String) : LkEvent()
}

/**
 * CLU's own participant in one room: joins with a token, publishes one audio track (CLU's voice), receives the other
 * participants' microphones as 16 kHz mono PCM, and exchanges text streams, data and RPCs in LiveKit's standard agent
 * protocol (lk.agent.state, lk.transcription, lk.chat), so any LiveKit client that speaks to agents can talk to CLU.
 */
class LkRoom private constructor(val roomHandle: Long, val localHandle: Long, val identity: String, private val scope: CoroutineScope, private val onEvent: (LkEvent) -> Unit) {
    private var sourceHandle = 0L; private var trackHandle = 0L
    @Volatile var trackSid = ""; private set
    private var sourceRate = 24_000
    private val streams = ConcurrentHashMap<String, Long>()
    @Volatile var closed = false; private set
    private val events = Channel(scope)

    companion object {
        suspend fun connect(url: String, token: String, scope: CoroutineScope, onEvent: (LkEvent) -> Unit): Pair<LkRoom, List<LkEvent>> {
            if (!LiveKitFfi.start()) throw LiveKitError(NativeVoice.loadError ?: "LiveKit is not available")
            val id = LiveKitFfi.asyncId()
            val opts = Room.RoomOptions.newBuilder().setAutoSubscribe(true).setAdaptiveStream(false).setDynacast(false).setJoinRetries(2).setConnectTimeoutMs(10_000).build()
            val ev = LiveKitFfi.call(id, 30_000, Ffi.FfiRequest.newBuilder().setConnect(Room.ConnectRequest.newBuilder().setUrl(url).setToken(token).setOptions(opts).setRequestAsyncId(id)).build())
            val cb = ev.connect
            if (cb.hasError()) throw LiveKitError("Could not join the voice room: ${cb.error}")
            val r = cb.result
            val room = LkRoom(r.room.handle.id, r.localParticipant.handle.id, r.localParticipant.info.identity, scope, onEvent)
            LiveKitFfi.onRoom(room.roomHandle, room::handle)
            LiveKitFfi.onRpc(room.localHandle, room::handleRpc)
            // People already in the room, and their microphones (auto-subscribed: they arrive as TrackSubscribed events too).
            val initial = r.participantsList.map { p -> LkEvent.Joined(p.participant.info.identity, p.participant.info.attributesMap.toMap()) }
            return room to initial
        }
    }

    // ------------------------------------------------------------------------------------------------ incoming
    private fun handle(e: Room.RoomEvent) {
        if (closed) return
        when (e.messageCase) {
            Room.RoomEvent.MessageCase.PARTICIPANT_CONNECTED -> e.participantConnected.info.info.let { onEvent(LkEvent.Joined(it.identity, it.attributesMap.toMap())) }
            Room.RoomEvent.MessageCase.PARTICIPANT_DISCONNECTED -> onEvent(LkEvent.Left(e.participantDisconnected.participantIdentity))
            Room.RoomEvent.MessageCase.TRACK_SUBSCRIBED -> {
                val t = e.trackSubscribed.track
                if (t.info.kind == Track.TrackKind.KIND_AUDIO) onEvent(LkEvent.AudioTrack(e.trackSubscribed.participantIdentity, t.info.sid, t.handle.id)) else LiveKitFfi.drop(t.handle.id)
            }
            Room.RoomEvent.MessageCase.TRACK_UNSUBSCRIBED -> onEvent(LkEvent.TrackGone(e.trackUnsubscribed.participantIdentity, e.trackUnsubscribed.trackSid))
            Room.RoomEvent.MessageCase.DATA_PACKET_RECEIVED -> {
                val d = e.dataPacketReceived
                if (d.hasUser()) {
                    val buf = d.user.data
                    val bytes = NativeVoice.bytesRead(buf.data.dataPtr, buf.data.dataLen.toInt()); LiveKitFfi.drop(buf.handle.id)
                    onEvent(LkEvent.Data(d.participantIdentity, d.user.topic, bytes))
                }
            }
            Room.RoomEvent.MessageCase.TEXT_STREAM_OPENED -> {
                val o = e.textStreamOpened; val reader = o.reader
                scope.launch {
                    val id = LiveKitFfi.asyncId()
                    val r = runCatching { LiveKitFfi.call(id, 15_000, Ffi.FfiRequest.newBuilder().setTextReadAll(DataStream.TextStreamReaderReadAllRequest.newBuilder().setReaderHandle(reader.handle.id).setRequestAsyncId(id)).build()) }.getOrNull()
                    LiveKitFfi.drop(reader.handle.id)
                    val text = r?.textStreamReaderReadAll?.takeIf { it.hasContent() }?.content ?: return@launch
                    onEvent(LkEvent.Text(o.participantIdentity, reader.info.topic, text, reader.info.attributesMap.toMap()))
                }
            }
            Room.RoomEvent.MessageCase.CONNECTION_STATE_CHANGED -> onEvent(LkEvent.ConnectionState(e.connectionStateChanged.state.name))
            Room.RoomEvent.MessageCase.RECONNECTING -> onEvent(LkEvent.ConnectionState("RECONNECTING"))
            Room.RoomEvent.MessageCase.RECONNECTED -> onEvent(LkEvent.ConnectionState("CONNECTED"))
            Room.RoomEvent.MessageCase.DISCONNECTED -> { closed = true; onEvent(LkEvent.Disconnected(e.disconnected.reason.name)) }
            else -> {}
        }
    }

    private fun handleRpc(e: Rpc.RpcMethodInvocationEvent) {
        onEvent(LkEvent.Rpc(e.callerIdentity, e.method, e.payload) { payload, error ->
            val b = Rpc.RpcMethodInvocationResponseRequest.newBuilder().setLocalParticipantHandle(localHandle).setInvocationId(e.invocationId)
            if (error != null) b.setError(Rpc.RpcError.newBuilder().setCode(1500).setMessage(error.take(200))) else b.setPayload(payload ?: "")
            runCatching { LiveKitFfi.request(Ffi.FfiRequest.newBuilder().setRpcMethodInvocationResponse(b).build()) }
        })
    }

    /** Tells LiveKit this participant is ready for room events (until then they are held back). Call once, after keeping the room. */
    fun ready() { LiveKitFfi.request(Ffi.FfiRequest.newBuilder().setReadyForRoomEvent(Room.ReadyForRoomEventRequest.newBuilder().setRoomHandle(roomHandle)).build()) }

    fun registerRpc(method: String) { LiveKitFfi.request(Ffi.FfiRequest.newBuilder().setRegisterRpcMethod(Rpc.RegisterRpcMethodRequest.newBuilder().setLocalParticipantHandle(localHandle).setMethod(method)).build()) }

    /** Starts receiving one participant's microphone as 16 kHz mono 16-bit frames (10 ms each). */
    fun listen(participant: String, trackHandle: Long, onFrame: (ShortArray) -> Unit) {
        val resp = LiveKitFfi.request(Ffi.FfiRequest.newBuilder().setNewAudioStream(AudioFrame.NewAudioStreamRequest.newBuilder().setTrackHandle(trackHandle)
            .setType(AudioFrame.AudioStreamType.AUDIO_STREAM_NATIVE).setSampleRate(16_000).setNumChannels(1)).build())
        val sh = resp.newAudioStream.stream.handle.id
        streams.put(participant, sh)?.let { old -> LiveKitFfi.offStream(old); LiveKitFfi.drop(old) }
        LiveKitFfi.onStream(sh) { ev ->
            if (ev.hasFrameReceived()) {
                val f = ev.frameReceived.frame
                val pcm = NativeVoice.pcmRead(f.info.dataPtr, f.info.samplesPerChannel * f.info.numChannels)
                LiveKitFfi.drop(f.handle.id)
                if (!closed) onFrame(pcm)
            }
        }
    }

    fun stopListening(participant: String) { streams.remove(participant)?.let { LiveKitFfi.offStream(it); LiveKitFfi.drop(it) } }

    /** Creates and publishes CLU's voice track. [rate]: the PCM rate given to [speak]. */
    suspend fun publishVoice(rate: Int = 24_000, queueMs: Int = 1_000) {
        sourceRate = rate
        val src = LiveKitFfi.request(Ffi.FfiRequest.newBuilder().setNewAudioSource(AudioFrame.NewAudioSourceRequest.newBuilder()
            .setType(AudioFrame.AudioSourceType.AUDIO_SOURCE_NATIVE).setSampleRate(rate).setNumChannels(1).setQueueSizeMs(queueMs)
            .setOptions(AudioFrame.AudioSourceOptions.newBuilder().setEchoCancellation(false).setNoiseSuppression(false).setAutoGainControl(false))).build())
        sourceHandle = src.newAudioSource.source.handle.id
        val tr = LiveKitFfi.request(Ffi.FfiRequest.newBuilder().setCreateAudioTrack(Track.CreateAudioTrackRequest.newBuilder().setName("clu-voice").setSourceHandle(sourceHandle)).build())
        trackHandle = tr.createAudioTrack.track.handle.id
        val id = LiveKitFfi.asyncId()
        val ev = LiveKitFfi.call(id, 20_000, Ffi.FfiRequest.newBuilder().setPublishTrack(Room.PublishTrackRequest.newBuilder().setLocalParticipantHandle(localHandle).setTrackHandle(trackHandle)
            .setOptions(Room.TrackPublishOptions.newBuilder().setSource(Track.TrackSource.SOURCE_MICROPHONE).setDtx(false).setRed(true)).setRequestAsyncId(id)).build())
        if (ev.publishTrack.hasError()) throw LiveKitError("Could not publish CLU's voice: ${ev.publishTrack.error}")
        trackSid = ev.publishTrack.publication.info.sid
    }

    /**
     * Queues PCM (mono, [sourceRate]) for playback in 10 ms frames. Suspends while LiveKit's queue is full, so a caller that
     * speaks a long answer is paced by real time; [clear] empties the queue at once (barge-in).
     */
    suspend fun speak(pcm: ShortArray, isCancelled: () -> Boolean = { false }) {
        val frame = sourceRate / 100 * 2 // 20 ms
        var off = 0
        while (off < pcm.size && !closed && !isCancelled()) {
            val n = minOf(frame, pcm.size - off)
            val chunk = if (n == frame) pcm.copyOfRange(off, off + n) else pcm.copyOfRange(off, off + n).copyOf(frame) // pad the tail with silence
            val ptr = NativeVoice.pcmAlloc(chunk)
            try {
                val id = LiveKitFfi.asyncId()
                val ev = LiveKitFfi.call(id, 10_000, Ffi.FfiRequest.newBuilder().setCaptureAudioFrame(AudioFrame.CaptureAudioFrameRequest.newBuilder().setSourceHandle(sourceHandle)
                    .setBuffer(AudioFrame.AudioFrameBufferInfo.newBuilder().setDataPtr(ptr).setNumChannels(1).setSampleRate(sourceRate).setSamplesPerChannel(frame)).setRequestAsyncId(id)).build())
                if (ev.captureAudioFrame.error.isNotEmpty()) throw LiveKitError(ev.captureAudioFrame.error)
            } finally { NativeVoice.memFree(ptr) }
            off += n
        }
    }

    /** Drops everything queued for playback (the listener hears silence within a frame or two). */
    fun clear() { if (sourceHandle != 0L) runCatching { LiveKitFfi.request(Ffi.FfiRequest.newBuilder().setClearAudioBuffer(AudioFrame.ClearAudioBufferRequest.newBuilder().setSourceHandle(sourceHandle)).build()) } }

    suspend fun setAttributes(attrs: Map<String, String>) {
        val id = LiveKitFfi.asyncId()
        runCatching { LiveKitFfi.call(id, 5_000, Ffi.FfiRequest.newBuilder().setSetLocalAttributes(Room.SetLocalAttributesRequest.newBuilder().setLocalParticipantHandle(localHandle)
            .addAllAttributes(attrs.map { (k, v) -> Room.AttributesEntry.newBuilder().setKey(k).setValue(v).build() }).setRequestAsyncId(id)).build()) }
    }

    /** Sends one complete text stream on [topic] (transcripts, replies) to everyone, or to [to]. */
    suspend fun sendText(topic: String, text: String, attributes: Map<String, String> = emptyMap(), streamId: String? = null, to: List<String> = emptyList(), senderIdentity: String? = null) {
        val id = LiveKitFfi.asyncId()
        val opts = DataStream.StreamTextOptions.newBuilder().setTopic(topic).putAllAttributes(attributes).addAllDestinationIdentities(to)
        streamId?.let { opts.setId(it) }; senderIdentity?.let { opts.setSenderIdentity(it) }
        runCatching { LiveKitFfi.call(id, 10_000, Ffi.FfiRequest.newBuilder().setSendText(DataStream.StreamSendTextRequest.newBuilder().setLocalParticipantHandle(localHandle).setOptions(opts).setText(text).setRequestAsyncId(id)).build()) }
    }

    suspend fun sendData(topic: String, bytes: ByteArray, reliable: Boolean = true) {
        val ptr = NativeVoice.bytesAlloc(bytes)
        try {
            val id = LiveKitFfi.asyncId()
            runCatching { LiveKitFfi.call(id, 10_000, Ffi.FfiRequest.newBuilder().setPublishData(Room.PublishDataRequest.newBuilder().setLocalParticipantHandle(localHandle)
                .setDataPtr(ptr).setDataLen(bytes.size.toLong()).setReliable(reliable).setTopic(topic).setRequestAsyncId(id)).build()) }
        } finally { NativeVoice.memFree(ptr) }
    }

    suspend fun disconnect() {
        if (closed) return
        closed = true
        streams.keys.toList().forEach(::stopListening)
        val id = LiveKitFfi.asyncId()
        runCatching { LiveKitFfi.call(id, 5_000, Ffi.FfiRequest.newBuilder().setDisconnect(Room.DisconnectRequest.newBuilder().setRoomHandle(roomHandle).setRequestAsyncId(id)).build()) }
        LiveKitFfi.offRoom(roomHandle); LiveKitFfi.offRpc(localHandle)
        listOf(trackHandle, sourceHandle, localHandle, roomHandle).forEach(LiveKitFfi::drop)
    }

    private class Channel(val scope: CoroutineScope)
}
