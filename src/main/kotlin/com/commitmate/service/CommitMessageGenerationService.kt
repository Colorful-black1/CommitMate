package com.commitmate.service

import com.commitmate.core.OperationCanceledException
import com.commitmate.git.GitDiffService
import com.commitmate.llm.CommitMessageSanitizer
import com.commitmate.llm.LlmConfig
import com.commitmate.llm.LlmException
import com.commitmate.settings.CommitMateSettings
import com.commitmate.util.CommitMateNotifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CommitMessageI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 提交面板内的一键生成：**不弹窗**，直接把提交信息流式写入 IDE 的提交框。
 *
 * 只处理提交面板中勾选的文件（[toggle] 的 includedPaths）；如果没有勾选信息则回退到整个仓库。
 * 再点一次按钮可取消正在进行的生成；取消或失败时恢复原有提交信息。
 */
@Service(Service.Level.PROJECT)
class CommitMessageGenerationService(private val project: Project) {

    private val settings: CommitMateSettings = service()
    private val gitDiffService: GitDiffService = project.service()
    private val commitMessageService = CommitMessageService()
    private val lock = Any()

    private var current: Generation? = null

    fun isGenerating(): Boolean = synchronized(lock) { current?.finished == false }

    fun toggle(
        control: CommitMessageI,
        document: Document?,
        includedPaths: Set<Path>,
        repositories: List<Path>,
    ) {
        val running = synchronized(lock) { current?.takeIf { !it.finished } }
        if (running != null) {
            running.canceled.set(true)
            return
        }

        val generation = Generation()
        synchronized(lock) { current = generation }
        generation.thread = Thread(
            { run(generation, control, document, includedPaths, repositories) },
            "commit-mate-generate",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun run(
        generation: Generation,
        control: CommitMessageI,
        document: Document?,
        includedPaths: Set<Path>,
        repositories: List<Path>,
    ) {
        val originalText = readDocumentText(document)
        try {
            val repository = pickRepository(repositories, includedPaths)
            val changes = repository?.let {
                gitDiffService.readStagedChanges(
                    repositoryRoot = it,
                    maxDiffCharacters = settings.maxDiffCharacters,
                    allowWorkingTreeFallback = settings.useWorkingTreeChanges,
                    includePaths = includedPaths,
                )
            }
            if (changes == null || changes.isEmpty) {
                CommitMateNotifications.warning(
                    project,
                    "没有可生成的变更：请在提交面板勾选文件，或先修改代码。",
                )
                return
            }

            val apiKey = settings.getApiKey()
            if (apiKey.isNullOrBlank()) {
                CommitMateNotifications.warning(project, "请先在 Settings → Tools → Commit Mate 配置 API Key。")
                return
            }
            val config = settings.toLlmConfig(apiKey)

            updateCommitMessage(control, GENERATING_PLACEHOLDER)

            val accumulator = StringBuilder()
            var lastPush = 0L
            val message = commitMessageService.generateStreaming(
                config = config,
                changes = changes,
                isCanceled = { generation.canceled.get() },
            ) { chunk ->
                accumulator.append(chunk)
                val now = System.currentTimeMillis()
                if (now - lastPush >= STREAM_UPDATE_INTERVAL_MILLIS) {
                    lastPush = now
                    updateCommitMessage(control, CommitMessageSanitizer.sanitize(accumulator.toString()))
                }
            }

            updateCommitMessage(control, message)
            CommitMateNotifications.info(project, "已生成提交信息（${changes.files.size} 个文件），请确认后自行提交。")
        } catch (_: OperationCanceledException) {
            restore(control, originalText)
            CommitMateNotifications.info(project, "已取消生成。")
        } catch (e: Throwable) {
            restore(control, originalText)
            val description = (e as? LlmException)?.message ?: e.message ?: "生成失败"
            CommitMateNotifications.error(project, description)
        } finally {
            generation.finished = true
        }
    }

    private fun pickRepository(repositories: List<Path>, includedPaths: Set<Path>): Path? {
        if (repositories.isEmpty()) return null
        if (includedPaths.isEmpty()) return repositories.first()
        return repositories.firstOrNull { root -> includedPaths.any { it.startsWith(root) } }
            ?: repositories.first()
    }

    private fun restore(control: CommitMessageI, originalText: String?) {
        updateCommitMessage(control, originalText.orEmpty())
    }

    private fun readDocumentText(document: Document?): String? {
        if (document == null) return null
        var text: String? = null
        ApplicationManager.getApplication().invokeAndWait { text = document.text }
        return text
    }

    private fun updateCommitMessage(control: CommitMessageI, text: String) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            control.setCommitMessage(text)
        } else {
            application.invokeLater { control.setCommitMessage(text) }
        }
    }

    private inner class Generation {
        val canceled = AtomicBoolean(false)

        @Volatile
        var finished = false

        var thread: Thread? = null
    }

    companion object {
        private const val GENERATING_PLACEHOLDER = "正在生成提交信息…"
        private const val STREAM_UPDATE_INTERVAL_MILLIS = 80L
    }
}
