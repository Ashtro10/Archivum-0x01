package ai.clu.desktop

import ai.clu.core.CluPlatform
import ai.clu.core.llm.LlamaBridge
import ai.clu.core.llm.LlmPlatform
import ai.clu.core.net.JvmHttp
import ai.clu.core.sync.KeyVault
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.util.CluJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val OS = System.getProperty("os.name").orEmpty().lowercase()
internal val IS_WINDOWS = OS.startsWith("windows")
private val IS_MAC = OS.startsWith("mac")

/** Where CLU keeps its data on this computer. One folder, owned by the user, nothing elsewhere. */
class DesktopPaths(val root: File) {
    val database = File(root, "clu.db")
    val files = File(root, "files")
    val models = File(root, "models")
    val logs = File(root, "logs")
    val config = File(root, "host.json")
    val token = File(root, "api-token")
    val vaultKey = File(root, "vault.key")
    val secrets = File(root, "secrets.json")
    val backups = File(root, "backups")

    fun ensure(): DesktopPaths {
        listOf(root, files, models, logs, backups).forEach { it.mkdirs() }
        OwnerOnly.restrict(root)
        return this
    }

    companion object {
        /**
         * `-Dclu.home` or `CLU_HOME` if set; otherwise the platform's per-user application-data folder:
         * Windows `%LOCALAPPDATA%\CLU`, macOS `~/Library/Application Support/CLU`, Linux `$XDG_DATA_HOME/clu`.
         */
        fun resolve(env: Map<String, String> = System.getenv(), home: String? = System.getProperty("clu.home")): DesktopPaths {
            val user = System.getProperty("user.home")
            val dir = home?.takeIf { it.isNotBlank() } ?: env["CLU_HOME"]?.takeIf { it.isNotBlank() } ?: when {
                IS_WINDOWS -> (env["LOCALAPPDATA"] ?: "$user\\AppData\\Local") + "\\CLU"
                IS_MAC -> "$user/Library/Application Support/CLU"
                else -> (env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() } ?: "$user/.local/share") + "/clu"
            }
            return DesktopPaths(File(dir))
        }
    }
}

/** Restricts a file or folder to the current user (POSIX permissions, or a Windows ACL with only the owner). */
object OwnerOnly {
    fun restrict(f: File): Boolean = runCatching {
        val p = f.toPath()
        val posix = Files.getFileAttributeView(p, java.nio.file.attribute.PosixFileAttributeView::class.java)
        if (posix != null) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(if (f.isDirectory) "rwx------" else "rw-------")); true
        } else {
            val acl = Files.getFileAttributeView(p, AclFileAttributeView::class.java) ?: return false
            val owner = Files.getOwner(p)
            acl.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(AclEntryPermission.entries.toSet()).build())
            true
        }
    }.getOrDefault(false)

    /** Writes [bytes] to [f] so that only the owner can ever read it (created restricted, then filled). */
    fun write(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        if (!f.exists()) f.createNewFile()
        restrict(f)
        f.writeBytes(bytes)
    }
}

/**
 * AES-256-GCM with a key in a file only the user can read (the same sealed format as the Android Keystore vault:
 * 12-byte IV + ciphertext). Windows DPAPI binding would bind the key to the Windows login as well; it needs native code
 * and is planned with the permission broker (M6).
 */
class FileKeyVault(private val keyFile: File) : KeyVault {
    private val key: SecretKeySpec by lazy {
        if (!keyFile.exists()) OwnerOnly.write(keyFile, ByteArray(32).also { SecureRandom().nextBytes(it) })
        SecretKeySpec(keyFile.readBytes().also { require(it.size == 32) { "vault key file is damaged" } }, "AES")
    }

    override fun protect(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(plain)
    }

    override fun unprotect(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return c.doFinal(sealed.copyOfRange(12, sealed.size))
    }
}

/** API keys and similar, sealed with the vault in one file; `CLU_SECRET_<NAME>` in the environment overrides. */
class DesktopSecrets(private val file: File, private val vault: KeyVault, private val env: Map<String, String> = System.getenv()) {
    private val map = MapSerializer(String.serializer(), String.serializer())
    private fun all(): Map<String, String> = if (!file.exists()) emptyMap() else runCatching { CluJson.decodeFromString(map, file.readText()) }.getOrDefault(emptyMap())
    fun get(name: String): String? = env["CLU_SECRET_" + name.uppercase().replace(Regex("[^A-Z0-9]"), "_")]
        ?: all()[name]?.let { runCatching { String(vault.unprotect(Base64.getDecoder().decode(it))) }.getOrNull() }
    fun put(name: String, value: String?) {
        val next = all().toMutableMap()
        if (value.isNullOrEmpty()) next.remove(name) else next[name] = Base64.getEncoder().encodeToString(vault.protect(value.toByteArray()))
        OwnerOnly.write(file, CluJson.encodeToString(map, next).toByteArray())
    }
}

/** Memory as the operating system reports it. Linux: /proc; Windows/macOS: the JDK's OS bean. */
interface MemoryProbe {
    /** Physical memory other programs could get right now. */
    fun systemAvailableBytes(): Long
    fun systemTotalBytes(): Long
    /** This process's memory: resident set on Linux, private (committed) bytes on Windows. Includes the model. */
    fun processBytes(): Long
}

object SystemMemoryProbe : MemoryProbe {
    private val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    private val linux = File("/proc/meminfo").canRead()

    private fun procKb(file: String, key: String): Long? = runCatching {
        File(file).readLines().firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong()?.times(1024)
    }.getOrNull()

    override fun systemAvailableBytes(): Long =
        (if (linux) procKb("/proc/meminfo", "MemAvailable") else null) ?: bean?.freeMemorySize ?: Long.MAX_VALUE
    override fun systemTotalBytes(): Long = bean?.totalMemorySize ?: Runtime.getRuntime().maxMemory()
    // On Windows the JDK reports the process's private bytes as "committed virtual memory" (PROCESS_MEMORY_COUNTERS.PagefileUsage).
    override fun processBytes(): Long =
        (if (linux) procKb("/proc/self/status", "VmRSS") else null) ?: bean?.committedVirtualMemorySize ?: Runtime.getRuntime().totalMemory()
}

/**
 * llama.cpp on the desktop: the JNI library from lib/native/<os>-x64 next to the jars (or `-Dclu.nativeLib`), threads
 * from the core settings (auto = half the logical processors), and memory as the [ResourceGovernor] allows.
 */
class DesktopLlmPlatform(private val governor: ResourceGovernor, private val nativeLibrary: String = "", private val wantGpu: () -> Boolean = { false }) : LlmPlatform {
    /** Which build of the engine this process loaded ("cpu" or "vulkan"); a process loads one native library for its whole life. */
    @Volatile var loadedBuild: String = ""; private set

    override fun loadNativeLibrary() {
        val vk = vulkanLibrary()
        val useVulkan = wantGpu() && vk != null
        LlamaBridge.load((if (useVulkan) vk!! else resolveNativeLibrary()).absolutePath)
        loadedBuild = if (useVulkan) "vulkan" else "cpu"
    }

    /**
     * The optional GPU build of the engine (native/windows-x64-vulkan/clu_llama.dll next to the CPU one). It is used only if
     * the owner asked for the GPU, the file exists, and Windows has a Vulkan loader (installed with the graphics driver):
     * otherwise the CPU build is loaded and nothing about the GPU is attempted.
     */
    fun vulkanLibrary(): File? {
        if (!IS_WINDOWS) return null
        val cpu = resolveNativeLibrary()
        val vk = File(cpu.parentFile?.parentFile, "windows-x64-vulkan/clu_llama.dll")
        val loader = File((System.getenv("SystemRoot") ?: "C:\\Windows") + "\\System32\\vulkan-1.dll")
        return vk.takeIf { it.isFile && loader.isFile }
    }

    fun resolveNativeLibrary(): File {
        listOf(nativeLibrary, System.getProperty("clu.nativeLib").orEmpty()).firstOrNull { it.isNotBlank() }?.let { return File(it) }
        val name = if (IS_WINDOWS) "windows-x64/clu_llama.dll" else if (IS_MAC) "macos/libclu_llama.dylib" else "linux-x64/libclu_llama.so"
        val jar = runCatching { File(DesktopLlmPlatform::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
        return File(jar?.parentFile ?: File("."), "native/$name")
    }

    override fun autoThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)
    override fun thermalSevere(): Boolean = false
    /** A GPU load may be attempted; the engine reports what it really used, and falls back to the CPU if the GPU can't be initialised. */
    override val gpuAvailable: Boolean get() = true
    /** The PC is the owner's: nothing is loaded ahead of time and no warm-up generation runs; the first request loads the model. */
    override val loadOnDemand: Boolean get() = true

    /**
     * The core's load check allows a model up to 1.35× the reported figure (slack for memory-mapped weights on phones).
     * On the PC the budget is a hard ceiling, so the figure is divided by that factor here.
     */
    override fun availableMemoryBytes(): Long = (governor.availableForModel() / CORE_LOAD_SLACK).toLong()

    companion object { const val CORE_LOAD_SLACK = 1.35 }
}

class DesktopPlatform(
    val paths: DesktopPaths,
    governor: ResourceGovernor,
    nativeLibrary: String = "",
    override val http: HttpFetcher = JvmHttp(),
) : CluPlatform {
    /** Set by the host once the core exists: whether the owner asked for GPU acceleration (decides which engine build is loaded at first use). */
    @Volatile var wantGpu: () -> Boolean = { false }
    override val llm = DesktopLlmPlatform(governor, nativeLibrary) { wantGpu() }
    override val keyVault: KeyVault = FileKeyVault(paths.vaultKey)
    val secrets = DesktopSecrets(paths.secrets, keyVault)

    override fun isOnline(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().any { n ->
            n.isUp && !n.isLoopback && n.inetAddresses.asSequence().any { !it.isLinkLocalAddress && !it.isLoopbackAddress }
        }
    }.getOrDefault(false)

    override fun deviceName(): String = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("PC")
    override fun secret(name: String): String? = secrets.get(name)
    override fun filesDir(): File = paths.files
}
