package ai.clu.core.util

import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.UUID

val CluJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun newId(): String = UUID.randomUUID().toString()

/** Abstract time source so tests can control time. */
interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

class ManualClock(var time: Long = 1_700_000_000_000L) : Clock {
    override fun now(): Long = time
    fun advance(ms: Long) { time += ms }
}

/**
 * Hybrid logical clock (physical ms + logical counter + device id). Encoded so that lexical order equals
 * causal/time order: "%015d-%05d-deviceId".
 */
class Hlc(private val clock: Clock, val deviceId: String) {
    private var lastMs = 0L
    private var counter = 0

    @Synchronized
    fun now(): String {
        val pt = clock.now()
        if (pt > lastMs) { lastMs = pt; counter = 0 } else counter++
        return encode(lastMs, counter, deviceId)
    }

    /** Merge a remote timestamp so subsequent local stamps sort after it. */
    @Synchronized
    fun observe(remote: String) {
        val (ms, c, _) = decode(remote) ?: return
        val pt = clock.now()
        when {
            pt > lastMs && pt > ms -> { lastMs = pt; counter = 0 }
            ms > lastMs -> { lastMs = ms; counter = c + 1 }
            ms == lastMs -> counter = maxOf(counter, c) + 1
            else -> counter++
        }
    }

    companion object {
        fun encode(ms: Long, counter: Int, device: String) = "%015d-%05d-%s".format(ms, counter, device)
        fun decode(s: String): Triple<Long, Int, String>? {
            val parts = s.split("-", limit = 3)
            if (parts.size < 3) return null
            return Triple(parts[0].toLongOrNull() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2])
        }
    }
}

private val secureRandom = SecureRandom()
fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

/** Bounded in-memory ring for recent items (logs, traces). */
class Ring<T>(private val capacity: Int) {
    private val items = ArrayDeque<T>()
    @Synchronized fun add(item: T) { items.addLast(item); while (items.size > capacity) items.removeFirst() }
    @Synchronized fun snapshot(): List<T> = items.toList()
    @Synchronized fun clear() = items.clear()
}
