package ai.clu.core

import ai.clu.core.agent.IntentRouter
import ai.clu.core.tools.CalcTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Arithmetic is answered exactly by the calculator, typed or spoken, and never guessed by the model. */
class CalculatorTest {
    private fun calc(e: String) = CalcTool.format(CalcTool.evaluate(e))

    @Test fun exactResultsWithoutFloatNoise() {
        assertEquals("0.3", calc("0.1 + 0.2"))
        assertEquals("84", calc("12 * 7")); assertEquals("391", calc("17x23")); assertEquals("2", calc("10 % 4"))
        assertEquals("16.6666666667", calc("(12.5*4)/3"))
        assertEquals("1,024", calc("2^10")); assertEquals("-8", calc("-2^3"))
        assertEquals("1,250,000", calc("1,000,000 * 1.25"))
    }

    @Test fun spokenFormsFunctionsAndConstants() {
        assertEquals("36", calc("15 percent of 240")); assertEquals("36", calc("15% of 240"))
        assertEquals("84", calc("12 times 7")); assertEquals("4", calc("12 divided by 3")); assertEquals("1,024", calc("2 to the power of 10"))
        assertEquals("9", calc("square root of 81")); assertEquals("3", calc("cube root of 27")); assertEquals("25", calc("5 squared"))
        assertEquals("3.14159265359", calc("pi")); assertEquals("1", calc("sin(90)")); assertEquals("2", calc("log(100)"))
        assertEquals("5", calc("7 minus 2")); assertEquals("19", calc("12 plus 7"))
    }

    @Test fun refusesWhatItCannotDo() {
        for (bad in listOf("1/0", "sqrt(-1)", "2 +", "banana * 3", "log(0)", "(".repeat(100) + "1" + ")".repeat(100)))
            assertFailsWith<IllegalArgumentException>(bad) { CalcTool.evaluate(bad) }
        val r = runBlocking { CalcTool().run(buildJsonObject { put("expression", "5/0") }, ai.clu.core.tools.ToolContext(ai.clu.core.security.Actor.ownerInApp(), newCore().personas.active(), null, 0)) }
        assertFalse(r.success); assertTrue("division by zero" in r.summary)
    }

    @Test fun routesArithmeticAndOnlyArithmetic() {
        fun routed(t: String) = IntentRouter.route(t)?.tool
        for (q in listOf("what is 12 * 7", "what's 15 percent of 240?", "calculate 2 to the power of 10", "how much is 1200 divided by 4", "square root of 81", "12 times 7"))
            assertEquals("calc", routed(q), q)
        for (q in listOf("what is 2023", "what is covid-19", "set volume to 40%", "2024-05-12", "98765 43210", "what is pi", "tell me about 3 musketeers"))
            assertNotEquals("calc", routed(q), q)
    }
}
