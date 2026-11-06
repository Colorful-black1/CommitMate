package com.commitmate.git

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffSectionFilterTest {

    private val diff = """
        diff --git a/a.txt b/a.txt
        index 111..222 100644
        --- a/a.txt
        +++ b/a.txt
        @@ -1 +1 @@
        -old a
        +new a
        diff --git a/b.txt b/b.txt
        index 333..444 100644
        --- a/b.txt
        +++ b/b.txt
        @@ -1 +1 @@
        -old b
        +new b
    """.trimIndent() + "\n"

    @Test
    fun `只保留指定文件的 diff 块`() {
        val filtered = DiffSectionFilter.filter(diff, setOf("b.txt"))

        assertTrue(filtered.contains("b/b.txt"))
        assertTrue(filtered.contains("+new b"))
        assertFalse(filtered.contains("a/a.txt"))
        assertFalse(filtered.contains("+new a"))
    }

    @Test
    fun `路径为空时原样返回`() {
        assertTrue(DiffSectionFilter.filter(diff, emptySet()) == diff)
    }

    @Test
    fun `重命名块可以按旧路径命中`() {
        val renameDiff = """
            diff --git a/legacy/Old.kt b/new/New.kt
            similarity index 100%
            rename from legacy/Old.kt
            rename to new/New.kt
        """.trimIndent() + "\n"

        val filtered = DiffSectionFilter.filter(renameDiff, setOf("legacy/Old.kt"))

        assertTrue(filtered.contains("rename to new/New.kt"))
    }

    @Test
    fun `都不匹配时返回空字符串`() {
        val filtered = DiffSectionFilter.filter(diff, setOf("missing.txt"))

        assertFalse(filtered.contains("diff --git"))
    }
}
