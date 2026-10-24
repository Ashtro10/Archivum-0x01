package ai.clu.core

import ai.clu.core.config.Settings
import ai.clu.core.diag.Level
import ai.clu.core.persona.ResponseTemplates
import ai.clu.core.proactive.ProactiveContext
import ai.clu.core.proactive.ProactivePlanner
import java.time.ZoneOffset
import kotlin.test.*
import org.junit.jupiter.api.Test

/**
 * Regression tests for the field crash on the iQOO Neo 9 Pro (Android 16): rendering "{owner}" with no form of
 * address compiled the regex ",?\s*\{owner}", which Android's ICU engine rejects (PatternSyntaxException on the main
 * thread at every first launch). Placeholders are now substituted literally, without any regex.
 */
class ResponseTemplatesTest {
    private val core = newCore()
    private val s = core.settings
    private val now = core.clock.now() // 08:53 UTC
    private val t = ResponseTemplates(s) { ZoneOffset.UTC }

    private fun owner(name: String, address: String = "") { s.set(Settings.OWNER_NAME, name); s.set(Settings.OWNER_ADDRESS, address) }
    private fun sub(template: String, vararg v: Pair<String, String>) = ResponseTemplates.substitute(template, mapOf(*v))

    @Test fun originalCrashingTemplatesRenderWithoutOwner() {
        owner("") // first launch: welcome screen not completed yet - the exact state of the crash
        assertEquals("Good morning.", t.render("{greeting}, {owner}.", now))
        assertEquals("I'm listening.", t.render("I'm listening, {owner}.", now))
        assertEquals("Yes?", t.render("Yes, {owner}?", now))
        // Every default greeting / termination template, through the same pick() path as the crash.
        for (seed in 0L..5L) {
            val g = t.pick(Settings.GREETINGS, now, seed)
            val e = t.pick(Settings.TERMINATION_RESPONSES, now, seed)
            for (out in listOf(g, e)) {
                assertFalse('{' in out || '}' in out, "placeholder left in '$out'")
                assertFalse(out.contains(" ,") || out.contains(",.") || out.contains(",?") || out.startsWith(","), "bad punctuation in '$out'")
            }
        }
    }

    @Test fun ownerPlaceholderUsesAddressThenName() {
        owner("Aishwarjya")
        assertEquals("Good morning, Aishwarjya.", t.render("{greeting}, {owner}.", now))
        owner("Aishwarjya", "Sir")
        assertEquals("Yes, Sir?", t.render("Yes, {owner}?", now))
        owner("  Aishwarjya  ") // stray spaces in the stored name are not spoken
        assertEquals("Yes, Aishwarjya?", t.render("Yes, {owner}?", now))
    }

    @Test fun missingOrEmptyOwnerDropsTheSeparator() {
        owner("")
        assertEquals("Battery is at 5%. You may want to charge me.", t.render("{owner}, battery is at 5%. You may want to charge me.", now))
        assertEquals("How was your day?", t.render("How was your day, {owner}?", now))
        assertEquals("Understood. Standing by.", t.render("Understood, {owner}. Standing by.", now))
        assertEquals("As you wish. Goodbye.", t.render("As you wish. {farewell}, {owner}.", now))
        assertEquals("Done. Shall I continue?", t.render("Done. {owner}, shall I continue?", now))
        assertEquals("Hello!", t.render("Hello {owner}!", now))
        assertEquals("", t.render("{owner}", now))
        owner("   ") // whitespace-only counts as missing
        assertEquals("Yes?", t.render("Yes, {owner}?", now))
    }

    @Test fun punctuationAroundPlaceholders() {
        owner("Sam")
        assertEquals("Sam!", t.render("{owner}!", now))
        assertEquals("Sam, wake up.", t.render("{owner} , wake up .", now))
        assertEquals("\"Sam\" (Clu) - 8:53 AM", t.render("\"{owner}\" ({name}) - {time}", now))
        assertEquals("Hi Sam. Hi Sam.", t.render("Hi   {owner}.   Hi {owner}.", now))
    }

    @Test fun multiplePlaceholders() {
        owner("Sam")
        s.set(Settings.CLU_NAME, "Clu")
        assertEquals("Good morning, Sam. Clu here, it is 8:53 AM. Goodbye, Sam.",
            t.render("{greeting}, {owner}. {name} here, it is {time}. {farewell}, {owner}.", now))
    }

    @Test fun unicodeNames() {
        for (name in listOf("Anaïs Müller", "अनन्या", "李雷", "Zoë 🙂", "Ελένη")) {
            owner(name)
            assertEquals("Yes, $name?", t.render("Yes, {owner}?", now), "name '$name'")
        }
        owner("")
        assertEquals("¿Listo?", sub("¿Listo, {owner}?", "owner" to ""))
        assertEquals("Ça va?", sub("{owner}, ça va?", "owner" to ""))
    }

    @Test fun regexSpecialCharactersAreLiteral() {
        val nasty = "\$1 \\0 (a+)* [x] ^.?|{} \\Q\\E"
        // In values: never interpreted as regex syntax or as replacement references ($1, \0).
        assertEquals("Yes, $nasty?", sub("Yes, {owner}?", "owner" to nasty))
        // A value containing a placeholder name is not expanded again (single pass).
        assertEquals("Hi {time}, it is 9:00.", sub("Hi {owner}, it is {time}.", "owner" to "{time}", "time" to "9:00"))
        // In templates: literal text, unknown and unbalanced braces stay as written.
        assertEquals("Cost \$5 (approx) [a-z]+ ^ * | \\d {unknown} {owner", sub("Cost \$5 (approx) [a-z]+ ^ * | \\d {unknown} {owner", "owner" to "Sam"))
        assertEquals("{} {{owner}}", sub("{} {{owner}}", "x" to "y")) // no known key -> untouched
        assertEquals("{Sam}", sub("{{owner}}", "owner" to "Sam"))
        // The settings path, with a name full of regex syntax.
        owner(nasty)
        assertEquals("Yes, $nasty?", t.render("Yes, {owner}?", now))
    }

    @Test fun emptyTemplateListDoesNotCrash() {
        assertEquals("", ResponseTemplates.substitute("", emptyMap()))
        assertTrue(s.list(Settings.APP_ALLOWLIST).isEmpty())
        assertEquals("", t.pick(Settings.APP_ALLOWLIST, now)) // previously floorMod(seed, 0) -> ArithmeticException
    }

    @Test fun greetingFailureIsLoggedAndSkippedNotThrown() {
        s.set(Settings.PROACTIVE_ENABLED, true)
        s.set(Settings.PROACTIVE_GREET_OPEN, true)
        val broken = object : ResponseTemplates(s) {
            override fun pick(key: String, nowMs: Long, seed: Long): String = throw IllegalStateException("malformed template")
        }
        val planner = ProactivePlanner(core.store, s, core.memories, broken, { ZoneOffset.UTC }, core.diag)
        val ctx = ProactiveContext(appInForeground = true, batteryPercent = 80, charging = false, lastInteractionAt = 0L, appOpenedNow = true)
        val msg = planner.plan(ctx, now) // must not throw
        assertNull(msg?.takeIf { it.kind == "greeting" }, "the failed greeting is skipped")
        val logged = core.diag.recent(minLevel = Level.ERROR).map { it.message }
        assertTrue(logged.any { it.contains("greeting message skipped") && it.contains("malformed template") }, "diagnostic logged: $logged")

        // With a working renderer the same call produces the greeting.
        val ok = ProactivePlanner(core.store, s, core.memories, t, { ZoneOffset.UTC }, core.diag).plan(ctx, now)
        assertEquals("greeting", ok?.kind)
    }
}
