package ai.clu.desktop

import ai.clu.core.CluCore
import ai.clu.core.db.Schema
import ai.clu.core.llm.LlmStatus
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class DesktopPlatformTest {
    private val dir = Files.createTempDirectory("clu-desktop").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) }

    private fun perms(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    @Test fun theDataFolderIsPerUserAndCanBeOverridden() {
        assertEquals(File("/tmp/x"), DesktopPaths.resolve(home = "/tmp/x").root)
        assertEquals(File("/tmp/env-home"), DesktopPaths.resolve(env = mapOf("CLU_HOME" to "/tmp/env-home"), home = null).root)
        if (!IS_WINDOWS) assertEquals(File("/data/xdg/clu"), DesktopPaths.resolve(env = mapOf("XDG_DATA_HOME" to "/data/xdg"), home = null).root)
        val p = DesktopPaths(File(dir, "home")).ensure()
        assertTrue(p.files.isDirectory && p.models.isDirectory && p.logs.isDirectory)
        assertEquals("rwx------", perms(p.root), "only the user can enter CLU's folder")
    }

    @Test fun theVaultSealsSecretsWithAKeyOnlyTheUserCanRead() {
        val p = DesktopPaths(File(dir, "v")).ensure()
        val vault = FileKeyVault(p.vaultKey)
        val sealed = vault.protect("brave-key-123".toByteArray())
        assertFalse(String(sealed).contains("brave-key-123"))
        assertEquals("brave-key-123", String(FileKeyVault(p.vaultKey).unprotect(sealed)), "the same key after a restart")
        assertEquals("rw-------", perms(p.vaultKey))
        val secrets = DesktopSecrets(p.secrets, vault, env = mapOf("CLU_SECRET_SEARXNG_URL" to "http://localhost:8080"))
        secrets.put("brave", "brave-key-123")
        assertEquals("brave-key-123", secrets.get("brave")); assertFalse(p.secrets.readText().contains("brave-key-123"))
        assertEquals("http://localhost:8080", secrets.get("searxng.url"), "the environment overrides")
        secrets.put("brave", null); assertNull(secrets.get("brave"))
        assertEquals("rw-------", perms(p.secrets))
    }

    @Test fun theApiTokenIsCreatedOnceAndKeptPrivate() {
        val f = File(dir, "api-token")
        val t = ApiToken.loadOrCreate(f)
        assertTrue(t.length >= 40); assertEquals(t, ApiToken.loadOrCreate(f)); assertEquals("rw-------", perms(f))
    }

    @Test fun hostJsonIsWrittenWithDefaultsAndValidated() {
        val f = File(dir, "host.json")
        val c = HostConfig.load(f)
        assertEquals(47821, c.port); assertEquals(16.0, c.memoryBudgetGB); assertTrue(f.readText().contains("\"memoryBudgetGB\""))
        f.writeText("""{"port": 80}""")
        assertFailsWith<IllegalArgumentException> { HostConfig.load(f) }
    }

    @Test fun theDesktopDatabaseRunsCluInFullWithWal() {
        val p = DesktopPaths(File(dir, "db")).ensure()
        val db = JdbcSqlDriver(p.database.absolutePath)
        assertEquals("wal", db.query("PRAGMA journal_mode") { it.string(0) }.first())
        val gov = ResourceGovernor(HostConfig().governor(), SystemMemoryProbe, { LlmStatus.NO_MODEL }, {})
        val core = CluCore(db, DesktopPlatform(p, gov))
        assertEquals(Schema.currentVersion, db.userVersion, "all migrations ran")
        val c = core.conversations.create("hello"); core.memories.count()
        assertEquals("hello", core.conversations.get(c.id)?.title)
        assertEquals(p.files, core.platform.filesDir())
        db.close()
    }

    @Test fun theNativeLibraryIsFoundNextToTheJars() {
        val gov = ResourceGovernor(HostConfig().governor(), SystemMemoryProbe, { LlmStatus.NO_MODEL }, {})
        assertEquals(File("/opt/x/clu_llama.dll"), DesktopLlmPlatform(gov, "/opt/x/clu_llama.dll").resolveNativeLibrary(), "host.json wins")
        val lib = DesktopLlmPlatform(gov).resolveNativeLibrary().path
        assertTrue(lib.endsWith("clu_llama.so") || lib.endsWith("clu_llama.dll"), lib)
    }

    @Test fun theMemoryProbeReadsRealFigures() {
        val free = SystemMemoryProbe.systemAvailableBytes(); val mine = SystemMemoryProbe.processBytes()
        assertTrue(free in 1..SystemMemoryProbe.systemTotalBytes(), "$free")
        assertTrue(mine > 10 * ResourceGovernor.MB, "$mine")
    }
}
