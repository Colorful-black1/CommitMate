package com.commitmate.llm

/** OpenAI Chat Completions 中的一条消息。 */
data class ChatMessage(val role: String, val content: String)

/** 调用大模型所需的配置。API Key 只在内存中传递，不会被写入普通配置文件。 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    /** 额外的请求头，例如网关要求的 `X-Head-Session`。 */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** 稳定会话 ID，作为 `x-opencode-session` 发送（OpenCode Go 用于路由与缓存优化）。 */
    val sessionId: String = "",
) {
    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL: String = "deepseek-chat"
        const val DEFAULT_TEMPERATURE: Double = 0.2
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 15_000
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 120_000

        /** 把 Base URL 规范化为 `.../chat/completions`。 */
        fun buildEndpoint(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }
    }
}

/** 调用模型过程中的可预期错误，message 会直接展示给用户。 */
class LlmException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
