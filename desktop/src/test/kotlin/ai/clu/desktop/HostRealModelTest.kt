package ai.clu.desktop

import ai.clu.core.config.Settings
import ai.clu.core.util.CluJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*

/**
 * The host with a real GGUF through CLU's llama.cpp JNI library: a turn over the local API, then unloading through
 * the API, with the process's memory recorded at each step. Opt-in: -Dclu.desktopRealModel=<file.gguf>.
 */
class HostRealModelTest {
    @Test fun aRealModelAnswersOverTheApiAndMemoryIsReturnedOnUnload() {
        val model = System.getProperty("clu.desktopRealModel")
        assumeTrue(model != null && File(model).isFile, "no model given")
        val dir = Files.createTempDirectory("clu-real").toFile()
        val host = Host.start(DesktopPaths(File(dir, "home")), HostConfig(governorIntervalSeconds = 600), port = 0, log = {})
        try {
            val entry = host.core.models.register(File(model!!))
            host.core.settings.set(Settings.MODEL_ACTIVE, entry.id)
            host.core.settings.set(Settings.CONTEXT_LENGTH, 2048)
            host.core.settings.set(Settings.MAX_TOKENS, 48)
            host.core.settings.set(Settings.SEARCH_AUTO, "off")
            val http = HttpClient.newHttpClient()
            fun call(path: String, body: String? = null) = http.send(HttpRequest.newBuilder(URI.create(host.url + path)).header("Authorization", "Bearer ${host.token}")
                .method(if (body != null) "POST" else "GET", if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).let { CluJson.parseToJsonElement(it.body()) }
            val before = SystemMemoryProbe.processBytes()
            val id = call("/v1/conversations", "{}").jsonObject["id"]!!.jsonPrimitive.content
            val t0 = System.nanoTime()
            val reply = call("/v1/conversations/$id/turns", """{"text":"Say hello in one short sentence."}""").jsonObject
            val turnMs = (System.nanoTime() - t0) / 1_000_000
            val loaded = SystemMemoryProbe.processBytes()
            val st = call("/v1/status").jsonObject["model"]!!.jsonObject
            assertEquals("READY", st["status"]!!.jsonPrimitive.content, st.toString())
            assertTrue(reply["text"]!!.jsonPrimitive.content.isNotBlank(), reply.toString())
            assertEquals("", reply["error"]!!.jsonPrimitive.content, reply.toString())
            val unloaded = call("/v1/model/unload", "{}").jsonObject["status"]!!.jsonPrimitive.content
            assertEquals("UNLOADED", unloaded)
            System.gc(); Thread.sleep(500)
            val after = SystemMemoryProbe.processBytes()
            println("REAL-HOST model=${entry.name} sizeMB=${entry.sizeBytes / 1_048_576} beforeMB=${before / 1_048_576} loadedMB=${loaded / 1_048_576} " +
                "afterUnloadMB=${after / 1_048_576} turnMs=$turnMs loadMs=${st["loadMs"]} reply=${reply["text"]!!.jsonPrimitive.content.take(120).replace('\n', ' ')}")
            assertTrue(loaded - before > entry.sizeBytes / 2, "the model's memory shows while it is loaded")
            assertTrue(after < loaded - entry.sizeBytes / 2, "unloading gives the model's memory back")
        } finally { host.stop(); dir.deleteRecursively() }
    }
}
