package ai.clu.core.util

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Recursive deletion that can only remove what it was meant to remove.
 *
 * Kotlin's `File.deleteRecursively()` follows symbolic links into directories. On 2026-11-04 that deleted the owner's
 * real `toolchain/` and `native/build/` through links inside a self-repair workspace
 * (docs/INCIDENT_2026-11-04_LINKED_INPUTS_DELETED.md). Every recursive delete in CLU goes through [tree]:
 *
 * * the target must lie strictly inside one of [allowedRoots] (compared by real, canonical path, so `..` and a
 *   symbolically linked parent cannot redirect it), and must not be or contain a [protected] path;
 * * a symbolic link is deleted as a link, never followed: a link given as the target, links met during the walk
 *   (nested ones, links to links, broken links) are all removed without touching what they point to;
 * * every directory entered must still have a real path inside the target's real path, otherwise the walk stops with
 *   [Refused] (defence in depth against mount points or links swapped in during the walk).
 */
object SafeDelete {
    class Refused(message: String) : IOException(message)

    /** Deletes [target] (file, directory tree or link). Returns true when nothing is left at that path. */
    fun tree(target: File, allowedRoots: Collection<File>, protected: Collection<File> = emptyList()): Boolean {
        val p = target.toPath().toAbsolutePath().normalize()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return true
        if (allowedRoots.isEmpty()) throw Refused("no allowed root given for deleting $p")
        // Where the entry really lives: its parent resolved through any links, plus its own name (the entry itself may be a link).
        val parentReal = p.parent?.let { if (Files.exists(it)) it.toRealPath() else null } ?: throw Refused("cannot resolve the parent of $p")
        val location = parentReal.resolve(p.fileName)
        val roots = allowedRoots.mapNotNull { r -> r.toPath().toAbsolutePath().normalize().let { if (Files.exists(it)) it.toRealPath() else null } }
        if (roots.none { root -> location.startsWith(root) && location != root })
            throw Refused("$location is not inside an allowed folder (${allowedRoots.joinToString()})")
        val guarded = protected.mapNotNull { g -> g.toPath().toAbsolutePath().normalize().let { if (Files.exists(it)) it.toRealPath() else it } }
        guarded.firstOrNull { g -> location == g || g.startsWith(location) || location.startsWith(g) }?.let { throw Refused("$location is or contains a protected folder ($it)") }

        if (Files.isSymbolicLink(p) || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) { Files.deleteIfExists(p); return true }
        val realTarget = p.toRealPath()
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!dir.toRealPath().startsWith(realTarget)) throw Refused("$dir leads outside $realTarget; stopped")
                return FileVisitResult.CONTINUE
            }
            // Without FOLLOW_LINKS a symbolic link (to a file, a directory, or nothing) arrives here: delete the link only.
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                if (Files.isSymbolicLink(file) || !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file) else throw exc
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { if (exc != null) throw exc; Files.deleteIfExists(dir); return FileVisitResult.CONTINUE }
        })
        return !Files.exists(p, LinkOption.NOFOLLOW_LINKS)
    }
}
