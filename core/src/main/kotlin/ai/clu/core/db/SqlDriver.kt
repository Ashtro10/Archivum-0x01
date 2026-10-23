package ai.clu.core.db

/**
 * Minimal SQL abstraction so all persistence logic (and its SQL) lives in :core and is tested on the
 * JVM against real SQLite (sqlite-jdbc). Android supplies an implementation over android.database.sqlite.
 *
 * Supported argument types: null, String, Long, Int, Double, Float, Boolean, ByteArray.
 */
interface SqlDriver {
    fun execute(sql: String, args: List<Any?> = emptyList())
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): List<T>
    fun <T> transaction(block: () -> T): T
    var userVersion: Int
    fun close()
}

interface SqlRow {
    fun string(i: Int): String?
    fun long(i: Int): Long?
    fun double(i: Int): Double?
    fun blob(i: Int): ByteArray?
    fun isNull(i: Int): Boolean
}

fun SqlRow.str(i: Int): String = string(i) ?: ""
fun SqlRow.lng(i: Int): Long = long(i) ?: 0L
fun SqlRow.bool(i: Int): Boolean = (long(i) ?: 0L) != 0L

fun <T> SqlDriver.queryOne(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): T? =
    query(sql, args, map).firstOrNull()

fun SqlDriver.longValue(sql: String, args: List<Any?> = emptyList()): Long =
    queryOne(sql, args) { it.long(0) } ?: 0L
