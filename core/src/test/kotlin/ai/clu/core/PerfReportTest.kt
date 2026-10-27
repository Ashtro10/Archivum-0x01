package ai.clu.core

import ai.clu.core.agent.TurnRequest
import ai.clu.core.diag.PerfReport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Stage 3.1: the shareable performance report has units, clear gaps, versions and timestamps, and no private data. */
class PerfReportTest {
    @Test fun reportIsUsefulAndPrivate(): Unit = runBlocking {
        val core = newCore(); val engine = ScriptedEngine().also { core.useEngine(it) }
        core.memories.save(ai.clu.core.memory.Memory(category = ai.clu.core.memory.MemoryCategory.CORE_FACT, title = "Secret plan",
            content = "zebra-secret-memory about the quokkaproject", source = ai.clu.core.memory.MemorySource.OWNER_SUPPLIED))
        core.diary.add("diary-secret-line", date = "2025-10-01")
        engine.reply("A private reply text.")
        core.agent.respond(TurnRequest(core.conversations.create().id, "Tell me about my quokkaproject secret", core.auth.actorForSpeaker(null, false)!!)).toList()
        core.diag.perf("app_create_ms", 812.0); core.diag.perf("app_create_ms", 790.0); core.diag.perf("stop_to_silence_ms", 12.4)
        core.diag.i("agent", "a log line mentioning quokkaproject secret")
        val r = PerfReport.build("1.5.2-prototype (versionCode 9)", "Xiaomi 23043RP34G, Android 14 (API 34)", mapOf("online" to "yes"),
            "PSS 800 MB", core.diag.perfSummary(), 1_761_550_000_000L)
        assertTrue(r.contains("App: 1.5.2-prototype (versionCode 9)") && r.contains("Device: Xiaomi 23043RP34G") && r.contains("Generated: 2025-"))
        assertTrue(r.contains("- Cold start (app process ready) (app_create_ms): p50 ") && r.contains(" ms"), r)
        assertTrue(r.contains("- Stop/interruption → playback silenced (app side) (stop_to_silence_ms): p50 12.4 ms"), r)
        assertTrue(r.contains("- Return from background → first frame (resume_to_frame_ms): not measured yet"))
        assertTrue(r.contains("(memory_retrieval_ms): p50"), "memory retrieval is measured per message")
        assertTrue(r.contains("- Battery: not measured by Clu"))
        for (secret in listOf("zebra", "diary-secret", "private reply", "quokkaproject", "Secret plan")) assertFalse(r.contains(secret, true), "report leaks \"$secret\": $r")
    }
}
