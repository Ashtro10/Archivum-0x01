package ai.clu.desktop.evolution

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes a directory tree WITHOUT following symbolic links: a link is removed as a link, never entered.
 *
 * Self-repair workspaces link large ignored inputs (`toolchain/`, `native/build`) from the owner's tree. Kotlin's
 * `File.deleteRecursively()` follows such links into directories and deleted the owner's real toolchain and native
 * build in a benchmark run (2026-11-04). Every recursive delete in self-repair goes through this function instead.
 */
object SafeDelete {
    fun tree(root: File): Boolean {
        val p = root.toPath()
        if (Files.isSymbolicLink(p)) { Files.deleteIfExists(p); return true } // the link itself, not its target
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return true
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            // Without FOLLOW_LINKS, a symbolic link (to a file or a directory) arrives here as a file: delete the link only.
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE }
        })
        return !Files.exists(p, LinkOption.NOFOLLOW_LINKS)
    }
}
