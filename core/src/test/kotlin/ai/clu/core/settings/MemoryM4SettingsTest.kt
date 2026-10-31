package ai.clu.core.settings

import ai.clu.core.config.Settings
import ai.clu.core.learning.PrefScope
import ai.clu.core.memory.*
import ai.clu.core.newCore
import ai.clu.core.util.ManualClock
import org.junit.jupiter.api.Test
import kotlin.test.*

/** One check per M4 memory setting that it changes behaviour (the registry requires a verifying test for each). */
class MemoryM4SettingsTest {
    private val clock = ManualClock(1_760_000_000_000L)
    private val core = newCore(clock = clock)

    @Test fun workingHours() {
        core.settings.set(Settings.WORKING_HOURS, 1)
        core.memory.working.put("short")
        clock.advance(2 * 3_600_000L)
        assertEquals(0, core.memory.working.list().size)
        core.settings.set(Settings.WORKING_HOURS, 48)
        core.memory.working.put("long"); clock.advance(2 * 3_600_000L)
        assertEquals(1, core.memory.working.list().size)
    }

    @Test fun formation() {
        core.settings.set(Settings.FORMATION, "off")
        assertTrue(core.formation.process("My sister's name is Mira.", null).isEmpty())
        core.settings.set(Settings.FORMATION, "suggest")
        assertTrue(core.formation.process("My sister's name is Mira.", null).single() is MemoryFormation.Outcome.Suggested)
        assertEquals(0L, core.memories.count())
        core.settings.set(Settings.FORMATION, "auto")
        assertTrue(core.formation.process("My dog's name is Biscuit.", null).single() is MemoryFormation.Outcome.Saved)
    }

    @Test fun consolidate() {
        fun add() = core.memory.remember(Memory(category = MemoryCategory.CORE_FACT, title = "Same", content = "Exactly the same words.", source = MemorySource.OWNER_SUPPLIED)).also { clock.advance(1000) }
        core.settings.set(Settings.CONSOLIDATION, false); add(); add()
        assertEquals(0, core.consolidator.run().autoMerged); assertEquals(2L, core.memories.count())
        core.settings.set(Settings.CONSOLIDATION, true)
        assertEquals(1, core.consolidator.run().autoMerged); assertEquals(1L, core.memories.count())
    }

    @Test fun learnPreferences() {
        val t = "setting:${Settings.RESPONSE_LENGTH}"
        core.settings.set(Settings.LEARN_PREFERENCES, false)
        core.learner.record(t, "brief"); assertEquals(0, core.learner.overview().size)
        core.settings.set(Settings.LEARN_PREFERENCES, true)
        assertEquals(PrefScope.ONCE, core.learner.record(t, "brief").scope); assertEquals(1, core.learner.overview().size)
    }
}
