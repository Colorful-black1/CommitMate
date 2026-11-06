package com.commitmate.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommitMessageSanitizerTest {

    @Test
    fun `去除 markdown 代码块`() {
        val raw = "```\nfeat(core): add feature\n\n- Do something\n```"

        val result = CommitMessageSanitizer.sanitize(raw)

        assertEquals("feat(core): add feature\n\n- Do something", result)
    }

    @Test
    fun `去除带语言标记的代码块`() {
        val raw = "```text\nfix(api): handle null body\n```"

        assertEquals("fix(api): handle null body", CommitMessageSanitizer.sanitize(raw))
    }

    @Test
    fun `去除多余前缀`() {
        assertEquals("fix(a): b", CommitMessageSanitizer.sanitize("Commit message: fix(a): b"))
        assertEquals("fix(a): b", CommitMessageSanitizer.sanitize("提交信息：fix(a): b"))
    }

    @Test
    fun `折叠多余空行并去除首尾空白`() {
        val raw = "\n\nfeat: x\n\n\n\n- body\n\n"

        assertEquals("feat: x\n\n- body", CommitMessageSanitizer.sanitize(raw))
    }

    @Test
    fun `空内容返回空字符串`() {
        assertEquals("", CommitMessageSanitizer.sanitize("   \n\n  "))
    }

    @Test
    fun `普通内容保持不变`() {
        val message = "refactor(service): extract git reader\n\n- Move git parsing into StagedChangesReader"

        assertEquals(message, CommitMessageSanitizer.sanitize(message))
    }
}
