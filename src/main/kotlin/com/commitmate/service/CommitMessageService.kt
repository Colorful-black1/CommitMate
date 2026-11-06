package com.commitmate.service

import com.commitmate.llm.ChatCompletionClient
import com.commitmate.llm.ChatMessage
import com.commitmate.llm.CommitMessageSanitizer
import com.commitmate.llm.LlmConfig
import com.commitmate.llm.LlmException
import com.commitmate.model.StagedChanges
import com.commitmate.prompt.PromptBuilder

/**
 * Commit Message 生成服务：组装 Prompt → 调用模型 → 清洗结果。
 *
 * 提供一次性（[generate]）与流式（[generateStreaming]）两种方式；
 * 不依赖 IntelliJ Platform，便于用本地 HTTP 服务做端到端测试。
 */
class CommitMessageService(
    private val client: ChatCompletionClient = ChatCompletionClient(),
) {

    fun generate(
        config: LlmConfig,
        changes: StagedChanges,
        isCanceled: () -> Boolean = { false },
    ): String {
        val messages = buildMessages(changes)
        val raw = client.complete(config, messages, isCanceled)
        return sanitizeOrFail(raw)
    }

    /**
     * 流式生成：每收到一段增量就回调 [onChunk]，返回清洗后的完整提交信息。
     */
    fun generateStreaming(
        config: LlmConfig,
        changes: StagedChanges,
        isCanceled: () -> Boolean = { false },
        onChunk: (String) -> Unit,
    ): String {
        val messages = buildMessages(changes)
        val raw = client.stream(config, messages, isCanceled, onChunk)
        return sanitizeOrFail(raw)
    }

    private fun buildMessages(changes: StagedChanges): List<ChatMessage> {
        if (changes.isEmpty) {
            throw LlmException("当前没有可生成的变更，请先暂存或修改代码。")
        }
        return listOf(
            ChatMessage(role = "system", content = PromptBuilder.SYSTEM_PROMPT),
            ChatMessage(role = "user", content = PromptBuilder.buildUserPrompt(changes)),
        )
    }

    private fun sanitizeOrFail(raw: String): String {
        val message = CommitMessageSanitizer.sanitize(raw)
        if (message.isBlank()) {
            throw LlmException("模型返回内容为空，请重试或更换模型。")
        }
        return message
    }
}
