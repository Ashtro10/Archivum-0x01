package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.*
import ai.clu.core.security.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.jupiter.api.Test

class SecuritySettingsTest {
    private val core = newCore()
    private val s = core.settings
    private val owner get() = core.people.owner()!!
    private val ashutosh by lazy { core.people.save(Person(name = "Ashutosh", role = PersonRole.TRUSTED, allowedTools = listOf("time.now"))) }

    @Test fun speakerId() {
        // With speaker identification off, every voice is treated as the owner (no voice gating).
        assertEquals(PersonRole.GUEST, core.auth.actorForSpeaker(null, speakerIdActive = true)!!.role)
        s.set(Settings.SPEAKER_VERIFY, false)
        val active = s.bool(Settings.SPEAKER_VERIFY)
        assertEquals(PersonRole.OWNER, core.auth.actorForSpeaker(null, speakerIdActive = active)!!.role)
    }

    @Test fun threshold() {
        core.people.addVoiceprint(owner.id, "m", floatArrayOf(1f, 0f, 0f))
        core.people.addVoiceprint(ashutosh.id, "m", floatArrayOf(0f, 1f, 0f))
        val sample = floatArrayOf(0.8f, 0.6f, 0f) // cosine 0.8 to owner, 0.6 to Ashutosh
        assertEquals(owner.id, SpeakerMatcher.identify(sample, core.people.voiceprints("m"), s.double(Settings.SPEAKER_THRESHOLD)).personId)
        s.set(Settings.SPEAKER_THRESHOLD, 0.9)
        assertNull(SpeakerMatcher.identify(sample, core.people.voiceprints("m"), s.double(Settings.SPEAKER_THRESHOLD)).personId)
    }

    @Test fun unknownPolicy() {
        assertEquals(PersonRole.GUEST, core.auth.actorForSpeaker(null, true)!!.role)
        assertEquals(SettingResult.RequiresVerification, s.set(Settings.UNKNOWN_POLICY, "ignore"))
        s.set(Settings.UNKNOWN_POLICY, "ignore", verified = true)
        assertNull(core.auth.actorForSpeaker(null, true))
        s.set(Settings.UNKNOWN_POLICY, "owner", verified = true)
        assertEquals(PersonRole.OWNER, core.auth.actorForSpeaker(null, true)!!.role)
    }

    @Test fun refusal() = runBlocking {
        s.set(Settings.REFUSAL_LINES, listOf("Access denied, human."))
        val conv = core.conversations.create()
        val guest = core.auth.actorForSpeaker(null, true)!!
        val events = mutableListOf<AgentEvent>()
        core.agent.handle(TurnRequest(conv.id, "Clu, turn off", guest, viaVoice = true)) { events += it }
        assertEquals("Access denied, human.", events.filterIsInstance<AgentEvent.Done>().single().text)
        assertTrue(events.none { it is AgentEvent.Control })
        // The owner is obeyed.
        events.clear()
        core.agent.handle(TurnRequest(conv.id, "Clu, turn off", core.auth.actorForSpeaker(owner.id, true)!!, viaVoice = true)) { events += it }
        assertTrue(events.any { it is AgentEvent.Control && it.command == "stop_listening" })
    }

    @Test fun pinForSensitive() {
        val ownerVoice = Actor(owner.id, PersonRole.OWNER, viaVoice = true)
        assertIs<AuthDecision.NeedsVerification>(core.auth.decide(ownerVoice, Sensitivity.SENSITIVE))
        assertEquals(AuthDecision.Allow, core.auth.decide(ownerVoice.copy(verifiedAt = core.clock.now()), Sensitivity.SENSITIVE))
        assertEquals(SettingResult.RequiresVerification, s.set(Settings.PIN_FOR_SENSITIVE, false))
        s.set(Settings.PIN_FOR_SENSITIVE, false, verified = true)
        assertEquals(AuthDecision.Allow, core.auth.decide(ownerVoice, Sensitivity.SENSITIVE))
        // Recognised friends never get sensitive access, whatever the setting.
        assertIs<AuthDecision.NeedsVerification>(core.auth.decide(Actor(ashutosh.id, PersonRole.TRUSTED, true), Sensitivity.SENSITIVE))
    }

    @Test fun authWindow() {
        val clock = core.clock as ai.clu.core.util.ManualClock
        val a = Actor(owner.id, PersonRole.OWNER, true, verifiedAt = clock.now())
        s.set(Settings.AUTH_WINDOW, 60)
        clock.advance(30_000); assertEquals(AuthDecision.Allow, core.auth.decide(a, Sensitivity.SENSITIVE))
        clock.advance(40_000); assertIs<AuthDecision.NeedsVerification>(core.auth.decide(a, Sensitivity.SENSITIVE))
    }
}
