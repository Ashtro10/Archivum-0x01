package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.search.Places
import ai.clu.core.security.Actor
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.jupiter.api.Test

/** identity.homeLocation: hazard answers measure from it; without it Clu asks; it is resolved on the device. */
class HomeLocationTest {
    @Test fun homeLocation() {
        assertEquals("Pune", Places.resolve("pune")?.name)
        assertEquals("Chennai", Places.resolve("we are in Chennai now")?.name)
        assertEquals(18.52, Places.resolve("18.52, 73.86")!!.lat)
        assertNull(Places.resolve("Atlantis"), "an unknown place is unknown, never guessed")
        assertEquals("east-northeast", Places.direction(13.08, 80.27, 15.2, 86.4))

        val platform = TestPlatform(); val core = newCore(platform = platform); core.useEngine(ScriptedEngine())
        platform.fakeHttp.routes["gdacsapi"] = HttpResponse(200, ConversationContextTest.GDACS)
        fun ask(t: String) = runBlocking { core.agent.respond(TurnRequest(core.conversations.create().id, t, Actor.ownerInApp())).toList() }
            .filterIsInstance<AgentEvent.Done>().single().text
        assertTrue(ask("Which cyclone is heading towards us?").startsWith("Which city"), "no location saved: Clu asks")
        assertEquals(0, platform.fakeHttp.requests.size, "nothing checked before knowing where")
        core.settings.set(Settings.HOME_LOCATION, "Chennai")
        assertTrue("of Chennai" in ask("Which cyclone is heading towards us?"), "measured from the saved location")
        assertTrue(platform.fakeHttp.requests.none { "Chennai" in it || "13.08" in it }, "the location itself isn't sent to the alert service")
    }
}
