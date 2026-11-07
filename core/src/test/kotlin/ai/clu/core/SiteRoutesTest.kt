package ai.clu.core

import ai.clu.core.agent.IntentRouter
import ai.clu.core.browser.Sites
import ai.clu.core.voice.Backchannel
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Named-site open/search, page actions, presence and backchannels: the deterministic parts adopted from the butler repo. */
class SiteRoutesTest {
    private fun r(t: String) = IntentRouter.route(t)
    private fun arg(t: String, k: String) = r(t)?.args?.get(k)?.jsonPrimitive?.content

    @Test fun siteSearchesGoToTheSitesOwnSearch() {
        assertEquals("browser.search", r("search YouTube for lo-fi music")?.tool)
        assertEquals("youtube", arg("search YouTube for lo-fi music", "site")?.lowercase())
        assertEquals("lo-fi music", arg("search YouTube for lo-fi music", "query"))
        assertEquals("browser.search", r("play arijit singh songs on youtube")?.tool)
        assertEquals("browser.search", r("find wireless headphones on Amazon")?.tool)
        assertEquals("https://www.youtube.com/results?search_query=lo-fi+music", Sites.searchUrl("YouTube", "lo-fi music"))
        assertTrue(Sites.searchUrl(null, "cats").startsWith("https://duckduckgo.com/?q="))
    }

    @Test fun opensSitesAndAddresses() {
        assertEquals("browser.open", r("go to wikipedia")?.tool)
        assertEquals("browser.open", r("open example.org")?.tool)
        assertEquals("https://www.youtube.com", Sites.urlFor("the YouTube website"))
        assertEquals("https://example.org", Sites.urlFor("example.org"))
        assertNull(Sites.urlFor("https://user:pw@example.org"))
        assertNull(Sites.urlFor("javascript:alert(1)"))
        assertNull(Sites.urlFor("file:///etc/passwd"))
    }

    @Test fun pageActions() {
        assertEquals("browser.read", r("read the page")?.tool)
        assertEquals("browser.inspect", r("what buttons are on this page?")?.tool)
        assertEquals("browser.scroll", r("scroll down")?.tool)
        assertEquals("browser.back", r("go back")?.tool)
        assertEquals("browser.key", r("press enter")?.tool)
        assertEquals("browser.click", r("click sign in")?.tool)
        assertEquals("browser.type", r("type cats into the search box")?.tool)
        assertEquals("browser.screenshot", r("take a screenshot")?.tool)
    }

    @Test fun consequentialControlsAreRecognised() {
        listOf("Place order", "Buy now", "Delete account", "Send", "Submit", "Confirm payment", "Log out").forEach { assertTrue(Sites.consequential(it), it) }
        listOf("Sign in", "Next", "About us", "Search", "Home").forEach { assertFalse(Sites.consequential(it), it) }
    }

    @Test fun presenceCheck() {
        listOf("you there?", "are you there", "you awake?").forEach { assertEquals("clu.presence", r(it)?.tool, it) }
        assertNotEquals("clu.presence", r("are you there in Pune tomorrow")?.tool)
    }

    @Test fun backchannelsDontTakeTheTurn() {
        listOf("mhm", "uh-huh", "yeah", "right", "okay", "I see", "got it", "go on", "yeah yeah").forEach { assertTrue(Backchannel.only(it), it) }
        listOf("wait", "no", "stop", "yeah but what about tomorrow", "what", "CLU", "okay stop", "", "tell me about the weather").forEach { assertFalse(Backchannel.only(it), it) }
    }
}
