package com.commitmate.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtraHeadersTest {

    @Test
    fun `解析单条请求头`() {
        val headers = ExtraHeaders.parse("X-Head-Session: abc-123")

        assertEquals(mapOf("X-Head-Session" to "abc-123"), headers)
    }

    @Test
    fun `解析多条并忽略空行与注释`() {
        val raw = """
            # 网关需要的头
            X-Head-Session: abc-123

            X-Custom-Token:xyz
        """.trimIndent()

        val headers = ExtraHeaders.parse(raw)

        assertEquals("abc-123", headers["X-Head-Session"])
        assertEquals("xyz", headers["X-Custom-Token"])
        assertEquals(2, headers.size)
    }

    @Test
    fun `值里可以有冒号`() {
        val headers = ExtraHeaders.parse("X-Url: https://example.com/a:b")

        assertEquals("https://example.com/a:b", headers["X-Url"])
    }

    @Test
    fun `忽略非法行`() {
        val raw = """
            没有冒号的一行
            : 少了名称
            Bad Header: value
            Good-Header: ok
        """.trimIndent()

        val headers = ExtraHeaders.parse(raw)

        assertEquals(1, headers.size)
        assertEquals("ok", headers["Good-Header"])
    }

    @Test
    fun `空输入返回空表`() {
        assertTrue(ExtraHeaders.parse("").isEmpty())
        assertTrue(ExtraHeaders.parse("   \n\n ").isEmpty())
    }
}
