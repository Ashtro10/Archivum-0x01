package ai.clu.core

import ai.clu.core.config.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.*
import org.junit.jupiter.api.Test

/**
 * The qualification runner. Without a model it must still produce a report naming the failure (a device run never
 * crashes the app). With -Dclu.qualifyModel=<gguf> [-Dclu.qualifyOut=<json>] [-Dclu.qualifyRounds=<n>] it measures that
 * model on THIS machine; the result describes this machine only and is never a substitute for the tablet.
 */
class DeviceQualificationTest {
    @Test fun withoutAModelTheReportNamesTheFailureInsteadOfCrashing() = runBlocking {
        val core = newCore()
        val r = DeviceQualification(core, ProcDeviceProbe()).run(DeviceQualification.Config(rounds = 2))
        assertTrue(r["errors"]!!.jsonArray.isNotEmpty(), r.toString())
        assertEquals("0/0", r["summary"]!!.jsonObject["rounds_ok"]!!.jsonPrimitive.content)
        assertNotNull(r["device"]!!.jsonObject["cpus"])
    }

    @Test fun qualifyARealModelOnThisMachine(): Unit = runBlocking {
        val path = System.getProperty("clu.qualifyModel")
        assumeTrue(path != null && File(path).exists(), "no real model given")
        val core = newCore()
        val s = core.settings
        s.set(Settings.THREADS, Runtime.getRuntime().availableProcessors().coerceAtMost(8)); s.set(Settings.CONTEXT_LENGTH, 4096); s.set(Settings.LOAD_ON_START, false)
        s.set(Settings.MODEL_ACTIVE, core.models.register(File(path)).id)
        val rounds = System.getProperty("clu.qualifyRounds", "12").toInt()
        val r = DeviceQualification(core, ProcDeviceProbe()).run(DeviceQualification.Config(rounds = rounds)) { println("qualify: $it") }
        val text = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), r)
        println(text)
        System.getProperty("clu.qualifyOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(text) }
        assertEquals("$rounds/$rounds", r["summary"]!!.jsonObject["rounds_ok"]!!.jsonPrimitive.content, "errors: ${r["errors"]}")
    }
}
