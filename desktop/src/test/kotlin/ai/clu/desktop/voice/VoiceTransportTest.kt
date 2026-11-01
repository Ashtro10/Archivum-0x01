package ai.clu.desktop.voice

import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

/** The real transport: CLU's own livekit-server and two real WebRTC participants through LiveKit's Rust SDK (CLU and a stand-in phone). */
class VoiceTransportTest {
    private val bin = File("../native/build/voice/linux-x64/livekit-server").takeIf { it.isFile } ?: File("native/build/voice/linux-x64/livekit-server")
    private val dir = Files.createTempDirectory("clu-lk").toFile()
    private var server: LiveKitServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterEach fun stop() { scope.cancel(); server?.stop(); dir.deleteRecursively() }

    private fun tone(hz: Double, ms: Int, rate: Int) = ShortArray(rate * ms / 1000) { i -> (sin(2 * PI * hz * i / rate) * 9000).toInt().toShort() }

    @Test fun twoParticipantsHearEachOtherThroughCLUsOwnServer() = runBlocking {
        assumeTrue(bin.isFile && NativeVoice.load(), "voice native build missing: ${NativeVoice.loadError}")
        val s = LiveKitServer(dir, bin) { println("[lk] $it") }.also { server = it }
        assertTrue(s.ensure(lan = false), s.message); assertEquals(LiveKitServer.State.RUNNING, s.state)
        assertTrue(File(dir, "livekit.yaml").readText().let { !it.contains("devkey") && it.contains("127.0.0.1") })
        val cluHeard = CopyOnWriteArrayList<ShortArray>(); val phoneHeard = CopyOnWriteArrayList<ShortArray>()
        lateinit var clu: LkRoom; lateinit var phone: LkRoom
        val cluEvents = CopyOnWriteArrayList<LkEvent>()
        clu = LkRoom.connect(s.localUrl, s.tokens.issue("clu", "room1", agent = true), scope) { e -> cluEvents += e; if (e is LkEvent.AudioTrack) clu.listen(e.participant, e.trackHandle) { cluHeard += it } }.first
        clu.ready(); clu.publishVoice(24_000)
        phone = LkRoom.connect(s.localUrl, s.tokens.issue("phone", "room1"), scope) { e -> if (e is LkEvent.AudioTrack) phone.listen(e.participant, e.trackHandle) { phoneHeard += it } }.first
        phone.ready(); phone.publishVoice(24_000)
        val talk = launch { repeat(15) { phone.speak(tone(440.0, 200, 24_000)) } }
        val reply = launch { repeat(15) { clu.speak(tone(220.0, 200, 24_000)) } }
        val ok = withTimeoutOrNull(20_000) { while (cluHeard.sumOf { it.size } < 16_000 || phoneHeard.sumOf { it.size } < 16_000) delay(50); true }
        if (ok == null) { println("[lk] events: $cluEvents heard clu=${cluHeard.sumOf { it.size }} phone=${phoneHeard.sumOf { it.size }}"); println("[lk] log: " + File(dir, "livekit.log").readLines().takeLast(30).joinToString("\n")); fail("no audio") }
        talk.cancel(); reply.cancel()
        assertTrue(cluEvents.any { it is LkEvent.Joined && it.participant == "phone" } || cluEvents.any { it is LkEvent.AudioTrack && it.participant == "phone" })
        fun energy(l: List<ShortArray>) = l.flatMap { it.asIterable() }.takeLast(8000).map { abs(it.toInt()) }.average()
        assertTrue(energy(cluHeard) > 1000, "CLU heard the phone's audio (16 kHz mono): ${energy(cluHeard)}")
        assertTrue(energy(phoneHeard) > 1000, "the phone heard CLU: ${energy(phoneHeard)}")
        println("[lk] CLU received ${cluHeard.sumOf { it.size }} samples, phone ${phoneHeard.sumOf { it.size }}")
        clu.disconnect(); phone.disconnect()
    }
}
