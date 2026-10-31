package ai.clu.desktop

import ai.clu.core.db.SqlDriver
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The database file is damaged and CLU will not start on it; [message] says what to do. */
class DatabaseDamagedException(message: String) : Exception(message)

/**
 * Consistent copies of CLU's database, and the way back from them.
 *
 * * A backup is made with SQLite's `VACUUM INTO` (a transactionally consistent snapshot while CLU keeps running),
 *   written under a temporary name and renamed only after it passes an integrity check, so an interrupted backup never
 *   leaves something that looks valid.
 * * Backups are kept in `<data folder>/backups`, the newest [keep] of each kind.
 * * Restoring never deletes anything: the current database is moved aside next to the backups first.
 * * Restoring happens with CLU stopped (`clu-host --restore-backup <file>`), because swapping a database under a
 *   running process is not safe.
 */
object DatabaseBackup {
    data class Backup(val file: File, val createdAt: Long, val sizeBytes: Long, val label: String)
    data class Verification(val ok: Boolean, val integrity: String, val memories: Long, val schemaVersion: Int)

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    fun create(db: SqlDriver, dir: File, now: Long, label: String = "auto", keep: Int = 7): Backup {
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() } // leftovers of an interrupted backup
        val name = "clu-${stamp.format(Instant.ofEpochMilli(now))}-$label.db"
        val target = File(dir, name); val part = File(dir, "$name.part")
        part.delete()
        db.execute("VACUUM INTO '${part.absolutePath.replace("'", "''")}'")
        val v = verify(part)
        if (!v.ok) { part.delete(); throw IllegalStateException("the backup did not pass its integrity check: ${v.integrity}") }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        OwnerOnly.restrict(target)
        prune(dir, label, keep)
        return Backup(target, now, target.length(), label)
    }

    fun list(dir: File): List<Backup> = (dir.listFiles { f -> f.isFile && f.name.startsWith("clu-") && f.name.endsWith(".db") } ?: emptyArray())
        .map { f -> Backup(f, f.lastModified(), f.length(), f.name.removeSuffix(".db").substringAfter('-').substringAfter('-').substringAfter('-', "")) }
        .sortedByDescending { it.file.name }

    private fun prune(dir: File, label: String, keep: Int) =
        list(dir).filter { it.label == label }.drop(keep).forEach { it.file.delete() }

    /** Opens the file read-only and runs SQLite's integrity check; also reports how many memories and which schema it holds. */
    fun verify(file: File): Verification = try {
        val cfg = org.sqlite.SQLiteConfig().apply { setReadOnly(true) }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", cfg.toProperties()).use { c ->
            c.createStatement().use { st ->
                val integrity = st.executeQuery("PRAGMA integrity_check").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }.joinToString("; ").ifBlank { "ok" }
                val version = st.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) }
                val memories = runCatching { st.executeQuery("SELECT COUNT(*) FROM memories WHERE deleted = 0").use { rs -> rs.next(); rs.getLong(1) } }.getOrDefault(0L)
                Verification(integrity == "ok", integrity, memories, version)
            }
        }
    } catch (e: Exception) { Verification(false, "cannot be opened: ${e.message?.lineSequence()?.firstOrNull()}", 0, 0) }

    /** Throws [DatabaseDamagedException] (with the way out) if the live database fails a quick check; a missing file is fine (first start). */
    fun requireHealthy(paths: DesktopPaths) {
        if (!paths.database.exists()) return
        val problem = try {
            val cfg = org.sqlite.SQLiteConfig().apply { setReadOnly(true) }
            DriverManager.getConnection("jdbc:sqlite:${paths.database.absolutePath}", cfg.toProperties()).use { c ->
                c.createStatement().use { st -> st.executeQuery("PRAGMA quick_check").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }.joinToString("; ") }
            }.takeIf { it != "ok" }
        } catch (e: Exception) { "cannot be opened: ${e.message?.lineSequence()?.firstOrNull()}" }
        if (problem == null) return
        val newest = list(paths.backups).firstOrNull { verify(it.file).ok }
        throw DatabaseDamagedException(
            "CLU's database (${paths.database}) did not pass its integrity check ($problem). Nothing was changed.\n" +
                (if (newest != null) "The newest good backup is ${newest.file.name}. To go back to it, with CLU stopped, run:\n  clu-host --restore-backup \"${newest.file}\"\n(the damaged file is kept next to the backups, not deleted)"
                else "There is no usable backup in ${paths.backups}. Keep the damaged file and ask for help rather than deleting it."))
    }

    /** Puts a verified backup in place. The current database (and its journal files) are moved aside, never deleted. Returns the moved-aside file. */
    fun restore(backup: File, paths: DesktopPaths, now: Long): File? {
        val v = verify(backup)
        require(v.ok) { "that backup does not pass its integrity check (${v.integrity}); nothing was changed" }
        paths.backups.mkdirs()
        var aside: File? = null
        if (paths.database.exists()) {
            aside = File(paths.backups, "before-restore-${stamp.format(Instant.ofEpochMilli(now))}.db")
            Files.move(paths.database.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        listOf("-wal", "-shm").forEach { s ->
            val f = File(paths.database.path + s)
            if (f.exists()) Files.move(f.toPath(), File(paths.backups, "before-restore-${stamp.format(Instant.ofEpochMilli(now))}.db$s").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.copy(backup.toPath(), paths.database.toPath(), StandardCopyOption.REPLACE_EXISTING)
        OwnerOnly.restrict(paths.database)
        return aside
    }
}
