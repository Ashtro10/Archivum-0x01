package ai.clu.platform

import ai.clu.core.CluPlatform
import ai.clu.core.llm.LlmPlatform
import ai.clu.core.llm.LlamaBridge
import ai.clu.core.sync.KeyVault
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.tools.HttpResponse
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val CREDENTIAL_HEADER = Regex("(?i)^(authorization|proxy-authorization|cookie|x-subscription-token|x-api-key|api-key)$|token|secret|key")

class AndroidHttp : HttpFetcher {
    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            // A request carrying a credential (API key, token) never follows a redirect: the client would forward the
            // header to whatever host the redirect names. The redirect is returned as-is (reported as an HTTP error).
            if (headers.keys.any { CREDENTIAL_HEADER.containsMatchIn(it) }) instanceFollowRedirects = false
        }

    private fun read(c: HttpURLConnection, maxBytes: Int = 2_000_000): HttpResponse {
        val code = c.responseCode
        val stream = if (code in 200..399) c.inputStream else c.errorStream
        // Bounded read: never holds more than maxBytes of a response in memory, however large the page is.
        val body = stream?.use { s ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
            while (out.size() < maxBytes) { val n = s.read(buf, 0, minOf(buf.size, maxBytes - out.size())); if (n < 0) break; out.write(buf, 0, n) }
            out.toString(Charsets.UTF_8.name())
        } ?: ""
        val h = listOf("content-type", "location", "last-modified").mapNotNull { k -> c.getHeaderField(k)?.let { k to it } }.toMap()
        return HttpResponse(code, body, h)
    }

    override suspend fun resolve(host: String): List<String> = withContext(Dispatchers.IO) { java.net.InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }

    /** Pages are fetched from the validated address only (no second DNS lookup); TLS keeps the platform's verification. */
    private val pinned = ai.clu.core.search.PinnedHttpClient(hostnameVerifier = { host, session ->
        javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session) })

    override suspend fun fetchPage(url: String, headers: Map<String, String>, maxBytes: Int, timeoutMs: Int, address: String) =
        pinned.fetch(url, address, headers, maxBytes, timeoutMs)

    override suspend fun get(url: String, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        val c = open(url, headers); try { read(c) } finally { c.disconnect() }
    }

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        val c = open(url, headers)
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray()) }
            read(c)
        } finally { c.disconnect() }
    }
}

/**
 * Wraps data with an AES-256-GCM key that lives in the Android Keystore (never leaves secure hardware where
 * available). Used for the device's sync identity key and stored API keys.
 */
class KeystoreVault(private val alias: String = "clu-vault") : KeyVault {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    override fun protect(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun unprotect(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return c.doFinal(sealed.copyOfRange(12, sealed.size))
    }
}

/** API keys etc., encrypted with [KeystoreVault] and stored in private preferences (never in plain settings). */
class SecretStore(context: Context, private val vault: KeyVault) {
    private val prefs = context.getSharedPreferences("clu-secrets", Context.MODE_PRIVATE)
    fun get(name: String): String? = prefs.getString(name, null)?.let { runCatching { String(vault.unprotect(Base64.decode(it, Base64.NO_WRAP))) }.getOrNull() }
    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) prefs.edit().remove(name).apply()
        else prefs.edit().putString(name, Base64.encodeToString(vault.protect(value.toByteArray()), Base64.NO_WRAP)).apply()
    }
}

object DeviceInfo {
    /** Cores whose max frequency is >= 70% of the fastest core (performance + prime cores). */
    fun performanceCores(): Int {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        if (freqs.isEmpty()) return (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val top = freqs.max()
        return freqs.count { it >= top * 0.7 }.coerceIn(2, 6)
    }

    fun summary(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "${Build.MANUFACTURER} ${Build.MODEL} (${Build.SOC_MODEL}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "RAM ${mi.totalMem / 1_048_576} MB total / ${mi.availMem / 1_048_576} MB free, ${Runtime.getRuntime().availableProcessors()} cores (${performanceCores()} performance)"
    }

    fun name(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}"
}

class AndroidLlmPlatform(private val context: Context) : LlmPlatform {
    override fun loadNativeLibrary() = LlamaBridge.load()
    override fun autoThreads() = DeviceInfo.performanceCores()
    override fun thermalSevere(): Boolean =
        context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    override fun availableMemoryBytes(): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        // Model weights are memory-mapped; file-backed pages can be reclaimed, so count cached memory as partly available.
        return mi.availMem
    }
}

class AndroidPlatform(private val context: Context, override val keyVault: KeyVault = KeystoreVault()) : CluPlatform {
    override val llm = AndroidLlmPlatform(context)
    override val http = AndroidHttp()
    val secrets = SecretStore(context, keyVault)

    override fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun deviceName() = DeviceInfo.name(context)
    override fun secret(name: String) = secrets.get(name)
}
