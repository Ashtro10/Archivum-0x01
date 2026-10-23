package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.config.*
import ai.clu.core.memory.*
import ai.clu.core.sync.PairingHandler
import ai.clu.core.sync.PeerLocator
import ai.clu.core.sync.SyncNode
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Two independent Clu installations (tablet = home, phone = portable) syncing over localhost TCP. */
class SyncHarness {
    val tablet = newCore(platform = TestPlatform().apply { name = "Xiaomi Pad 6" })
    val phone = newCore(platform = TestPlatform().apply { name = "iQOO Neo 9 Pro" })
    val sasSeen = mutableListOf<String>()
    private val accept = PairingHandler { _, _, sas -> synchronized(sasSeen) { sasSeen += sas }; true }
    val tabletNode: SyncNode
    val phoneNode: SyncNode
    val port: Int

    init {
        tablet.settings.set(Settings.DEVICE_ROLE, "home")
        phone.settings.set(Settings.DEVICE_ROLE, "portable")
        tabletNode = tablet.syncNode(accept)
        phoneNode = phone.syncNode(accept)
        port = tabletNode.startServer(0)
    }

    fun pair() = runBlocking { tabletNode.openPairingWindow(); phoneNode.pair("127.0.0.1", port) }
    fun sync() = runBlocking { phoneNode.syncWith("127.0.0.1", port) }
    fun close() { tabletNode.shutdown(); phoneNode.shutdown() }

    fun memory(core: CluCore, title: String, content: String = title) =
        core.memories.save(Memory(category = MemoryCategory.MANUAL, title = title, content = content, source = MemorySource.OWNER_SUPPLIED))
}

class SyncSettingsTest {
    private val h = SyncHarness()
    @AfterEach fun tearDown() = h.close()

    @Test fun role() {
        assertTrue(h.pair().ok)
        assertEquals("home", h.phone.syncEngine.peers().single().role)
        // One entity: the portable device adopted the home device's identity.
        assertEquals(h.tablet.store.entityId, h.phone.store.entityId)
        assertEquals(SettingScope.DEVICE, Settings[Settings.DEVICE_ROLE]!!.scope, "device role never syncs")
        h.phone.settings.set(Settings.DEVICE_NAME, "Pocket")
        assertTrue(h.sync().ok)
        assertEquals("portable", h.phone.settings.string(Settings.DEVICE_ROLE))
        assertEquals("home", h.tablet.settings.string(Settings.DEVICE_ROLE))
    }

    @Test fun enabled() {
        h.pair()
        h.phone.settings.set(Settings.SYNC_ENABLED, false)
        val r = h.sync()
        assertFalse(r.ok); assertTrue(r.error!!.contains("turned off"))
        h.phone.settings.set(Settings.SYNC_ENABLED, true)
        h.tablet.settings.set(Settings.SYNC_ENABLED, false)
        assertFalse(h.sync().ok, "the tablet refuses sessions when its sync is off")
    }

    @Test fun serve() {
        assertTrue(ServicePolicy.shouldServeSync(h.tablet.settings))
        h.tablet.settings.set(Settings.SYNC_SERVE, false)
        assertFalse(ServicePolicy.shouldServeSync(h.tablet.settings))
    }

    @Test fun discovery() {
        val loc = PeerLocator(h.phone.settings, h.phone.syncEngine, discovered = { listOf("192.168.43.5") }, gateway = { "192.168.43.1" })
        assertEquals(listOf("192.168.43.5", "192.168.43.1"), loc.candidates())
        h.phone.settings.set(Settings.SYNC_ADDRESS, "10.0.0.7:5000")
        assertEquals("10.0.0.7:5000", loc.candidates().first())
        h.phone.settings.set(Settings.SYNC_DISCOVERY, "manual")
        assertEquals(listOf("10.0.0.7:5000"), loc.candidates())
        assertIs<SettingResult.Invalid>(h.phone.settings.set(Settings.SYNC_ADDRESS, "bad address!"))
    }

    @Test fun port() {
        assertEquals("10.0.0.7" to 5000, SyncNode.splitHostPort("10.0.0.7:5000", 47823))
        assertEquals("10.0.0.7" to 47823, SyncNode.splitHostPort("10.0.0.7", h.phone.settings.int(Settings.SYNC_PORT)))
        h.phone.settings.set(Settings.SYNC_PORT, 50000)
        assertEquals(50000, SyncNode.splitHostPort("10.0.0.7", h.phone.settings.int(Settings.SYNC_PORT)).second)
    }

    @Test fun interval() {
        assertEquals(5 * 60_000L, ServicePolicy.syncIntervalMs(h.phone.settings))
        h.phone.settings.set(Settings.SYNC_INTERVAL, 0); assertNull(ServicePolicy.syncIntervalMs(h.phone.settings))
    }

    @Test fun scopes() {
        h.pair()
        h.memory(h.phone, "Phone memory")
        h.phone.settings.set(Settings.HUMOR, "frequent")
        h.phone.settings.set(Settings.SYNC_MEMORIES, false)
        assertTrue(h.sync().ok)
        assertTrue(h.tablet.memories.list().isEmpty(), "memories excluded by setting")
        assertEquals("frequent", h.tablet.settings.string(Settings.HUMOR), "shared settings synced")
        h.phone.settings.set(Settings.SYNC_MEMORIES, true)
        // Re-enable: rows logged earlier were already acknowledged, so touch the memory to resend it.
        h.phone.memories.list().forEach { h.phone.memories.save(it) }
        assertTrue(h.sync().ok)
        assertEquals("Phone memory", h.tablet.memories.list().single().title)
        // Device-specific settings never sync.
        h.phone.settings.set(Settings.CONTEXT_LENGTH, 1024)
        h.sync()
        assertEquals(4096, h.tablet.settings.int(Settings.CONTEXT_LENGTH))
    }

    @Test fun conflicts() {
        h.pair()
        val m = h.memory(h.tablet, "Shared note", "original")
        h.sync()
        assertEquals("original", h.phone.memories.get(m.id)!!.content)
        // Concurrent edits on both devices.
        h.phone.memories.save(h.phone.memories.get(m.id)!!.copy(content = "phone edit"))
        (h.tablet.clock as ai.clu.core.util.ManualClock).advance(5_000)
        h.tablet.memories.save(h.tablet.memories.get(m.id)!!.copy(content = "tablet edit"))
        val r = h.sync()
        assertTrue(r.conflicts >= 1)
        assertEquals(h.tablet.memories.get(m.id)!!.content, h.phone.memories.get(m.id)!!.content, "devices converge")
        assertEquals("tablet edit", h.phone.memories.get(m.id)!!.content, "latest edit wins")

        // Home-wins policy: the phone edits later, but the tablet's version is kept.
        h.phone.settings.set(Settings.CONFLICT_POLICY, "home"); h.sync()
        h.tablet.memories.save(h.tablet.memories.get(m.id)!!.copy(content = "tablet v2"))
        (h.phone.clock as ai.clu.core.util.ManualClock).advance(60_000)
        h.phone.memories.save(h.phone.memories.get(m.id)!!.copy(content = "phone v2 (later)"))
        h.sync(); h.sync()
        assertEquals("tablet v2", h.tablet.memories.get(m.id)!!.content)
        assertEquals("tablet v2", h.phone.memories.get(m.id)!!.content)

        // Keep-both: a conflict copy is created instead of losing an edit.
        h.phone.settings.set(Settings.CONFLICT_POLICY, "keep_both"); h.sync()
        h.tablet.memories.save(h.tablet.memories.get(m.id)!!.copy(content = "tablet v3"))
        h.phone.memories.save(h.phone.memories.get(m.id)!!.copy(content = "phone v3"))
        h.sync(); h.sync()
        val titles = h.tablet.memories.list().map { it.content }.toSet()
        assertTrue("tablet v3" in titles && "phone v3" in titles, "both versions kept: $titles")
    }

    @Test fun remoteInference(): Unit = runBlocking {
        h.pair()
        val model = java.io.File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
        h.tablet.settings.set(Settings.MODEL_ACTIVE, h.tablet.models.register(model).id)
        val remote = h.phoneNode.remoteEngine("127.0.0.1", h.port)
        assertTrue(remote.probe(), "tablet reports a usable model")
        h.phone.remoteEngineProvider = { remote }
        // Policy "fallback": phone has no local model -> uses the tablet.
        assertEquals("home_device", h.phone.inference.select()!!.source)
        val chunks = mutableListOf<String>()
        val r = remote.generate(listOf(ai.clu.core.llm.ChatMessage("user", "hello")), ai.clu.core.llm.GenParams(maxTokens = 6, temperature = 0f)) { chunks += it }
        assertTrue(r.stats.generatedTokens in 1..6)
        assertEquals(r.text, chunks.joinToString(""))
        h.phone.settings.set(Settings.REMOTE_INFERENCE, "local")
        assertNull(h.phone.inference.select(), "local-only never uses the tablet")
        remote.close()
    }
}
