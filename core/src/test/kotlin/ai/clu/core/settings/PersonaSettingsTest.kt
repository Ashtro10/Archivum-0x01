package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.config.*
import ai.clu.core.llm.GenerationParams
import ai.clu.core.persona.*
import ai.clu.core.voice.SpeechTextPreprocessor
import ai.clu.core.voice.VoiceConfig
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*
import org.junit.jupiter.api.Test

class PersonaSettingsTest {
    private val core = newCore()
    private val s = core.settings
    private fun system(): String = core.promptBuilder.system(PromptContext(core.personas.active(), emptyList(), emptyList(), core.clock.now(), true, "portable"))
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 23, h, m, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun cluName() {
        assertEquals(SettingResult.RequiresVerification, s.set(Settings.CLU_NAME, "Vision"))
        assertTrue(system().startsWith("You are Clu,"))
        assertEquals(SettingResult.Ok, s.set(Settings.CLU_NAME, "Vision", verified = true))
        assertTrue(system().startsWith("You are Vision,"))
        assertIs<SettingResult.Invalid>(s.set(Settings.CLU_NAME, "", verified = true))
        // persists across restart
        val again = newCore(db = JdbcDriver((core.db as JdbcDriver).path))
        assertEquals("Vision", again.settings.string(Settings.CLU_NAME))
    }

    @Test fun ownerName() {
        assertFalse(system().contains("Aishwarjya"))
        s.set(Settings.OWNER_NAME, "Aishwarjya")
        assertTrue(system().contains("your owner, Aishwarjya"))
    }

    @Test fun ownerAddress() {
        s.set(Settings.OWNER_NAME, "Aishwarjya")
        assertEquals("Yes, Aishwarjya?", core.templates.render("Yes, {owner}?", core.clock.now()))
        s.set(Settings.OWNER_ADDRESS, "sir")
        assertTrue(system().contains("Address the owner as \"sir\""))
        assertEquals("Yes, sir?", core.templates.render("Yes, {owner}?", core.clock.now()))
        s.set(Settings.OWNER_NAME, ""); s.set(Settings.OWNER_ADDRESS, "")
        assertEquals("Yes?", core.templates.render("Yes, {owner}?", core.clock.now()))
    }

    @Test fun language() {
        assertFalse(system().contains("Respond in Hindi"))
        s.set(Settings.LANGUAGE, "hi"); assertTrue(system().contains("Respond in Hindi"))
        s.set(Settings.LANGUAGE, "hinglish"); assertTrue(system().contains("Hinglish"))
        assertIs<SettingResult.Invalid>(s.set(Settings.LANGUAGE, "klingon"))
    }

    @Test fun pronunciations() {
        s.set(Settings.PRONUNCIATIONS, listOf("Ashutosh=Aa-shu-tosh", "Clu=Clue"))
        val pre = SpeechTextPreprocessor(VoiceConfig.from(s).pronunciations)
        assertEquals("Aa-shu-tosh called. Clue answered.", pre.apply("Ashutosh called. Clu answered."))
        assertIs<SettingResult.Invalid>(s.set(Settings.PRONUNCIATIONS, listOf("")))
    }

    @Test fun responseLength() {
        s.set(Settings.RESPONSE_LENGTH, "brief")
        val brief = GenerationParams.from(s, core.prefs, false).maxTokens
        assertTrue(system().contains("Keep replies brief"))
        s.set(Settings.RESPONSE_LENGTH, "detailed")
        val detailed = GenerationParams.from(s, core.prefs, false).maxTokens
        assertTrue(system().contains("detailed, well-structured"))
        assertTrue(detailed > brief * 2, "brief=$brief detailed=$detailed")
    }

    @Test fun formality() {
        s.set(Settings.FORMALITY, "formal"); assertTrue(system().contains("Speak formally"))
        s.set(Settings.FORMALITY, "casual"); assertTrue(system().contains("Speak casually"))
    }

    @Test fun humor() {
        s.set(Settings.HUMOR, "none"); assertTrue(system().contains("Do not use humour"))
        s.set(Settings.HUMOR, "frequent"); assertTrue(system().contains("Use humour often"))
    }

    @Test fun sarcasm() {
        s.set(Settings.SARCASM, "off"); assertTrue(system().contains("Do not be sarcastic"))
        assertEquals("I'm sorry, only my owner can ask for that.", core.auth.refusalLine(1))
        s.set(Settings.SARCASM, "sharp"); assertTrue(system().contains("Sharp sarcasm"))
        assertTrue(core.auth.refusalLine(1) in s.list(Settings.REFUSAL_LINES))
    }

    @Test fun directness() {
        s.set(Settings.DIRECTNESS, "blunt"); assertTrue(system().contains("Be blunt"))
        s.set(Settings.DIRECTNESS, "diplomatic"); assertTrue(system().contains("diplomatically"))
    }

    @Test fun greetOnStart() {
        assertNotNull(UiPolicy.activationGreeting(s, core.templates, core.clock.now()))
        s.set(Settings.GREET_ON_START, false)
        assertNull(UiPolicy.activationGreeting(s, core.templates, core.clock.now()))
    }

    @Test fun greetings() {
        s.set(Settings.OWNER_ADDRESS, "boss")
        s.set(Settings.GREETINGS, listOf("{greeting}, {owner}."))
        assertEquals("Good morning, boss.", UiPolicy.activationGreeting(s, core.templates, at(9, 0)))
        assertEquals("Good evening, boss.", UiPolicy.activationGreeting(s, core.templates, at(20, 0)))
        assertIs<SettingResult.Invalid>(s.set(Settings.GREETINGS, emptyList<String>()))
    }

    @Test fun customInstructions() {
        s.set(Settings.CUSTOM_INSTRUCTIONS, "Always end with a single-word summary.")
        assertTrue(system().contains("Owner's instructions: Always end with a single-word summary."))
    }

    @Test fun quietHours() {
        s.set(Settings.QUIET_START, "22:30"); s.set(Settings.QUIET_END, "07:00")
        assertTrue(QuietHours.isQuiet(s, at(23, 0)))
        assertTrue(QuietHours.isQuiet(s, at(6, 59)))
        assertFalse(QuietHours.isQuiet(s, at(12, 0)))
        s.set(Settings.QUIET_START, "13:00"); s.set(Settings.QUIET_END, "14:00")
        assertTrue(QuietHours.isQuiet(s, at(13, 30)))
        assertFalse(QuietHours.isQuiet(s, at(23, 0)))
        s.set(Settings.QUIET_ENABLED, false)
        assertFalse(QuietHours.isQuiet(s, at(13, 30)))
        assertIs<SettingResult.Invalid>(s.set(Settings.QUIET_START, "25:00"))
    }

    @Test fun quietMuteSpeech() {
        assertTrue(UiPolicy.shouldSpeak(s, viaVoice = true, nowMs = at(23, 30)))
        s.set(Settings.QUIET_MUTE_SPEECH, true)
        assertFalse(UiPolicy.shouldSpeak(s, viaVoice = true, nowMs = at(23, 30)))
        assertTrue(UiPolicy.shouldSpeak(s, viaVoice = true, nowMs = at(12, 0)))
    }

    @Test fun activePersona() {
        val rick = core.characters.save(CharacterProfile(name = "Rick", personality = "Cynical genius scientist."))
        assertTrue(core.personas.active().isClu)
        s.set(Settings.ACTIVE_PERSONA, rick.id)
        assertEquals("Rick", core.personas.active().name)
        assertTrue(system().startsWith("You are Rick, a fictional character"))
        assertTrue(system().contains("Cynical genius scientist."))
        core.personas.switchToClu()
        assertTrue(system().startsWith("You are Clu,"))
    }
}
