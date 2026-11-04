package clu.build

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Empties a build working folder without following symbolic links (see docs/INCIDENT_2026-11-04_LINKED_INPUTS_DELETED.md).
 * Only folders inside a `build` directory are accepted; links are removed as links.
 */
fun File.safeClean(): File {
    val p = toPath().toAbsolutePath().normalize()
    require(p.any { it.toString() == "build" }) { "refusing to clean $p: not inside a build folder" }
    if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(p) || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.delete(p)
        else Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE }
        })
    }
    mkdirs(); return this
}
