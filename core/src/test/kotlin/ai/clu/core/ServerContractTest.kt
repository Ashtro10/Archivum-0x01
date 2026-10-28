package ai.clu.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The LiveKit server agent's side of the shared agent contract (server/tests/test_contract.py: the same
 * contract/agent_contract.json as [AgentContractTest], plus cancellation), run with every full build so neither engine
 * can drift from it unnoticed. Opt-in like the other server tests: -Dclu.serverPython=<python 3.10+>.
 */
class ServerContractTest {
    private val python = System.getProperty("clu.serverPython")

    @Test fun serverFollowsTheSameAgentContract() {
        assumeTrue(python != null, "set -Dclu.serverPython to run the server agent's contract tests")
        val server = File("../server")
        val p = ProcessBuilder(python, "-m", "unittest", "-v", "tests/test_contract.py").directory(server).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "server contract tests finished")
        println(out)
        assertEquals(0, p.exitValue(), out.takeLast(3000))
        assertTrue(Regex("Ran (\\d+) tests").find(out)!!.groupValues[1].toInt() >= 6 && out.trimEnd().endsWith("OK"), out.takeLast(800))
    }
}
