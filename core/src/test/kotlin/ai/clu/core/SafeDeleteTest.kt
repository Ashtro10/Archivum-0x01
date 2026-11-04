package ai.clu.core

import ai.clu.core.util.SafeDelete
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlin.test.*

/**
 * Regression for the 2026-11-04 incident (docs/INCIDENT_2026-11-04_LINKED_INPUTS_DELETED.md), with real files: a cleanup
 * that followed links deleted the owner's toolchain, native build and, through a nested link, clang's system headers.
 * Every case here builds external "sentinel" folders and checks their exact contents after the delete.
 */
class SafeDeleteTest {
    private lateinit var base: File
    private lateinit var workspaces: File
    private lateinit var sentinel: File

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    /** Every file under [dir] with its hash (links are not followed). */
    private fun snapshot(dir: File): Map<String, String> = dir.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.associate { it.relativeTo(dir).path to sha(it) }
    private fun link(at: File, to: File) { at.parentFile.mkdirs(); Files.createSymbolicLink(at.toPath(), to.toPath()) }

    @BeforeEach fun setUp() {
        assumeTrue(runCatching { val t = Files.createTempDirectory("probe"); Files.createSymbolicLink(t.resolve("l"), t); true }.getOrDefault(false), "symbolic links unsupported")
        base = Files.createTempDirectory("safedelete").toFile().canonicalFile
        workspaces = File(base, "evolution/workspaces").apply { mkdirs() }
        // The owner's things that must survive: a toolchain, a native build, and a stand-in for a system header folder.
        sentinel = File(base, "owner").apply { mkdirs() }
        File(sentinel, "toolchain/apksig.jar").apply { parentFile.mkdirs(); writeText("precious jar") }
        File(sentinel, "toolchain/ndk/sysroot/usr/include/stdio.h").apply { parentFile.mkdirs(); writeText("int printf();") }
        File(sentinel, "native/build/host/libclu_llama.so").apply { parentFile.mkdirs(); writeBytes(ByteArray(4096) { it.toByte() }) }
        File(sentinel, "system/clang/include/stdbool.h").apply { parentFile.mkdirs(); writeText("#define bool _Bool") }
    }

    /** A workspace laid out like the one that caused the incident: links to the owner's folders, and a nested link inside them. */
    private fun incidentWorkspace(): File {
        val ws = File(workspaces, "repair-1").apply { mkdirs() }
        File(ws, "core/src/main/A.kt").apply { parentFile.mkdirs(); writeText("class A") }
        link(File(ws, "toolchain"), File(sentinel, "toolchain"))
        link(File(ws, "native/build"), File(sentinel, "native/build"))
        // Nested: the owner's native build itself holds a link to the system headers (as cmake's clang-resource/include did).
        link(File(sentinel, "native/build/cmake-android/clang-resource/include"), File(sentinel, "system/clang/include"))
        // A workspace-local link chain: link -> link -> owner folder.
        link(File(ws, "chain2"), File(sentinel, "toolchain")); link(File(ws, "chain1"), File(ws, "chain2"))
        // Broken links, at the top and nested.
        link(File(ws, "dangling"), File(base, "does-not-exist")); link(File(ws, "core/broken"), File(ws, "gone/away"))
        return ws
    }

    @Test fun theIncidentLayoutIsDeletedAndEveryLinkedFolderSurvivesByteForByte() {
        val before = snapshot(sentinel)
        val ws = incidentWorkspace()
        assertTrue(SafeDelete.tree(ws, listOf(workspaces)))
        assertFalse(Files.exists(ws.toPath(), LinkOption.NOFOLLOW_LINKS), "the workspace is gone")
        assertEquals(before, snapshot(sentinel), "toolchain, native build and system headers are untouched")
        assertTrue(File(sentinel, "system/clang/include/stdbool.h").exists(), "the nested link's target survives")
    }

    @Test fun aLinkGivenAsTheTargetRemovesOnlyTheLink() {
        val l = File(workspaces, "toolchain-link"); link(l, File(sentinel, "toolchain"))
        val before = snapshot(sentinel)
        assertTrue(SafeDelete.tree(l, listOf(workspaces)))
        assertFalse(Files.exists(l.toPath(), LinkOption.NOFOLLOW_LINKS)); assertEquals(before, snapshot(sentinel))
        val dangling = File(workspaces, "dangling"); link(dangling, File(base, "nothing"))
        assertTrue(SafeDelete.tree(dangling, listOf(workspaces)), "a broken link is removed")
        assertFalse(Files.exists(dangling.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun targetsOutsideTheAllowedFoldersAreRefusedAndNothingIsDeleted() {
        val before = snapshot(sentinel)
        assertFailsWith<SafeDelete.Refused> { SafeDelete.tree(File(sentinel, "toolchain"), listOf(workspaces)) }
        assertFailsWith<SafeDelete.Refused> { SafeDelete.tree(workspaces, listOf(workspaces)) } // never the allowed folder itself
        assertFailsWith<SafeDelete.Refused> { SafeDelete.tree(File(workspaces, "../../owner/toolchain"), listOf(workspaces)) } // ".." cannot escape
        assertFailsWith<SafeDelete.Refused> { SafeDelete.tree(File(sentinel, "toolchain"), emptyList()) }
        assertEquals(before, snapshot(sentinel))
    }

    @Test fun aTargetReachedThroughALinkedParentIsJudgedByWhereItReallyIs() {
        // workspaces/escape -> owner ; workspaces/escape/toolchain is really owner/toolchain: refused.
        link(File(workspaces, "escape"), sentinel)
        val before = snapshot(sentinel)
        assertFailsWith<SafeDelete.Refused> { SafeDelete.tree(File(workspaces, "escape/toolchain"), listOf(workspaces)) }
        assertEquals(before, snapshot(sentinel))
        // Removing the link itself is fine and leaves the owner's folder alone.
        assertTrue(SafeDelete.tree(File(workspaces, "escape"), listOf(workspaces))); assertEquals(before, snapshot(sentinel))
    }

    @Test fun protectedFoldersAreRefusedEvenInsideAnAllowedFolder() {
        val repo = File(workspaces, "checkout").apply { mkdirs() }
        File(repo, "toolchain/apksig.jar").apply { parentFile.mkdirs(); writeText("jar") }
        assertFailsWith<SafeDelete.Refused> { SafeDelete.tree(File(repo, "toolchain"), listOf(workspaces), protected = listOf(File(repo, "toolchain"))) }
        assertFailsWith<SafeDelete.Refused> { SafeDelete.tree(repo, listOf(workspaces), protected = listOf(File(repo, "toolchain"))) } // contains a protected folder
        assertTrue(File(repo, "toolchain/apksig.jar").exists())
    }

    @Test fun plainFilesAndMissingTargetsAreHandled() {
        val f = File(workspaces, "x.log").apply { writeText("log") }
        assertTrue(SafeDelete.tree(f, listOf(workspaces))); assertFalse(f.exists())
        assertTrue(SafeDelete.tree(File(workspaces, "never-existed"), listOf(workspaces)))
    }
}
