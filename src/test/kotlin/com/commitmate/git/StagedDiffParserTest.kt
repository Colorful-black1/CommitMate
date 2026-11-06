package com.commitmate.git

import com.commitmate.model.ChangeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StagedDiffParserTest {

    @Test
    fun `解析修改与新增条目`() {
        val raw = "M\u0000a.txt\u0000A\u0000new.txt\u0000"
        val changes = StagedDiffParser.parseNameStatusZ(raw)

        assertEquals(2, changes.size)
        assertEquals(ChangeType.MODIFIED, changes[0].type)
        assertEquals("a.txt", changes[0].path)
        assertEquals(ChangeType.ADDED, changes[1].type)
        assertEquals("new.txt", changes[1].path)
    }

    @Test
    fun `解析重命名条目`() {
        val raw = "R100\u0000old.txt\u0000renamed.txt\u0000"
        val changes = StagedDiffParser.parseNameStatusZ(raw)

        assertEquals(1, changes.size)
        assertEquals(ChangeType.RENAMED, changes[0].type)
        assertEquals("renamed.txt", changes[0].path)
        assertEquals("old.txt", changes[0].oldPath)
        assertEquals("old.txt → renamed.txt", changes[0].displayPath)
    }

    @Test
    fun `解析删除与含空格路径`() {
        val raw = "D\u0000docs/old file.md\u0000"
        val changes = StagedDiffParser.parseNameStatusZ(raw)

        assertEquals(1, changes.size)
        assertEquals(ChangeType.DELETED, changes[0].type)
        assertEquals("docs/old file.md", changes[0].path)
    }

    @Test
    fun `空输出返回空列表`() {
        assertTrue(StagedDiffParser.parseNameStatusZ("").isEmpty())
        assertTrue(StagedDiffParser.parseNameStatusZ("   ").isEmpty())
    }
}
