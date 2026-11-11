package com.commitmate.llm

import com.commitmate.core.OperationCanceledException
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

class ChatCompletionClientTest {

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private val client = ChatCompletionClient()

    @AfterEach
    fun tearDown() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    @Test
    fun `成功返回 choices 0 message content`() {
        val authorization = AtomicReference<String>()
        val requestBody = AtomicReference<String>()
        val baseUrl = startServer { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            requestBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            respond(exchange, 200, """{"choices":[{"message":{"role":"assistant","content":"feat(core): add x"}}]}""")
        }

        val content = client.complete(config(baseUrl), messages())

        assertEquals("feat(core): add x", content)
        assertEquals("Bearer test-key", authorization.get())
        assertTrue(requestBody.get().contains("\"model\":\"deepseek-chat\""))
        assertTrue(requestBody.get().contains("\"role\":\"system\""))
    }

    @Test
    fun `HTTP 401 时给出 API Key 提示`() {
        val baseUrl = startServer { exchange ->
            respond(exchange, 401, """{"error":{"message":"Invalid API key"}}""")
        }

        val exception = assertThrows<LlmException> {
            client.complete(config(baseUrl), messages())
        }

        assertEquals(401, exception.httpStatus)
        assertTrue(exception.message!!.contains("API Key"))
        assertTrue(exception.message!!.contains("Invalid API key"))
    }

    @Test
    fun `HTTP 404 时提示检查 Base URL`() {
        val baseUrl = startServer { exchange -> respond(exchange, 404, "not found") }

        val exception = assertThrows<LlmException> {
            client.complete(config(baseUrl), messages())
        }

        assertEquals(404, exception.httpStatus)
        assertTrue(exception.message!!.contains("API Base URL"))
    }

    @Test
    fun `返回内容为空时抛错`() {
        val baseUrl = startServer { exchange ->
            respond(exchange, 200, """{"choices":[{"message":{"content":"   "}}]}""")
        }

        val exception = assertThrows<LlmException> {
            client.complete(config(baseUrl), messages())
        }

        assertTrue(exception.message!!.contains("空"))
    }

    @Test
    fun `响应缺少 choices 时抛错`() {
        val baseUrl = startServer { exchange -> respond(exchange, 200, """{"id":"x"}""") }

        val exception = assertThrows<LlmException> {
            client.complete(config(baseUrl), messages())
        }

        assertTrue(exception.message!!.contains("choices"))
    }

    @Test
    fun `响应不是合法 JSON 时抛错`() {
        val baseUrl = startServer { exchange -> respond(exchange, 200, "<html>oops</html>") }

        val exception = assertThrows<LlmException> {
            client.complete(config(baseUrl), messages())
        }

        assertTrue(exception.message!!.contains("无法解析"))
    }

    @Test
    fun `取消后抛出 OperationCanceledException`() {
        val baseUrl = startServer { exchange ->
            Thread.sleep(3_000)
            runCatching { respond(exchange, 200, """{"choices":[]}""") }
        }

        assertThrows<OperationCanceledException> {
            client.complete(config(baseUrl), messages(), isCanceled = { true })
        }
    }

    @Test
    fun `流式输出逐段回调并返回完整内容`() {
        val chunks = mutableListOf<String>()
        val gson = com.google.gson.Gson()
        val pieces = listOf("feat(core): ", "新增示例", "\n\n- 细节一", "\n- 细节二")
        val baseUrl = startServer { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                for (piece in pieces) {
                    val payload = """{"choices":[{"delta":{"content":${gson.toJson(piece)}}}]}"""
                    out.write("data: $payload\n\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }

        val text = client.stream(config(baseUrl), messages()) { chunks.add(it) }

        assertEquals(pieces.joinToString(""), text)
        assertEquals(pieces, chunks)
    }

    @Test
    fun `流式请求取消后立即返回`() {
        val baseUrl = startServer { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                repeat(30) {
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n".toByteArray())
                    out.flush()
                    Thread.sleep(100)
                }
            }
        }

        assertThrows<OperationCanceledException> {
            client.stream(config(baseUrl), messages(), isCanceled = { true }) { }
        }
    }

    @Test
    fun `自定义请求头会被带上`() {
        val captured = AtomicReference<String>()
        val baseUrl = startServer { exchange ->
            captured.set(exchange.requestHeaders.getFirst("X-Head-Session"))
            respond(exchange, 200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }

        client.complete(
            config(baseUrl).copy(extraHeaders = mapOf("X-Head-Session" to "sess-42")),
            messages(),
        )

        assertEquals("sess-42", captured.get())
    }

    @Test
    fun `默认发送会话头与自定义 User-Agent`() {
        val session = AtomicReference<String>()
        val userAgent = AtomicReference<String>()
        val baseUrl = startServer { exchange ->
            session.set(exchange.requestHeaders.getFirst("x-opencode-session"))
            userAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            respond(exchange, 200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }

        client.complete(config(baseUrl).copy(sessionId = "sess-stable"), messages())

        assertEquals("sess-stable", session.get())
        assertTrue(userAgent.get().startsWith("commit-mate/"))
    }

    @Test
    fun `会话 ID 为空时发送兜底值`() {
        val session = AtomicReference<String>()
        val baseUrl = startServer { exchange ->
            session.set(exchange.requestHeaders.getFirst("x-opencode-session"))
            respond(exchange, 200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }

        client.complete(config(baseUrl), messages())

        assertTrue(session.get().isNotBlank())
    }

    @Test
    fun `未配置 API Key 时直接失败`() {
        val exception = assertThrows<LlmException> {
            client.complete(
                config("http://127.0.0.1:1/v1").copy(apiKey = ""),
                messages(),
            )
        }

        assertTrue(exception.message!!.contains("API Key"))
    }

    private fun messages(): List<ChatMessage> = listOf(
        ChatMessage("system", "system prompt"),
        ChatMessage("user", "user prompt"),
    )

    private fun config(baseUrl: String): LlmConfig = LlmConfig(
        baseUrl = baseUrl,
        apiKey = "test-key",
        model = "deepseek-chat",
        connectTimeoutMillis = 5_000,
        readTimeoutMillis = 10_000,
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
