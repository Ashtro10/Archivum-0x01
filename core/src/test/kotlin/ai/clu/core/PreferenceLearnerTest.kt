package ai.clu.core

import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.learning.*
import ai.clu.core.memory.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Preference learning: how far a preference is established, when CLU asks, and that it never promotes on its own. */
class PreferenceLearnerTest {
    private val clock = ai.clu.core.util.ManualClock(1_760_000_000_000L)
    private val core = newCore(clock = clock)
    private val l get() = core.learner
    private val day = 86_400_000L
    private val brief = "setting:${Settings.RESPONSE_LENGTH}"

    @Test fun oneSignalIsOnlyOnce() {
        val st = l.record(brief, "brief", conversationId = "c1")
        assertEquals(PrefScope.ONCE, st.scope); assertEquals(1, st.signals)
        assertTrue(l.pending().isEmpty(), "one interaction never produces a question, let alone a preference")
    }

    @Test fun repeatsForTheSameKindOfTaskAreTaskScopedAndDoNotLeakIntoOtherTasks() {
        l.record("pref:detail", "concise", taskTag = "coding", conversationId = "c1")
        val st = l.record("pref:detail", "concise", taskTag = "coding", conversationId = "c1")
        assertEquals(PrefScope.TASK, st.scope)
        assertEquals(listOf("The owner prefers concise answers: lead with the answer, minimal elaboration."), l.taskInstructions("coding"))
        assertTrue(l.taskInstructions("writing").isEmpty()); assertTrue(core.prefs.instructions().isEmpty(), "not a standing preference")
    }

    @Test fun aPreferenceThatKeepsComingUpAcrossDaysIsAskedAboutNeverAppliedByItself() {
        l.record(brief, "brief", conversationId = "c1"); clock.advance(day)
        l.record(brief, "brief", conversationId = "c2"); assertTrue(l.pending().isEmpty(), "two is not enough"); clock.advance(day)
        val st = l.record(brief, "brief", conversationId = "c3")
        assertEquals(PrefScope.RECURRING, st.scope); assertEquals(3, st.conversations); assertEquals(3, st.days)
        val q = l.pending().single()
        assertTrue(q.question.contains("Make it my standing preference?") && q.question.contains("Response length"), q.question)
        assertTrue(q.evidence.isNotBlank())
        assertEquals("balanced", core.settings.string(Settings.RESPONSE_LENGTH), "asking changes nothing")
        // More signals do not pile up duplicate questions.
        l.record(brief, "brief", conversationId = "c4"); assertEquals(1, l.pending().size)
        l.accept(q.id)
        assertEquals("brief", core.settings.string(Settings.RESPONSE_LENGTH)); assertEquals(PrefScope.STABLE, l.status(brief, "brief").scope)
        assertTrue(l.pending().isEmpty())
    }

    @Test fun sameDaySameConversationRepeatsDoNotCountAsRecurring() {
        repeat(6) { l.record(brief, "brief", conversationId = "c1") }
        assertNotEquals(PrefScope.RECURRING, l.status(brief, "brief").scope); assertTrue(l.pending().isEmpty())
    }

    @Test fun aRejectedQuestionIsRememberedUntilThereIsNewEvidence() {
        repeat(3) { i -> l.record("pref:tone", "direct", conversationId = "c$i"); clock.advance(day) }
        val q = l.pending().single(); l.reject(q.id)
        assertEquals("", core.prefs.get("tone")?.value.orEmpty())
        l.record("pref:tone", "direct", conversationId = "c4"); l.record("pref:tone", "direct", conversationId = "c5")
        assertTrue(l.pending().isEmpty(), "two more signals are not new evidence")
        clock.advance(day); l.record("pref:tone", "direct", conversationId = "c6")
        assertEquals(1, l.pending().size, "three more signals: ask again")
        l.accept(l.pending().single().id)
        assertEquals("direct", core.prefs.get("tone")!!.value)
        assertTrue(core.prefs.instructions().any { it.contains("direct") }, "and it now shapes replies")
    }

    @Test fun negativeFeedbackIsCountedButNeverCreatesAPreference() {
        repeat(5) { i -> l.record("pref:examples", "include", polarity = -1, conversationId = "c$i"); clock.advance(day) }
        val st = l.status("pref:examples", "include")
        assertEquals(5, st.negative); assertEquals(0, st.signals); assertTrue(l.pending().isEmpty())
    }

    @Test fun anInstructionTargetBecomesAStandingInstructionOnlyWhenApproved() {
        repeat(3) { i -> l.record("instruction", "Never use bullet points for short answers", conversationId = "c$i"); clock.advance(day) }
        val q = l.pending().single(); assertTrue(core.prefs.instructions().isEmpty())
        l.accept(q.id)
        assertTrue(core.prefs.instructions().contains("Never use bullet points for short answers"))
    }

    @Test fun sensitiveSettingsCannotBePromotedThisWay() {
        val key = ai.clu.core.config.Settings.all.first { it.sensitive }.key
        repeat(3) { i -> l.record("setting:$key", "x", conversationId = "c$i"); clock.advance(day) }
        val q = l.pending().single()
        assertFails { l.accept(q.id) }
        assertEquals("PENDING", l.pending().single().status, "the question stays open; nothing was changed")
    }

    @Test fun learningCanBeSwitchedOffAndResetByTheOwner() {
        core.settings.set(Settings.LEARN_PREFERENCES, false)
        l.record(brief, "brief"); assertEquals(0, l.overview().size)
        core.settings.set(Settings.LEARN_PREFERENCES, true)
        l.record(brief, "brief"); assertEquals(1, l.overview().size)
        l.clearSignals(); assertEquals(0, l.overview().size)
    }

    @Test fun repeatedAlternativeChoicesComeFromTheConversationPathToo() {
        val engine = ScriptedEngine().also { core.useEngine(it) }
        val conv1 = core.conversations.create().id; val conv2 = core.conversations.create().id; val conv3 = core.conversations.create().id
        val owner = core.auth.actorForSpeaker(null, false)!!
        fun say(c: String, t: String) = runBlocking { engine.reply("OK."); core.agent.respond(TurnRequest(c, t, owner)).toList() }
        say(conv1, "be more concise"); clock.advance(day); say(conv2, "be more concise"); clock.advance(day); say(conv3, "be more concise")
        val st = l.status("setting:${Settings.RESPONSE_LENGTH}", "brief")
        assertEquals(3, st.signals); assertEquals(PrefScope.RECURRING, st.scope)
        assertEquals(1, l.pending().size)
        assertEquals("brief", core.settings.string(Settings.RESPONSE_LENGTH), "an explicit command still does what it says")
    }
}
