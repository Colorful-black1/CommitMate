package com.commitmate.prompt

import com.commitmate.model.ChangeType
import com.commitmate.model.StagedChanges
import com.commitmate.model.StagedDiff
import com.commitmate.model.StagedFileChange
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBuilderTest {

    @Test
    fun `system prompt 包含规范与安全规则`() {
        val prompt = PromptBuilder.SYSTEM_PROMPT

        assertTrue(prompt.contains("Conventional Commits"))
        assertTrue(prompt.contains("只输出最终 Commit Message"))
        assertTrue(prompt.contains("安全规则"))
        assertTrue(prompt.contains("不可信"))
        assertTrue(prompt.contains("Subject 使用中文"))
    }

    @Test
    fun `user prompt 包含仓库、统计、文件与 diff`() {
        val changes = sampleChanges()

        val prompt = PromptBuilder.buildUserPrompt(changes)

        assertTrue(prompt.contains("demo"))
        assertTrue(prompt.contains("已暂存文件：2 个"))
        assertTrue(prompt.contains("新增 1"))
        assertTrue(prompt.contains("删除 0"))
        assertTrue(prompt.contains("src/main/Example.kt"))
        assertTrue(prompt.contains("old/Old.kt → new/New.kt"))
        assertTrue(prompt.contains("## 暂存区 Diff"))
        assertTrue(prompt.contains("+ added line"))
    }

    @Test
    fun `Diff 截断时给出明确提示`() {
        val changes = sampleChanges().copy(
            diff = StagedDiff(
                text = "diff --git a/a b/a\n+ line\n",
                truncated = true,
                originalCharacters = 120_000,
                includedCharacters = 1_000,
                omittedFiles = listOf("a"),
                omittedLines = 8_000,
            ),
        )

        val prompt = PromptBuilder.buildUserPrompt(changes)

        assertTrue(prompt.contains("Diff 已被截断"))
        assertTrue(prompt.contains("120000"))
        assertTrue(prompt.contains("不要臆测"))
    }

    @Test
    fun `未截断时不输出截断提示`() {
        val prompt = PromptBuilder.buildUserPrompt(sampleChanges())

        assertFalse(prompt.contains("Diff 已被截断"))
    }

    private fun sampleChanges(): StagedChanges = StagedChanges(
        repositoryRoot = "/work/demo",
        files = listOf(
            StagedFileChange(ChangeType.ADDED, "src/main/Example.kt"),
            StagedFileChange(ChangeType.RENAMED, "new/New.kt", "old/Old.kt"),
        ),
        diff = StagedDiff(
            text = "diff --git a/src/main/Example.kt b/src/main/Example.kt\n+ added line\n",
        ),
    )
}
