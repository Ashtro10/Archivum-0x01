package ai.clu.desktop

import ai.clu.desktop.evolution.GitCli
import ai.clu.desktop.evolution.SafeDelete
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Regression for the 2026-11-04 incident: deleting a self-repair workspace followed its symbolic links (toolchain/,
 * native/build) and deleted the owner's real directories. Deletion must remove links, never what they point to.
 */
class SafeDeleteTest {
    private fun outside(): Pair<File, File> {
        val dir = Files.createTempDirectory("owner-toolchain").toFile().also { it.deleteOnExit() }
        val file = File(dir, "apksig.jar").apply { writeText("precious") }
        File(dir, "sub").mkdirs(); File(dir, "sub/libclu_llama.so").writeText("native")
        return dir to file
    }

    @Test fun aTreeWithLinksIsDeletedButTheLinkTargetsSurvive() {
        assumeTrue(runCatching { Files.createSymbolicLink(Files.createTempDirectory("probe").resolve("l"), File("/").toPath()) }.isSuccess)
        val (target, file) = outside()
        val ws = Files.createTempDirectory("ws").toFile()
        File(ws, "native").mkdirs()
        Files.createSymbolicLink(File(ws, "toolchain").toPath(), target.toPath())
        Files.createSymbolicLink(File(ws, "native/build").toPath(), File(target, "sub").toPath())
        Files.createSymbolicLink(File(ws, "jar").toPath(), file.toPath())
        File(ws, "core/src").mkdirs(); File(ws, "core/src/A.kt").writeText("x")
        assertTrue(SafeDelete.tree(ws))
        assertFalse(ws.exists())
        assertEquals("precious", file.readText(), "the linked file survives")
        assertEquals("native", File(target, "sub/libclu_llama.so").readText(), "the linked directory's contents survive")
        // Deleting a link given directly removes only the link.
        val link = Files.createTempDirectory("l").resolve("toolchain"); Files.createSymbolicLink(link, target.toPath())
        assertTrue(SafeDelete.tree(link.toFile())); assertTrue(file.exists())
    }

    @Test fun removingARepairWorktreeNeverTouchesTheLinkedOwnerDirectories() {
        assumeTrue(runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val (target, file) = outside()
        val repo = Files.createTempDirectory("repo").toFile()
        fun git(vararg a: String) = check(ProcessBuilder(listOf("git", *a)).directory(repo).redirectErrorStream(true).start().waitFor() == 0)
        git("init", "-q", "-b", "main"); git("config", "user.name", "T"); git("config", "user.email", "t@example.invalid")
        File(repo, "README").writeText("r"); git("add", "-A"); git("commit", "-q", "-m", "i")
        val g = GitCli(repo); val wt = File(Files.createTempDirectory("wts").toFile(), "w1")
        g.addWorktree(wt, "clu/repair/x", "HEAD")
        Files.createSymbolicLink(File(wt, "toolchain").toPath(), target.toPath()) // as linkIgnoredInputs does
        g.removeWorktree(wt)
        assertFalse(wt.exists())
        assertEquals("precious", file.readText()); assertTrue(File(target, "sub/libclu_llama.so").exists())
    }
}
