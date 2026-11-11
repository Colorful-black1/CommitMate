package com.commitmate.llm

/**
 * 解析用户在设置里填写的自定义请求头。
 *
 * 每行一条 `Name: Value`，空行与 `#` 开头的注释会被忽略；名称只允许字母、数字、`-`、`_`，
 * 避免把非法值交给 [java.net.HttpURLConnection] 触发难以理解的异常。
 *
 * 典型用途：某些网关（如 opencode-go）要求额外的 `X-Head-Session` 之类的请求头。
 */
object ExtraHeaders {

    fun parse(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()

        val headers = LinkedHashMap<String, String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val separator = trimmed.indexOf(':')
            if (separator <= 0) continue

            val name = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            if (name.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) continue

            headers[name] = value
        }
        return headers
    }
}
