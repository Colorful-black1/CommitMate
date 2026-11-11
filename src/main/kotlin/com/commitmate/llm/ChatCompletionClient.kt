package com.commitmate.llm

import com.commitmate.core.OperationCanceledException
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * 调用 OpenAI Chat Completions 兼容接口（DeepSeek 等）。
 *
 * - 通过 [HttpURLConnection] 发起请求，不引入额外 HTTP 依赖；
 * - 请求体与响应体使用 Gson（IntelliJ Platform 已内置）处理；
 * - 阻塞式 socket 读取放在独立 daemon 线程中，调用线程以短轮询等待，
 *   因此取消可以立即返回，而不依赖 `disconnect()` 是否中断底层读操作；
 * - 任何异常都不会包含 API Key 或 Diff 内容。
 */
class ChatCompletionClient {

    private val gson = Gson()

    fun complete(
        config: LlmConfig,
        messages: List<ChatMessage>,
        isCanceled: () -> Boolean = { false },
    ): String {
        if (config.apiKey.isBlank()) {
            throw LlmException("尚未配置 API Key，请先在 Commit Mate 设置中填写。")
        }

        val endpoint = LlmConfig.buildEndpoint(config.baseUrl)
        val connection = try {
            URI(endpoint).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw LlmException("API Base URL 无法解析：${config.baseUrl}", cause = e)
        }

        configure(connection, config)
        val payload = buildRequestBody(config, messages).toByteArray(Charsets.UTF_8)

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "commit-mate-http").apply { isDaemon = true }
        }
        try {
            val future = executor.submit(
                Callable<String> {
                    try {
                        connection.outputStream.use { it.write(payload) }
                        val status = connection.responseCode
                        val body = readBody(connection, status)
                        if (status !in 200..299) {
                            throw LlmException(describeHttpError(status, extractErrorMessage(body)), status)
                        }
                        extractContent(body)
                    } catch (e: IOException) {
                        throw LlmException(describeNetworkError(e, config.baseUrl), cause = e)
                    }
                },
            )

            return awaitResult(future, connection, config, isCanceled)
        } finally {
            connection.disconnect()
            executor.shutdownNow()
        }
    }

    private fun configure(connection: HttpURLConnection, config: LlmConfig) {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = config.connectTimeoutMillis
        connection.readTimeout = config.readTimeoutMillis
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        // 仅用于本次请求，不会写入日志
        connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        // 网关可能要求的额外请求头（如 X-Head-Session），不覆盖上面的基础头
        for ((name, value) in config.extraHeaders) {
            if (name.equals("Authorization", ignoreCase = true) ||
                name.equals("Content-Type", ignoreCase = true)
            ) {
                continue
            }
            runCatching { connection.setRequestProperty(name, value) }
        }
    }

    private fun translateFailure(cause: Throwable?, baseUrl: String): Throwable = when (cause) {
        null -> LlmException("请求模型服务失败。")
        is LlmException -> cause
        is OperationCanceledException -> cause
        is IOException -> LlmException(describeNetworkError(cause, baseUrl), cause = cause)
        is RuntimeException -> cause
        else -> LlmException("请求模型服务失败：${cause.message ?: cause.javaClass.simpleName}", cause = cause)
    }

    /**
     * 以流式（SSE）方式生成，每收到一段增量就回调 [onChunk]（在后台线程调用）。
     *
     * 与 [complete] 一样支持取消：取消后立即返回 [OperationCanceledException]，不等待 socket 超时。
     */
    fun stream(
        config: LlmConfig,
        messages: List<ChatMessage>,
        isCanceled: () -> Boolean = { false },
        onChunk: (String) -> Unit,
    ): String {
        if (config.apiKey.isBlank()) {
            throw LlmException("尚未配置 API Key，请先在 Commit Mate 设置中填写。")
        }

        val endpoint = LlmConfig.buildEndpoint(config.baseUrl)
        val connection = try {
            URI(endpoint).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw LlmException("API Base URL 无法解析：${config.baseUrl}", cause = e)
        }

        configure(connection, config)
        val payload = buildRequestBody(config, messages, stream = true).toByteArray(Charsets.UTF_8)

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "commit-mate-http-stream").apply { isDaemon = true }
        }
        try {
            val future = executor.submit(
                Callable<String> {
                    try {
                        connection.outputStream.use { it.write(payload) }
                        val status = connection.responseCode
                        if (status !in 200..299) {
                            val body = readBody(connection, status)
                            throw LlmException(describeHttpError(status, extractErrorMessage(body)), status)
                        }
                        readStream(connection, onChunk)
                    } catch (e: IOException) {
                        throw LlmException(describeNetworkError(e, config.baseUrl), cause = e)
                    }
                },
            )
            return awaitResult(future, connection, config, isCanceled)
        } finally {
            connection.disconnect()
            executor.shutdownNow()
        }
    }

    private fun readStream(connection: HttpURLConnection, onChunk: (String) -> Unit): String {
        val accumulated = StringBuilder()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank() || !line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val delta = extractDelta(payload) ?: continue
                if (delta.isNotEmpty()) {
                    accumulated.append(delta)
                    onChunk(delta)
                }
            }
        }
        if (accumulated.isEmpty()) {
            throw LlmException("模型返回内容为空，请重试或更换模型。")
        }
        return accumulated.toString()
    }

    private fun extractDelta(payloadJson: String): String? {
        val json = runCatching { JsonParser.parseString(payloadJson).asJsonObject }.getOrNull() ?: return null
        val error = json.get("error")
        if (error != null && error.isJsonObject) {
            throw LlmException(extractErrorMessage(payloadJson).ifBlank { "模型服务返回了错误。" })
        }
        val choices = json.get("choices")
        if (choices == null || !choices.isJsonArray) return null
        val first = choices.asJsonArray.firstOrNull()
        if (first == null || !first.isJsonObject) return null
        val delta = first.asJsonObject.get("delta")
        if (delta == null || !delta.isJsonObject) return null
        val content = delta.asJsonObject.get("content")
        if (content == null || content.isJsonNull || !content.isJsonPrimitive) return null
        return content.asString
    }

    /** 等待后台请求完成，同时按 [isCanceled] 轮询取消；超时或取消都会中止。 */
    private fun awaitResult(
        future: java.util.concurrent.Future<String>,
        connection: HttpURLConnection,
        config: LlmConfig,
        isCanceled: () -> Boolean,
    ): String {
        val deadline = System.currentTimeMillis() +
            config.connectTimeoutMillis + config.readTimeoutMillis + EXTRA_WAIT_MILLIS
        while (true) {
            if (isCanceled()) {
                future.cancel(true)
                connection.disconnect()
                throw OperationCanceledException()
            }
            try {
                return future.get(CANCEL_POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (System.currentTimeMillis() > deadline) {
                    future.cancel(true)
                    connection.disconnect()
                    throw LlmException("请求模型服务超时，请检查网络或适当增大超时时间。")
                }
            } catch (e: ExecutionException) {
                throw translateFailure(e.cause, config.baseUrl)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                future.cancel(true)
                connection.disconnect()
                throw OperationCanceledException()
            }
        }
    }

    private fun buildRequestBody(config: LlmConfig, messages: List<ChatMessage>, stream: Boolean = false): String {
        val root = JsonObject()
        root.addProperty("model", config.model)
        root.addProperty("temperature", config.temperature)
        root.addProperty("stream", stream)

        val array = JsonArray()
        for (message in messages) {
            val item = JsonObject()
            item.addProperty("role", message.role)
            item.addProperty("content", message.content)
            array.add(item)
        }
        root.add("messages", array)
        return gson.toJson(root)
    }

    private fun readBody(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun extractContent(body: String): String {
        val json = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            throw LlmException("模型返回了无法解析的响应内容。", cause = e)
        }

        val error = json.get("error")
        if (error != null && error.isJsonObject) {
            throw LlmException(extractErrorMessage(body).ifBlank { "模型服务返回了错误。" })
        }

        val choicesElement = json.get("choices")
        val choices = if (choicesElement != null && choicesElement.isJsonArray) choicesElement.asJsonArray else null
        if (choices == null || choices.isEmpty) {
            throw LlmException("模型返回内容为空（响应中缺少 choices）。")
        }

        val firstChoice = choices.firstOrNull()
        if (firstChoice == null || !firstChoice.isJsonObject) {
            throw LlmException("模型返回内容为空（choices 结构异常）。")
        }
        val message = firstChoice.asJsonObject.get("message")
        if (message == null || !message.isJsonObject) {
            throw LlmException("模型返回内容为空（响应中缺少 message）。")
        }
        val content = message.asJsonObject.get("content")
        if (content == null || content.isJsonNull || !content.isJsonPrimitive) {
            throw LlmException("模型返回内容为空。")
        }

        val text = content.asString
        if (text.isBlank()) {
            throw LlmException("模型返回内容为空，请重试或更换模型。")
        }
        return text
    }

    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return ""
        val parsed = runCatching { JsonParser.parseString(body) }.getOrNull()
        if (parsed != null && parsed.isJsonObject) {
            val obj = parsed.asJsonObject
            val error = obj.get("error")
            if (error != null && error.isJsonObject) {
                val message = error.asJsonObject.get("message")
                if (message != null && !message.isJsonNull && message.isJsonPrimitive) {
                    return message.asString.trim().take(MAX_ERROR_MESSAGE_LENGTH)
                }
            }
            val message = obj.get("message")
            if (message != null && !message.isJsonNull && message.isJsonPrimitive) {
                return message.asString.trim().take(MAX_ERROR_MESSAGE_LENGTH)
            }
        }
        return body.trim().take(MAX_ERROR_MESSAGE_LENGTH)
    }

    private fun describeHttpError(status: Int, serverMessage: String): String {
        val hint = when (status) {
            400 -> "请求被模型服务拒绝（HTTP 400）。"
            401 -> "API Key 无效或已过期（HTTP 401），请检查设置中的 API Key。"
            403 -> "没有权限访问该模型（HTTP 403）。"
            404 -> "请求地址不存在（HTTP 404），请检查 API Base URL 是否为 .../v1 这类前缀。"
            408 -> "模型服务请求超时（HTTP 408）。"
            413 -> "请求体过大（HTTP 413），请缩小暂存区范围或调低最大 Diff 字符数。"
            429 -> "请求过于频繁或额度不足（HTTP 429）。"
            in 500..599 -> "模型服务内部错误（HTTP $status）。"
            else -> "模型服务返回异常状态码（HTTP $status）。"
        }
        return if (serverMessage.isBlank()) hint else "$hint\n$serverMessage"
    }

    private fun describeNetworkError(error: IOException, baseUrl: String): String = when (error) {
        is SocketTimeoutException -> "请求模型服务超时，请检查网络或适当增大超时时间。"
        is UnknownHostException -> "无法解析模型服务域名：$baseUrl，请检查 API Base URL、DNS 或代理设置。"
        is ConnectException -> "无法连接到模型服务：$baseUrl，请检查网络或代理设置。"
        is SSLException -> "HTTPS 证书校验失败：${error.message}"
        else -> "网络请求失败：${error.message ?: error.javaClass.simpleName}"
    }

    companion object {
        private const val CANCEL_POLL_INTERVAL_MILLIS = 100L
        private const val EXTRA_WAIT_MILLIS = 5_000L
        private const val MAX_ERROR_MESSAGE_LENGTH = 500
    }
}
