package com.commitmate.llm

import com.commitmate.model.ChangeType
import com.commitmate.model.StagedChanges
import com.commitmate.model.StagedDiff
import com.commitmate.model.StagedFileChange
import com.commitmate.service.CommitMessageService
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class CommitMessageServiceTest {

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    @Test
    fun `端到端生成并清洗代码块`() {
        val requestBody = AtomicReference<String>()
        val baseUrl = startServer { exchange ->
            requestBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            respond(
                exchange,
                200,
                """{"choices":[{"message":{"content":"```\nfeat(core): add example\n\n- Add Example.kt\n```"}}]}""",
            )
        }

        val message = CommitMessageService().generate(config(baseUrl), sampleChanges())

        assertEquals("feat(core): add example\n\n- Add Example.kt", message)
        assertTrue(requestBody.get().contains("Conventional Commits"))
        assertTrue(requestBody.get().contains("src/main/Example.kt"))
        assertTrue(requestBody.get().contains("已暂存文件：1 个"))
    }

    @Test
    fun `空暂存区直接失败`() {
        val exception = assertThrows<LlmException> {
            CommitMessageService().generate(config("http://127.0.0.1:1/v1"), StagedChanges(repositoryRoot = "/work/demo"))
        }

        assertTrue(exception.message!!.contains("暂存"))
    }

    @Test
    fun `模型返回空内容时失败`() {
        val baseUrl = startServer { exchange ->
            respond(exchange, 200, """{"choices":[{"message":{"content":""}}]}""")
        }

        val exception = assertThrows<LlmException> {
            CommitMessageService().generate(config(baseUrl), sampleChanges())
        }

        assertTrue(exception.message!!.contains("空"))
    }

    @Test
    fun `endpoint 规范化`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            LlmConfig.buildEndpoint("https://api.deepseek.com/v1/"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            LlmConfig.buildEndpoint("https://api.deepseek.com/v1/chat/completions"),
        )
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            LlmConfig.buildEndpoint("  http://127.0.0.1:8080/v1  "),
        )
    }

    private fun config(baseUrl: String): LlmConfig = LlmConfig(
        baseUrl = baseUrl,
        apiKey = "test-key",
        model = "deepseek-chat",
        connectTimeoutMillis = 5_000,
        readTimeoutMillis = 10_000,
    )

    private fun sampleChanges(): StagedChanges = StagedChanges(
        repositoryRoot = "/work/demo",
        files = listOf(StagedFileChange(ChangeType.ADDED, "src/main/Example.kt")),
        diff = StagedDiff("diff --git a/src/main/Example.kt b/src/main/Example.kt\n+ fun main() {}\n"),
    )

    private fun startServer(handler: (HttpExchange) -> Unit): String {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/v1/chat/completions") { exchange -> handler(exchange) }
        val pool = Executors.newCachedThreadPool()
        httpServer.executor = pool
        httpServer.start()
        server = httpServer
        executor = pool
        return "http://127.0.0.1:${httpServer.address.port}/v1"
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
