package com.commitmate.llm

/**
 * 清洗模型输出：去掉 Markdown 代码块、多余前缀、首尾空行。
 *
 * 即使提示词已经要求“不要输出代码块”，不同模型仍可能返回 ```git 包裹的内容，
 * 这里做一次兜底，保证填进编辑框的是可直接使用的提交信息。
 */
object CommitMessageSanitizer {

    private val LEADING_LABELS = listOf(
        "commit message:",
        "commit message：",
        "git commit message:",
        "git commit message：",
        "提交信息:",
        "提交信息：",
        "提交说明:",
        "提交说明：",
    )

    private val MULTIPLE_BLANK_LINES = Regex("\n{3,}")

    fun sanitize(raw: String): String {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) return ""

        text = stripCodeFence(text)
        text = stripLeadingLabel(text)
        text = MULTIPLE_BLANK_LINES.replace(text, "\n\n")
        return text.trim()
    }

    private fun stripCodeFence(text: String): String {
        var result = text
        if (result.startsWith("```")) {
            val firstNewline = result.indexOf('\n')
            result = if (firstNewline >= 0) result.substring(firstNewline + 1) else ""
        }
        val trimmed = result.trimEnd()
        if (trimmed.endsWith("```")) {
            result = trimmed.dropLast(3)
        }
        return result.trim()
    }

    private fun stripLeadingLabel(text: String): String {
        val lines = text.split('\n')
        if (lines.isEmpty()) return text
        val first = lines.first()
        val label = LEADING_LABELS.firstOrNull { first.lowercase().startsWith(it) } ?: return text
        val remainder = first.substring(label.length).trimStart(' ', '\t', '-', ':', '：')
        val rest = lines.drop(1)
        return if (remainder.isBlank()) rest.joinToString("\n") else (listOf(remainder) + rest).joinToString("\n")
    }
}
