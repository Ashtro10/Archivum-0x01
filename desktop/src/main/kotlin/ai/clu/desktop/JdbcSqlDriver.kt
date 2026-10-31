package ai.clu.desktop

import ai.clu.core.db.SqlDriver
import ai.clu.core.db.SqlRow
import ai.clu.core.util.CluJson
import kotlinx.serialization.Serializable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * SQLite through JDBC: the same SQL the Android driver and the core tests execute. Write-ahead logging and a busy
 * timeout suit a process that runs for weeks; one connection, serialised (the core already serialises its writes).
 */
class JdbcSqlDriver(path: String) : SqlDriver {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private var depth = 0

    init {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute("PRAGMA synchronous=NORMAL")
        }
    }

    private fun bind(st: java.sql.PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.setNull(i + 1, java.sql.Types.NULL)
                is String -> st.setString(i + 1, a)
                is Long -> st.setLong(i + 1, a)
                is Int -> st.setLong(i + 1, a.toLong())
                is Double -> st.setDouble(i + 1, a)
                is Float -> st.setDouble(i + 1, a.toDouble())
                is Boolean -> st.setLong(i + 1, if (a) 1 else 0)
                is ByteArray -> st.setBytes(i + 1, a)
                else -> error("unsupported arg ${a::class}")
            }
        }
    }

    @Synchronized override fun execute(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { st -> bind(st, args); st.execute() }
    }

    @Synchronized override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val row = Row(rs); val out = mutableListOf<T>()
                while (rs.next()) out += map(row)
                out
            }
        }

    @Synchronized override fun <T> transaction(block: () -> T): T {
        if (depth == 0) conn.autoCommit = false
        depth++
        try {
            val r = block()
            depth--
            if (depth == 0) { conn.commit(); conn.autoCommit = true }
            return r
        } catch (t: Throwable) {
            depth--
            if (depth == 0) { conn.rollback(); conn.autoCommit = true }
            throw t
        }
    }

    override var userVersion: Int
        get() = query("PRAGMA user_version") { it.long(0)!!.toInt() }.first()
        set(v) = execute("PRAGMA user_version = $v")

    @Synchronized override fun close() = conn.close()

    private class Row(val rs: ResultSet) : SqlRow {
        override fun string(i: Int): String? = rs.getString(i + 1)
        override fun long(i: Int): Long? = rs.getLong(i + 1).takeUnless { rs.wasNull() }
        override fun double(i: Int): Double? = rs.getDouble(i + 1).takeUnless { rs.wasNull() }
        override fun blob(i: Int): ByteArray? = rs.getBytes(i + 1)
        override fun isNull(i: Int): Boolean { rs.getObject(i + 1); return rs.wasNull() }
    }
}

/** host.json: settings that belong to this PC rather than to CLU (CLU's own settings live in its database). */
@Serializable
data class HostConfig(
    /** The local API's port, on 127.0.0.1 only. */
    val port: Int = 47821,
    /** CLU's memory ceiling in GB (host process including the model). */
    val memoryBudgetGB: Double = 16.0,
    /** Memory always left for the user's own programs (GB). */
    val reserveGB: Double = 2.0,
    /** Unload an idle model when the PC's free memory falls below this (GB). */
    val pressureFreeGB: Double = 1.5,
    val governorIntervalSeconds: Int = 15,
    /** How often background memory work (tidy-up, integrity check, backup) may run; 0 = never. It waits while the PC or the model is busy. */
    val maintenanceMinutes: Int = 20,
    /** When the window is closed CLU keeps running in the notification area (true); false = CLU quits when its window closes. */
    val runInBackground: Boolean = true,
    /** Path to clu_llama.dll / libclu_llama.so; empty = the one installed next to CLU. */
    val nativeLibrary: String = "",
) {
    fun validated(): HostConfig {
        require(port in 1024..65535) { "port must be 1024-65535" }
        require(memoryBudgetGB in 1.0..256.0) { "memoryBudgetGB must be 1-256" }
        require(reserveGB in 0.0..64.0 && pressureFreeGB in 0.0..64.0) { "reserveGB / pressureFreeGB out of range" }
        require(governorIntervalSeconds in 1..600) { "governorIntervalSeconds must be 1-600" }
        require(maintenanceMinutes in 0..1440) { "maintenanceMinutes must be 0-1440" }
        return this
    }

    fun governor() = GovernorConfig((memoryBudgetGB * ResourceGovernor.GB).toLong(), (reserveGB * ResourceGovernor.GB).toLong(),
        (pressureFreeGB * ResourceGovernor.GB).toLong())

    companion object {
        private val pretty = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }

        /** Reads host.json, writing the defaults first if it doesn't exist (so the user can see and edit every setting). */
        fun load(file: File): HostConfig {
            if (!file.exists()) { file.parentFile?.mkdirs(); file.writeText(pretty.encodeToString(serializer(), HostConfig()) + "\n") }
            return CluJson.decodeFromString(serializer(), file.readText()).validated()
        }
    }
}
