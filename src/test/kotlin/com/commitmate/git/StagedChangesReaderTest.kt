package com.commitmate.git

import com.commitmate.model.ChangeSource
import com.commitmate.model.ChangeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 使用真实 git 命令验证暂存区读取行为（需要本机安装 git）。
 */
class StagedChangesReaderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var repository: Path
    private val reader = StagedChangesReader()

    @BeforeEach
    fun setUp() {
        Assumptions.assumeTrue(GitTestSupport.isGitAvailable, "本机未安装 git，跳过 Git 集成测试")
        repository = tempDir.resolve("repo")
        GitTestSupport.initRepository(repository)
        GitTestSupport.writeFile(repository, "a.txt", "hello\n")
        GitTestSupport.writeFile(repository, "old.txt", "rename me\n")
        GitTestSupport.commitAll(repository, "init")
    }

    @Test
    fun `暂存区为空且关闭回退时不读取工作区`() {
        GitTestSupport.writeFile(repository, "a.txt", "changed but not staged\n")

        val changes = reader.read(repository, 60_000, allowWorkingTreeFallback = false)

        assertTrue(changes.isEmpty)
        assertEquals(ChangeSource.STAGED, changes.source)
        assertTrue(changes.diff.text.isBlank())
    }

    @Test
    fun `暂存区为空时回退到工作区未提交变更`() {
        GitTestSupport.writeFile(repository, "a.txt", "unstaged content\n")

        val changes = reader.read(repository, 60_000)

        assertFalse(changes.isEmpty)
        assertEquals(ChangeSource.WORKING_TREE, changes.source)
        assertTrue(changes.isWorkingTree)
        assertEquals(1, changes.files.size)
        assertEquals(ChangeType.MODIFIED, changes.files[0].type)
        assertTrue(changes.diff.text.contains("+unstaged content"))
    }

    @Test
    fun `回退时不包含未跟踪文件`() {
        GitTestSupport.writeFile(repository, "a.txt", "unstaged content\n")
        GitTestSupport.writeFile(repository, "brand-new.txt", "untracked\n")

        val changes = reader.read(repository, 60_000)

        assertEquals(ChangeSource.WORKING_TREE, changes.source)
        assertEquals(1, changes.files.size)
        assertEquals("a.txt", changes.files[0].path)
    }

    @Test
    fun `只处理指定文件`() {
        GitTestSupport.writeFile(repository, "a.txt", "change a\n")
        GitTestSupport.writeFile(repository, "old.txt", "change b\n")

        val onlyA = setOf(repository.resolve("a.txt").toAbsolutePath().normalize())
        val changes = reader.read(repository, 60_000, includePaths = onlyA)

        assertEquals(1, changes.files.size)
        assertEquals("a.txt", changes.files[0].path)
        assertTrue(changes.diff.text.contains("+change a"))
        assertFalse(changes.diff.text.contains("change b"))
    }

    @Test
    fun `指定的文件不存在时返回空`() {
        GitTestSupport.writeFile(repository, "a.txt", "change a\n")

        val missing = setOf(repository.resolve("not-changed.txt").toAbsolutePath().normalize())
        val changes = reader.read(repository, 60_000, includePaths = missing)

        assertTrue(changes.isEmpty)
    }

    @Test
    fun `有暂存内容时不回退到工作区`() {
        GitTestSupport.writeFile(repository, "a.txt", "staged content\n")
        GitTestSupport.git(repository, "add", "a.txt")
        GitTestSupport.writeFile(repository, "old.txt", "unstaged only\n")

        val changes = reader.read(repository, 60_000)

        assertEquals(ChangeSource.STAGED, changes.source)
        assertEquals(1, changes.files.size)
        assertEquals("a.txt", changes.files[0].path)
    }

    @Test
    fun `只读取暂存区内容而不读取未暂存修改`() {
        GitTestSupport.writeFile(repository, "a.txt", "staged content\n")
        GitTestSupport.git(repository, "add", "a.txt")
        GitTestSupport.writeFile(repository, "a.txt", "unstaged content\n")

        val changes = reader.read(repository, 60_000)

        assertEquals(1, changes.files.size)
        assertEquals(ChangeType.MODIFIED, changes.files[0].type)
        assertEquals(1, changes.modifiedCount)
        assertTrue(changes.diff.text.contains("+staged content"))
        assertFalse(changes.diff.text.contains("+unstaged content"))
    }

    @Test
    fun `识别新增文件`() {
        GitTestSupport.writeFile(repository, "docs/new.md", "# new\n")
        GitTestSupport.git(repository, "add", "docs/new.md")

        val changes = reader.read(repository, 60_000)

        assertEquals(1, changes.files.size)
        assertEquals(ChangeType.ADDED, changes.files[0].type)
        assertEquals("docs/new.md", changes.files[0].path)
        assertEquals(1, changes.addedCount)
    }

    @Test
    fun `识别删除文件`() {
        Files.delete(repository.resolve("old.txt"))
        GitTestSupport.git(repository, "add", "-A")

        val changes = reader.read(repository, 60_000)

        assertEquals(1, changes.files.size)
        assertEquals(ChangeType.DELETED, changes.files[0].type)
        assertEquals(1, changes.deletedCount)
    }

    @Test
    fun `识别重命名文件`() {
        GitTestSupport.git(repository, "mv", "old.txt", "renamed.txt")

        val changes = reader.read(repository, 60_000)

        assertEquals(1, changes.files.size)
        assertEquals(ChangeType.RENAMED, changes.files[0].type)
        assertEquals("old.txt", changes.files[0].oldPath)
        assertEquals("renamed.txt", changes.files[0].path)
        assertEquals(1, changes.renamedCount)
    }

    @Test
    fun `超大 Diff 会截断并标记`() {
        val content = buildString {
            repeat(4_000) { appendLine("line $it: 用于验证超大 Diff 截断行为的内容") }
        }
        GitTestSupport.writeFile(repository, "big.txt", content)
        GitTestSupport.git(repository, "add", "big.txt")

        val changes = reader.read(repository, maxDiffCharacters = 2_000)

        assertTrue(changes.diff.truncated)
        assertTrue(changes.diff.text.length <= 2_000)
        assertTrue(changes.diff.originalCharacters > changes.diff.includedCharacters)
        assertTrue(changes.diff.text.contains("[Commit Mate]"))
    }
}
