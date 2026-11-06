package com.commitmate.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffCompactorTest {

    @Test
    fun `未超限时原样返回`() {
        val diff = buildDiff(sectionCount = 2, hunkLines = 3)
        val result = DiffCompactor.compact(diff, 100_000)

        assertFalse(result.truncated)
        assertEquals(diff, result.text)
        assertEquals(diff.length, result.originalCharacters)
        assertEquals(diff.length, result.includedCharacters)
    }

    @Test
    fun `超限时截断并显式提示`() {
        val diff = buildDiff(sectionCount = 30, hunkLines = 60)
        val limit = 4_000

        val result = DiffCompactor.compact(diff, limit)

        assertTrue(result.truncated)
        assertTrue(result.text.length <= limit, "截断后长度必须不超过上限，实际 ${result.text.length}")
        assertEquals(diff.length, result.originalCharacters)
        assertEquals(result.text.length, result.includedCharacters)
        assertTrue(result.text.contains("[Commit Mate]"), "必须包含明确的截断提示")
        assertTrue(result.omittedLines > 0)
    }

    @Test
    fun `截断时优先保留文件头`() {
        val diff = buildDiff(sectionCount = 20, hunkLines = 40)
        val result = DiffCompactor.compact(diff, 3_000)

        assertTrue(result.text.contains("diff --git a/file0.txt b/file0.txt"))
        assertTrue(result.omittedFiles.isNotEmpty())
    }

    @Test
    fun `maxCharacters 非法时抛异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiffCompactor.compact("diff --git a/a b/a\n", 0)
        }
    }

    private fun buildDiff(sectionCount: Int, hunkLines: Int): String = buildString {
        repeat(sectionCount) { index ->
            appendLine("diff --git a/file$index.txt b/file$index.txt")
            appendLine("index 0000000..1111111 100644")
            appendLine("--- a/file$index.txt")
            appendLine("+++ b/file$index.txt")
            appendLine("@@ -1,$hunkLines +1,$hunkLines @@")
            repeat(hunkLines) { line ->
                appendLine("-old line $line of file $index")
                appendLine("+new line $line of file $index")
            }
        }
    }
}
