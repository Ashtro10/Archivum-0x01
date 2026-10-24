package ai.clu.livekit

import livekit.LivekitModels
import livekit.LivekitRtc
import kotlin.test.*
import org.junit.jupiter.api.Test

/** Unit tests that need no server: candidate encoding and protobuf round trips of the messages the client uses. */
class SignallingTest {
    @Test fun candidateJsonRoundTrip() {
        val c = IceCandidate("candidate:1 1 udp 2122260223 192.168.43.5 50000 typ host generation 0 ufrag \"x\"", "0", 0)
        val json = LiveKitRoom.candidateJson(c)
        assertEquals(c, LiveKitRoom.parseCandidate(json))
        val server = """{"candidate":"candidate:2 1 udp 1 10.0.0.1 7882 typ host","sdpMid":"1","sdpMLineIndex":1,"usernameFragment":"ab"}"""
        assertEquals(IceCandidate("candidate:2 1 udp 1 10.0.0.1 7882 typ host", "1", 1), LiveKitRoom.parseCandidate(server))
        assertNull(LiveKitRoom.parseCandidate("{}"))
    }

    @Test fun dataPacketRoundTrip() {
        val pkt = LivekitModels.DataPacket.newBuilder().setKind(LivekitModels.DataPacket.Kind.RELIABLE)
            .setUser(LivekitModels.UserPacket.newBuilder().setTopic("clu.reply").setPayload(com.google.protobuf.ByteString.copyFromUtf8("{\"v\":1}")))
            .addDestinationIdentities("clu-voice-agent").setSequence(3).build()
        val back = LivekitModels.DataPacket.parseFrom(pkt.toByteArray())
        assertEquals("clu.reply", back.user.topic)
        assertEquals("{\"v\":1}", back.user.payload.toStringUtf8())
        assertEquals(listOf("clu-voice-agent"), back.destinationIdentitiesList)
    }

    @Test fun addTrackRequestForMicrophone() {
        val req = LivekitRtc.SignalRequest.newBuilder().setAddTrack(LivekitRtc.AddTrackRequest.newBuilder().setCid("TR_mic")
            .setName("microphone").setType(LivekitModels.TrackType.AUDIO).setSource(LivekitModels.TrackSource.MICROPHONE)).build()
        val back = LivekitRtc.SignalRequest.parseFrom(req.toByteArray())
        assertEquals(LivekitRtc.SignalRequest.MessageCase.ADD_TRACK, back.messageCase)
        assertEquals(LivekitModels.TrackSource.MICROPHONE, back.addTrack.source)
    }

    @Test fun connectFailureIsReportedNotHung() {
        // No server on this port: connect must fail quickly with a clear error and leave the room closed.
        val events = mutableListOf<RoomEvent>()
        val room = LiveKitRoom(object : RtcFactory {
            override fun createPeer(iceServers: List<IceServer>, observer: RtcObserver) = error("not reached")
            override fun createMicrophoneTrack(id: String) = error("not reached")
            override fun dispose() {}
        }, { events += it })
        val e = assertFails { kotlinx.coroutines.runBlocking { room.connect("ws://127.0.0.1:1", "x.y.z", timeoutMs = 5000) } }
        assertTrue(e.message!!.contains("signalling connection failed"), e.message)
        assertTrue(room.closed)
        assertTrue(events.any { it is RoomEvent.Disconnected })
    }
}
