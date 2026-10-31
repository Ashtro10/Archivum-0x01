package ai.clu.desktop

import ai.clu.core.llm.LlmStatus
import ai.clu.desktop.ResourceGovernor.Companion.GB
import org.junit.jupiter.api.Test
import kotlin.test.*

/** The governor's rules over a fake memory probe: no real model or memory pressure needed. */
class ResourceGovernorTest {
    private class Probe(var free: Long, var mine: Long, val total: Long = 32 * GB) : MemoryProbe {
        override fun systemAvailableBytes() = free
        override fun systemTotalBytes() = total
        override fun processBytes() = mine
    }

    private var status = LlmStatus.READY
    private var unloads = 0
    private fun governor(p: Probe) = ResourceGovernor(GovernorConfig(16 * GB, 2 * GB, (1.5 * GB).toLong()), p, { status }, { unloads++; status = LlmStatus.UNLOADED })

    @Test fun aModelMayUseOnlyWhatIsLeftOfTheBudgetAndOfFreeMemoryMinusTheReserve() {
        // Plenty free on the PC: the budget is the limit (16 GB minus the host's own 0.5 GB).
        assertEquals((15.5 * GB).toLong(), governor(Probe(free = 24 * GB, mine = (0.5 * GB).toLong())).availableForModel())
        // The user is busy (6 GB free): free memory minus the 2 GB reserve is the limit.
        assertEquals(4 * GB, governor(Probe(free = 6 * GB, mine = (0.5 * GB).toLong())).availableForModel())
        // Nothing to spare: never negative.
        assertEquals(0, governor(Probe(free = 1 * GB, mine = (0.5 * GB).toLong())).availableForModel())
    }

    @Test fun theCoreLoadCheckBecomesAHardCeilingOnThePc() {
        // LlmService allows a model up to 1.35x the reported figure; the desktop platform reports figure / 1.35.
        val g = governor(Probe(free = 24 * GB, mine = 0))
        val reported = DesktopLlmPlatform(g).availableMemoryBytes()
        assertTrue(reported * DesktopLlmPlatform.CORE_LOAD_SLACK <= g.availableForModel() + 1)
    }

    @Test fun anIdleModelIsUnloadedWhenThePcRunsShort() {
        val p = Probe(free = 8 * GB, mine = 6 * GB)
        val g = governor(p)
        assertNull(g.tick(), "enough memory: nothing to do")
        p.free = 1 * GB
        val why = g.tick()
        assertNotNull(why); assertTrue(why.startsWith("the PC is low on memory"), why)
        assertEquals(1, unloads); assertEquals("unloaded the model", g.events().single().action)
        assertNull(g.tick(), "already unloaded: not unloaded twice")
        assertEquals(1, unloads)
    }

    @Test fun overBudgetUnloadsTheModel() {
        val why = governor(Probe(free = 10 * GB, mine = 17 * GB)).tick()
        assertNotNull(why); assertTrue(why.startsWith("CLU is over its memory budget"), why)
    }

    @Test fun aReplyBeingGeneratedOrALoadInProgressIsNeverInterrupted() {
        val p = Probe(free = (0.5 * GB).toLong(), mine = 20 * GB)
        for (s in listOf(LlmStatus.GENERATING, LlmStatus.LOADING, LlmStatus.UNLOADED, LlmStatus.NO_MODEL, LlmStatus.ERROR)) {
            status = s
            assertNull(governor(p).tick(), "$s")
        }
        assertEquals(0, unloads)
        status = LlmStatus.READY // the reply finished: the next check acts
        assertNotNull(governor(p).tick())
    }

    @Test fun theSnapshotReportsTheFiguresInMegabytes() {
        val s = governor(Probe(free = 8 * GB, mine = 1 * GB)).snapshot()
        assertEquals(16 * 1024L, s["budgetMB"]); assertEquals(8 * 1024L, s["systemAvailableMB"]); assertEquals(1024L, s["processMB"])
        assertEquals(6 * 1024L, s["availableForModelMB"])
    }
}
