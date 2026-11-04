package ai.clu.core

import ai.clu.core.capability.CapabilityCommand
import ai.clu.core.capability.CapabilityOp
import org.junit.jupiter.api.Test
import kotlin.test.*

class CapabilityCommandTest {
    private fun p(s: String) = CapabilityCommand.parse(s)

    @Test fun developmentInstructionsAreRecognised() {
        p("Give yourself a weather service with a sci-fi interface.")!!.let { assertEquals(CapabilityOp.CREATE, it.op); assertEquals("weather", it.kind); assertEquals("scifi", it.style) }
        p("Give yourself a sci-fi weather service")!!.let { assertEquals(CapabilityOp.CREATE, it.op); assertEquals("weather", it.kind); assertEquals("scifi", it.style) }
        p("Build yourself a tactical map HUD.")!!.let { assertEquals(CapabilityOp.CREATE, it.op); assertEquals("map", it.kind); assertEquals("tactical", it.style) }
        p("Give yourself a tactical map HUD")!!.let { assertEquals("map", it.kind); assertEquals("tactical", it.style) }
        p("Create a fantasy map too.")!!.let { assertEquals(CapabilityOp.CREATE, it.op); assertEquals("fantasy", it.style); assertTrue(it.variant) }
        p("Create another map interface, but make it fantasy-themed.")!!.let { assertEquals("map", it.kind); assertEquals("fantasy", it.style); assertTrue(it.variant) }
        p("Build a dashboard that combines your weather and map services")!!.let { assertEquals("dashboard", it.kind) }
        p("Create an ocean-themed map")!!.let { assertEquals("map", it.kind); assertEquals("ocean", it.style) }
        p("Build yourself a pomodoro timer app")!!.let { assertEquals(CapabilityOp.CREATE, it.op); assertEquals("pomodoro timer", it.kind) }
    }

    @Test fun managementInstructionsAreRecognised() {
        p("Show me my maps")!!.let { assertEquals(CapabilityOp.LIST, it.op); assertEquals("map", it.kind) }
        p("Show me all the map experiences you've created.")!!.let { assertEquals(CapabilityOp.LIST, it.op); assertEquals("map", it.kind) }
        p("List your capabilities")!!.let { assertEquals(CapabilityOp.LIST, it.op); assertNull(it.kind) }
        p("Open my map")!!.let { assertEquals(CapabilityOp.OPEN, it.op); assertEquals("map", it.kind); assertNull(it.style) }
        p("Open the fantasy map")!!.let { assertEquals(CapabilityOp.OPEN, it.op); assertEquals("fantasy", it.style) }
        p("Make the tactical map my default.")!!.let { assertEquals(CapabilityOp.SET_DEFAULT, it.op); assertEquals("map", it.kind); assertEquals("tactical", it.style) }
        p("Make the tactical map your default map viewer")!!.let { assertEquals(CapabilityOp.SET_DEFAULT, it.op); assertEquals("tactical", it.style) }
        p("Improve your weather service.")!!.let { assertEquals(CapabilityOp.IMPROVE, it.op); assertEquals("weather", it.kind) }
        p("Improve my weather service with an hourly forecast")!!.let { assertEquals(CapabilityOp.IMPROVE, it.op); assertEquals("an hourly forecast", it.details) }
        p("Add an hourly forecast to my weather")!!.let { assertEquals(CapabilityOp.IMPROVE, it.op); assertEquals("weather", it.kind) }
        p("Roll back my weather service")!!.let { assertEquals(CapabilityOp.ROLLBACK, it.op) }
        p("Disable the fantasy map")!!.let { assertEquals(CapabilityOp.DISABLE, it.op); assertEquals("fantasy", it.style) }
        p("Uninstall the fantasy map")!!.let { assertEquals(CapabilityOp.UNINSTALL, it.op) }
    }

    @Test fun ordinaryConversationIsLeftAlone() {
        listOf("What's the weather in London?", "Show London on the map", "Make me a sandwich", "Build a bridge between the ideas", "Open YouTube",
            "Create a note that the dentist is on Friday", "How do maps work?", "Improve my essay", "Remember that my map of Goa is in the drawer", "Make sure you call me at 9")
            .forEach { assertNull(p(it), it) }
    }
}
