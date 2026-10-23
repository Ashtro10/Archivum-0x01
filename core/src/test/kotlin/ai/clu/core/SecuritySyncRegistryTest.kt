package ai.clu.core

import ai.clu.core.config.*
import ai.clu.core.llm.GgufReader
import ai.clu.core.memory.*
import ai.clu.core.security.CredentialStore
import ai.clu.core.settings.SyncHarness
import ai.clu.core.sync.*
import ai.clu.core.util.ManualClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class CredentialTest {
    private val core = newCore()
    private val creds = core.credentials

    @Test fun pinIsHashedNotStoredInPlaintext() {
        creds.set(CredentialStore.Kind.PIN, "482916")
        val raw = core.db.query("SELECT hash, salt FROM credentials") { it.blob(0)!! + it.blob(1)!! }.single()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("482916"))
        assertEquals(CredentialStore.VerifyResult.Ok, creds.verify(CredentialStore.Kind.PIN, "482916"))
        assertEquals(CredentialStore.VerifyResult.Wrong, creds.verify(CredentialStore.Kind.PIN, "000000"))
        assertFailsWith<IllegalArgumentException> { creds.set(CredentialStore.Kind.PIN, "12") }
    }

    @Test fun lockoutIsTemporaryNeverPermanent() {
        val clock = core.clock as ManualClock
        creds.set(CredentialStore.Kind.PIN, "482916")
        repeat(5) { creds.verify(CredentialStore.Kind.PIN, "111111") }
        assertIs<CredentialStore.VerifyResult.LockedOut>(creds.verify(CredentialStore.Kind.PIN, "482916"))
        clock.advance(31_000)
        assertEquals(CredentialStore.VerifyResult.Ok, creds.verify(CredentialStore.Kind.PIN, "482916"))
        repeat(20) { creds.verify(CredentialStore.Kind.PIN, "111111"); clock.advance(16 * 60_000) }
        clock.advance(16 * 60_000)
        assertEquals(CredentialStore.VerifyResult.Ok, creds.verify(CredentialStore.Kind.PIN, "482916"), "delay is capped at 15 minutes")
    }

    @Test fun securityPhraseFallbackIsNormalised() {
        creds.set(CredentialStore.Kind.PHRASE, "  Ultron  Never  Sleeps ")
        assertEquals(CredentialStore.VerifyResult.Ok, creds.verify(CredentialStore.Kind.PHRASE, "ultron never sleeps"))
    }
}

class SyncRobustnessTest {
    private val h = SyncHarness()
    @AfterEach fun tearDown() = h.close()

    @Test fun pairingShowsSameCodeOnBothDevices() {
        assertTrue(h.pair().ok)
        assertEquals(2, h.sasSeen.size); assertEquals(h.sasSeen[0], h.sasSeen[1]); assertTrue(h.sasSeen[0].matches(Regex("\\d{6}")))
    }

    @Test fun rejectedCodeMeansNoPairing() {
        h.tabletNode.pairing = PairingHandler { _, _, _ -> false }
        assertFalse(h.pair().ok)
        assertTrue(h.tablet.syncEngine.peers().isEmpty()); assertTrue(h.phone.syncEngine.peers().isEmpty())
    }

    @Test fun pairingRequiresOpenWindow() = runBlocking {
        assertFalse(h.phoneNode.pair("127.0.0.1", h.port).ok, "tablet must explicitly open pairing")
    }

    @Test fun unpairedDeviceCannotSync() {
        val stranger = newCore(platform = TestPlatform().apply { name = "Stranger" })
        val node = stranger.syncNode { _, _, _ -> true }
        val r = runBlocking { node.syncWith("127.0.0.1", h.port) }
        assertFalse(r.ok); assertTrue(r.error!!.contains("pair first"))
        node.shutdown()
    }

    @Test fun bidirectionalSyncIsIdempotent() {
        h.pair()
        h.memory(h.phone, "From phone")
        h.memory(h.tablet, "From tablet")
        val c = h.phone.conversations.create(); h.phone.conversations.addMessage(c.id, ai.clu.core.conversation.Role.USER, "hi from phone")
        assertTrue(h.sync().ok)
        assertEquals(setOf("From phone", "From tablet"), h.tablet.memories.list().map { it.title }.toSet())
        assertEquals(setOf("From phone", "From tablet"), h.phone.memories.list().map { it.title }.toSet())
        assertEquals("hi from phone", h.tablet.conversations.messages(c.id).single().content)
        // Re-sending everything creates no duplicates.
        h.phone.db.execute("UPDATE sync_peers SET acked_seq = 0")
        assertTrue(h.sync().ok); assertTrue(h.sync().ok)
        assertEquals(2, h.tablet.memories.count()); assertEquals(1, h.tablet.conversations.messages(c.id).size)
    }

    @Test fun deletionsPropagate() {
        h.pair()
        val m = h.memory(h.phone, "Temporary secret")
        h.sync()
        h.phone.memories.delete(m.id)
        h.sync()
        assertTrue(h.tablet.memories.get(m.id)!!.deleted)
        assertTrue(h.tablet.memories.search("secret").isEmpty())
    }

    @Test fun interruptedSyncResumesWithoutLoss() {
        h.pair()
        repeat(450) { h.memory(h.phone, "Note $it") }
        // Simulate interruption: the tablet goes away mid-way, then comes back.
        val first = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(60_000) {
                val t = Thread { Thread.sleep(150); h.tabletNode.stopServer() }; t.start()
                h.phoneNode.syncWith("127.0.0.1", h.port)
            }
        }
        val partial = h.tablet.memories.count()
        h.tabletNode.startServer(h.port)
        assertTrue(h.sync().ok)
        assertEquals(450, h.tablet.memories.count(), "all records arrive after resuming (partial after interruption: $partial, first=$first)")
        assertTrue(h.phone.syncEngine.peers().single().ackedSeq > 0)
    }

    @Test fun phoneWorksWithoutTablet() = runBlocking {
        h.pair()
        h.tabletNode.stopServer()
        val r = h.phoneNode.syncWith("127.0.0.1", h.port)
        assertFalse(r.ok)
        // Local operation continues: memory writes and agent replies still work.
        h.memory(h.phone, "Offline note")
        h.phone.useEngine(null)
        val conv = h.phone.conversations.create()
        var reply = ""
        h.phone.agent.handle(ai.clu.core.agent.TurnRequest(conv.id, "what time is it", ai.clu.core.security.Actor.ownerInApp())) { if (it is ai.clu.core.agent.AgentEvent.Done) reply = it.text }
        assertTrue(reply.startsWith("It is"))
        assertEquals(1, h.phone.memories.count())
    }

    @Test fun tamperedFramesAreRejected() {
        val key = ByteArray(32) { 1 }
        val buf = ByteArrayOutputStream()
        val tx = SecureChannel(ByteArrayInputStream(ByteArray(0)), buf, key, key)
        tx.send("hello".toByteArray())
        val bytes = buf.toByteArray(); bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        val rx = SecureChannel(ByteArrayInputStream(bytes), ByteArrayOutputStream(), key, key)
        assertFailsWith<ChannelException> { rx.receive() }
        // Replay of a valid frame is rejected.
        val buf2 = ByteArrayOutputStream()
        SecureChannel(ByteArrayInputStream(ByteArray(0)), buf2, key, key).send("x".toByteArray())
        val one = buf2.toByteArray()
        val rx2 = SecureChannel(ByteArrayInputStream(one + one), ByteArrayOutputStream(), key, key)
        assertContentEquals("x".toByteArray(), rx2.receive())
        assertFailsWith<ChannelException> { rx2.receive() }
    }
}

class RegistryTest {
    @Test fun everySettingValidatesDefaultsAndRejectsWrongTypes() {
        val core = newCore()
        for (d in Settings.all) {
            assertNull(d.type.validate(d.default), d.key)
            val wrong = when (d.type) {
                is SettingType.Bool -> JsonPrimitive("yes")
                is SettingType.IntRange -> JsonPrimitive((d.type as SettingType.IntRange).max + 1)
                is SettingType.FloatRange -> JsonPrimitive((d.type as SettingType.FloatRange).max + 1)
                is SettingType.Choice -> JsonPrimitive("__nope__")
                is SettingType.Text -> JsonPrimitive("x".repeat((d.type as SettingType.Text).maxLength + 1))
                SettingType.TimeOfDay -> JsonPrimitive("99:99")
                is SettingType.TextList -> JsonPrimitive(3)
                is SettingType.Structured -> JsonPrimitive(3)
            }
            assertIs<SettingResult.Invalid>(core.settings.set(d.key, wrong, verified = true), "invalid value accepted for ${d.key}")
        }
        assertEquals(SettingResult.UnknownSetting, core.settings.set("does.not.exist", true))
    }

    @Test fun everySettingPersistsAcrossRestart() {
        val db = tempDb()
        val core = newCore(db)
        val changed = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        for (d in Settings.all) {
            val v = when (val t = d.type) {
                is SettingType.Bool -> JsonPrimitive(!(d.default as JsonPrimitive).content.toBoolean())
                is SettingType.IntRange -> JsonPrimitive(if ((d.default as JsonPrimitive).content.toInt() == t.min) t.max else t.min)
                is SettingType.FloatRange -> JsonPrimitive(if ((d.default as JsonPrimitive).content.toDouble() == t.min) t.max else t.min)
                is SettingType.Choice -> JsonPrimitive(t.values.first { it != (d.default as JsonPrimitive).content })
                is SettingType.Text -> if (t.pattern != null) continue else JsonPrimitive("changed")
                SettingType.TimeOfDay -> JsonPrimitive("12:34")
                is SettingType.TextList -> kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("changed")))
                is SettingType.Structured -> continue
            }
            if (d.key == Settings.MODEL_ACTIVE || d.key == Settings.ACTIVE_PERSONA) continue
            assertEquals(SettingResult.Ok, core.settings.set(d.key, v, verified = true), d.key)
            changed[d.key] = v
        }
        val again = newCore(JdbcDriver(db.path))
        for ((k, v) in changed) assertEquals(v, again.settings.raw(k), "$k did not persist")
        assertTrue(changed.size > 100)
    }

    @Test fun snapshotsRestoreKnownGoodConfiguration() {
        val core = newCore()
        val s = core.settings
        s.set(Settings.HUMOR, "none")
        val good = s.snapshot("Working", knownGood = true)
        s.set(Settings.HUMOR, "frequent"); s.set(Settings.TEMPERATURE, 1.4)
        assertTrue(s.restoreKnownGood().isEmpty())
        assertEquals("none", s.string(Settings.HUMOR)); assertEquals(0.7, s.double(Settings.TEMPERATURE))
        assertTrue(s.snapshots().any { it.id == good.id && it.knownGood })
        assertTrue(s.importJson("""{"persona.humor":"martian"}""").single().contains("persona.humor"))
    }

    /** The Settings Implementation Register references a test for every setting; make sure each test exists. */
    @Test fun everySettingHasAnExistingVerificationTest() {
        newCore() // registers dynamic tool settings
        val missing = Settings.all.filter { d ->
            val (cls, method) = d.verifiedBy.split("#")
            val k = runCatching { Class.forName("ai.clu.core.settings.$cls") }.getOrNull() ?: return@filter true
            k.methods.none { it.name == method }
        }.map { "${it.key} -> ${it.verifiedBy}" }
        assertTrue(missing.isEmpty(), "missing verification tests: $missing")
    }

    @Test fun ggufReaderReportsMetadata() {
        val f = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
        val info = GgufReader.read(f)
        assertEquals(3, info.version); assertEquals("llama", info.architecture); assertEquals("clu-test-tiny-random", info.name)
        assertEquals(2, info.blockCount); assertEquals(2048, info.contextLength); assertTrue(info.hasChatTemplate); assertEquals("F32", info.quantization)
    }
}
