package com.commitmate.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GitRepositoryLocatorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `从子目录向上找到仓库根目录`() {
        val root = tempDir.resolve("repo")
        Files.createDirectories(root.resolve(".git"))
        val nested = root.resolve("module/src/main")
        Files.createDirectories(nested)

        assertEquals(root, GitRepositoryLocator.findRepositoryRoot(nested))
        assertEquals(root, GitRepositoryLocator.findRepositoryRoot(root))
    }

    @Test
    fun `支持 git 文件形式（worktree、submodule）`() {
        val root = tempDir.resolve("worktree")
        Files.createDirectories(root)
        Files.writeString(root.resolve(".git"), "gitdir: ../.git/modules/x")
        val nested = root.resolve("src")
        Files.createDirectories(nested)

        assertEquals(root, GitRepositoryLocator.findRepositoryRoot(nested))
    }

    @Test
    fun `非 Git 目录返回 null`() {
        val plain = tempDir.resolve("plain")
        Files.createDirectories(plain.resolve("a/b"))

        assertNull(GitRepositoryLocator.findRepositoryRoot(plain.resolve("a/b")))
    }

    @Test
    fun `多仓库去重并排序`() {
        val first = tempDir.resolve("first")
        val second = tempDir.resolve("second")
        Files.createDirectories(first.resolve(".git"))
        Files.createDirectories(second.resolve(".git"))
        Files.createDirectories(first.resolve("sub"))
        Files.createDirectories(second.resolve("sub"))

        val roots = GitRepositoryLocator.findRepositoryRoots(
            listOf(first, first.resolve("sub"), second, second.resolve("sub")),
        )

        assertEquals(listOf(first, second), roots)
    }
}
