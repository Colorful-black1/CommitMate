package com.commitmate.ui

import com.commitmate.core.OperationCanceledException
import com.commitmate.git.GitDiffService
import com.commitmate.llm.ExtraHeaders
import com.commitmate.llm.LlmConfig
import com.commitmate.llm.LlmException
import com.commitmate.model.StagedChanges
import com.commitmate.service.CommitMessageService
import com.commitmate.settings.CommitMateSettings
import com.commitmate.util.CommitMateNotifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionListener
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Commit Mate 主窗口。
 *
 * 只负责展示与交互：
 * - 顶部：模型配置；
 * - 中部：Git 仓库、变更统计、查看 Diff；
 * - 底部：生成 / 重新生成 / 复制 / 填入提交框，以及可自由编辑的 Commit Message 文本区。
 *
 * [commitMessageControl] 非空时（从提交面板工具栏打开），可以一键把结果写回 IDE 的提交框。
 * 所有耗时操作（读取 Git、请求模型）都在后台线程执行，网络请求可取消。
 */
class CommitMateDialog(
    private val project: Project,
    private val commitMessageControl: CommitMessageI? = null,
) : DialogWrapper(project) {

    private val gitDiffService: GitDiffService = project.service()
    private val settings: CommitMateSettings = service()
    private val commitMessageService = CommitMessageService()

    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val apiKeyField = JBPasswordField()

    private val repositorySelector = JComboBox<Path>()
    private val repositoryPathLabel = JBLabel()
    private val summaryLabel = JBLabel()
    private val messageArea = JBTextArea(12, 72)
    private val statusLabel = JBLabel()

    private val generateButton = JButton("Generate")
    private val regenerateButton = JButton("Regenerate")
    private val copyButton = JButton("Copy")
    private val fillCommitButton = JButton("填入提交框")
    private val viewDiffButton = JButton("查看 Diff")
    private val refreshButton = JButton("刷新")

    private var repositories: List<Path> = emptyList()
    private var currentChanges: StagedChanges? = null
    private var loading = false
    private var generating = false
    private var generatedOnce = false
    private var suppressSelectorEvents = false
    private var disposed = false

    init {
        title = "Commit Mate"
        setOKButtonText("关闭")
        init()
        installListeners()
        loadSettingsIntoUi()
        updateControls()
        reloadRepositories()
    }

    /** 不显示多余的 Cancel 按钮，关闭统一走底部“关闭”按钮或 Esc。 */
    override fun createCancelAction(): ActionListener? = null

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 12))
        root.border = JBUI.Borders.empty(12)
        root.add(createConfigurationPanel(), BorderLayout.NORTH)
        root.add(createMainPanel(), BorderLayout.CENTER)
        root.preferredSize = Dimension(880, 660)
        installEscapeToClose(root) { doCancelAction() }
        return root
    }

    override fun dispose() {
        disposed = true
        super.dispose()
    }

    private fun createConfigurationPanel(): JComponent {
        baseUrlField.columns = COLUMNS
        modelField.columns = COLUMNS
        apiKeyField.columns = COLUMNS

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("API Base URL:", baseUrlField)
            .addLabeledComponent("Model:", modelField)
            .addLabeledComponent("API Key:", apiKeyField)
            .panel

        val hint = JBLabel(
            "API Key 留空则使用已保存的 Key；网关额外的请求头（如 X-Head-Session）在 Settings → Tools → Commit Mate 中配置。",
        )
        hint.foreground = JBColor.GRAY

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(form, BorderLayout.CENTER)
        panel.add(hint, BorderLayout.SOUTH)
        return panel
    }

    private fun createMainPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))

        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)

        val repositoryRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        repositoryRow.add(JBLabel("Git 仓库:"))
        repositorySelector.renderer = RepositoryRenderer()
        repositorySelector.preferredSize = Dimension(260, 26)
        repositoryRow.add(repositorySelector)
        repositoryRow.add(refreshButton)
        repositoryRow.add(viewDiffButton)
        repositoryRow.alignmentX = Component.LEFT_ALIGNMENT
        infoPanel.add(repositoryRow)

        repositoryPathLabel.foreground = JBColor.GRAY
        repositoryPathLabel.alignmentX = Component.LEFT_ALIGNMENT
        infoPanel.add(repositoryPathLabel)

        summaryLabel.alignmentX = Component.LEFT_ALIGNMENT
        infoPanel.add(summaryLabel)

        panel.add(infoPanel, BorderLayout.NORTH)

        messageArea.lineWrap = true
        messageArea.wrapStyleWord = true
        val messageScroll = JBScrollPane(messageArea)
        messageScroll.preferredSize = Dimension(640, 320)

        val messagePanel = JPanel(BorderLayout(0, 4))
        messagePanel.add(JBLabel("Commit Message（可直接编辑）:"), BorderLayout.NORTH)
        messagePanel.add(messageScroll, BorderLayout.CENTER)
        panel.add(messagePanel, BorderLayout.CENTER)

        statusLabel.text = " "
        val statusRow = JPanel(BorderLayout())
        statusRow.add(statusLabel, BorderLayout.WEST)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        buttons.add(generateButton)
        buttons.add(regenerateButton)
        buttons.add(copyButton)
        fillCommitButton.isVisible = commitMessageControl != null
        buttons.add(fillCommitButton)
        statusRow.add(buttons, BorderLayout.EAST)

        panel.add(statusRow, BorderLayout.SOUTH)
        return panel
    }

    private fun installListeners() {
        generateButton.addActionListener { generate() }
        regenerateButton.addActionListener { generate() }
        copyButton.addActionListener { copyMessage() }
        fillCommitButton.addActionListener { fillCommitMessage() }
        viewDiffButton.addActionListener { openDiffPreview() }
        refreshButton.addActionListener { reloadRepositories() }

        repositorySelector.addActionListener {
            if (suppressSelectorEvents) return@addActionListener
            val selected = repositorySelector.selectedItem as? Path ?: return@addActionListener
            loadStagedChanges(selected)
        }

        messageArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent?) = updateControls()

            override fun removeUpdate(event: DocumentEvent?) = updateControls()

            override fun changedUpdate(event: DocumentEvent?) = updateControls()
        })
    }

    private fun loadSettingsIntoUi() {
        baseUrlField.text = settings.baseUrl
        modelField.text = settings.model
        apiKeyField.text = ""
    }

    private fun reloadRepositories() {
        if (loading) return
        loading = true
        setStatus("正在读取 Git 仓库…")
        updateControls()

        // 仓库定位只做少量文件系统探测，直接在 EDT 完成；真正的 git diff 才放到后台线程。
        val result = runCatching { gitDiffService.findRepositories() }
        loading = false
        result.onSuccess { applyRepositories(it) }.onFailure { showFailure(it) }
    }

    private fun applyRepositories(found: List<Path>) {
        repositories = found
        suppressSelectorEvents = true
        repositorySelector.removeAllItems()
        for (repository in found) {
            repositorySelector.addItem(repository)
        }
        suppressSelectorEvents = false

        if (found.isEmpty()) {
            currentChanges = null
            repositoryPathLabel.text = "未在当前项目中找到 Git 仓库"
            summaryLabel.text = ""
            summaryLabel.foreground = UIUtil.getLabelForeground()
            setStatus("未找到 Git 仓库，请确认当前项目已使用 Git 管理。", isError = true)
            updateControls()
            return
        }

        suppressSelectorEvents = true
        repositorySelector.selectedIndex = 0
        suppressSelectorEvents = false
        loadStagedChanges(found.first())
    }

    private fun loadStagedChanges(repositoryRoot: Path) {
        if (loading) return
        loading = true
        currentChanges = null
        repositoryPathLabel.text = repositoryRoot.toString()
        repositoryPathLabel.toolTipText = repositoryRoot.toString()
        summaryLabel.text = "正在读取暂存区…"
        summaryLabel.foreground = UIUtil.getLabelForeground()
        setStatus("正在读取暂存区…")
        updateControls()

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                gitDiffService.readStagedChanges(
                    repositoryRoot = repositoryRoot,
                    maxDiffCharacters = settings.maxDiffCharacters,
                    allowWorkingTreeFallback = settings.useWorkingTreeChanges,
                )
            }
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                loading = false
                result.onSuccess { applyChanges(it) }.onFailure { showFailure(it) }
            }
        }
    }

    private fun applyChanges(changes: StagedChanges) {
        currentChanges = changes
        if (changes.isEmpty) {
            summaryLabel.text = if (settings.useWorkingTreeChanges) {
                "当前没有已暂存的代码变更，也没有未提交的工作区修改。"
            } else {
                "当前没有已暂存的代码变更，请先执行 Git Add。"
            }
            summaryLabel.foreground = JBColor.ORANGE
            setStatus("")
        } else {
            summaryLabel.text = buildSummary(changes)
            summaryLabel.foreground = UIUtil.getLabelForeground()
            when {
                changes.diff.truncated ->
                    setStatus("Diff 较长，已按上限截断（原始 ${changes.diff.originalCharacters} 字符）。")

                changes.isWorkingTree ->
                    setStatus("暂存区为空，已改用工作区未提交的 ${changes.files.size} 个文件。")

                else -> setStatus("已读取 ${changes.files.size} 个暂存文件。")
            }
        }
        updateControls()
    }

    private fun buildSummary(changes: StagedChanges): String {
        val counts = "新增 ${changes.addedCount}，修改 ${changes.modifiedCount}，" +
            "删除 ${changes.deletedCount}，重命名 ${changes.renamedCount}"
        return if (changes.isWorkingTree) {
            "工作区未提交 ${changes.files.size} 个文件（$counts）· 未暂存"
        } else {
            "已暂存 ${changes.files.size} 个文件（$counts）"
        }
    }

    private fun generate() {
        if (generating) return
        val changes = currentChanges
        if (changes == null || changes.isEmpty) {
            CommitMateNotifications.warning(
                project,
                if (settings.useWorkingTreeChanges) {
                    "当前没有可生成的变更，请先修改代码或执行 Git Add。"
                } else {
                    "当前没有已暂存的代码变更，请先执行 Git Add。"
                },
            )
            return
        }
        val config = readConfig() ?: return
        persistConfig(config)

        generating = true
        setStatus("正在生成 Commit Message…")
        updateControls()

        var generated: String? = null
        var failure: Throwable? = null
        var canceled = false

        val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously({
            try {
                val indicator = ProgressManager.getInstance().progressIndicator
                generated = commitMessageService.generate(config, changes) {
                    indicator?.isCanceled == true || Thread.currentThread().isInterrupted
                }
            } catch (_: OperationCanceledException) {
                canceled = true
            } catch (e: Throwable) {
                failure = e
            }
        }, "正在生成 Commit Message", true, project)

        generating = false
        val produced = generated
        val error = failure

        when {
            produced != null -> {
                messageArea.text = produced
                messageArea.caretPosition = 0
                generatedOnce = true
                setStatus("已生成，可自由编辑后复制。")
            }

            error != null -> {
                val description = (error as? LlmException)?.message
                    ?: error.message
                    ?: error.javaClass.simpleName
                setStatus("生成失败", isError = true)
                CommitMateNotifications.error(project, description)
            }

            canceled || !completed -> setStatus("已取消生成。")

            else -> setStatus("未获得生成结果，请重试。", isError = true)
        }

        updateControls()
        messageArea.requestFocusInWindow()
    }

    private fun readConfig(): LlmConfig? {
        val baseUrl = baseUrlField.text.trim()
        if (baseUrl.isBlank()) {
            CommitMateNotifications.warning(project, "请先填写 API Base URL。")
            return null
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            CommitMateNotifications.warning(project, "API Base URL 需要以 http:// 或 https:// 开头。")
            return null
        }

        val model = modelField.text.trim()
        if (model.isBlank()) {
            CommitMateNotifications.warning(project, "请先填写 Model 名称。")
            return null
        }

        val typedKey = String(apiKeyField.password).trim()
        val apiKey = typedKey.ifBlank { settings.getApiKey().orEmpty() }
        if (apiKey.isBlank()) {
            CommitMateNotifications.warning(project, "请填写 API Key，或先在 Settings → Tools → Commit Mate 中配置。")
            return null
        }

        return LlmConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            temperature = settings.temperature,
            readTimeoutMillis = settings.timeoutSeconds * 1_000,
            extraHeaders = ExtraHeaders.parse(settings.extraHeaders),
            sessionId = settings.stableSessionId(),
        )
    }

    private fun persistConfig(config: LlmConfig) {
        settings.baseUrl = config.baseUrl
        settings.model = config.model
        val typedKey = String(apiKeyField.password).trim()
        if (typedKey.isNotBlank()) {
            runCatching { settings.setApiKey(typedKey) }
                .onFailure { CommitMateNotifications.warning(project, "API Key 保存失败：${it.message}") }
            apiKeyField.text = ""
        }
    }

    private fun copyMessage() {
        val text = messageArea.text
        if (text.isBlank()) {
            CommitMateNotifications.warning(project, "还没有可复制的 Commit Message。")
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        CommitMateNotifications.info(project, "Commit Message 已复制到剪贴板。")
        setStatus("已复制到剪贴板。")
    }

    private fun openDiffPreview() {
        val changes = currentChanges ?: return
        DiffPreviewDialog(project, changes).show()
        updateControls()
    }

    /** 仅在从提交面板工具栏打开时可用：把结果写回 IDE 的提交信息框（不执行提交）。 */
    private fun fillCommitMessage() {
        val control = commitMessageControl ?: return
        val text = messageArea.text
        if (text.isBlank()) {
            CommitMateNotifications.warning(project, "还没有可填入的 Commit Message。")
            return
        }
        control.setCommitMessage(text)
        CommitMateNotifications.info(project, "已填入提交框，请确认后自行提交。")
        doOKAction()
    }

    private fun updateControls() {
        val hasChanges = currentChanges?.isEmpty == false
        val busy = loading || generating
        val hasMessage = messageArea.text.isNotBlank()
        generateButton.isEnabled = hasChanges && !busy
        regenerateButton.isEnabled = hasChanges && !busy && generatedOnce
        copyButton.isEnabled = hasMessage && !generating
        fillCommitButton.isEnabled = commitMessageControl != null && hasMessage && !generating
        viewDiffButton.isEnabled = currentChanges != null && !busy
        refreshButton.isEnabled = !busy
        repositorySelector.isEnabled = repositories.size > 1 && !busy
        baseUrlField.isEnabled = !generating
        modelField.isEnabled = !generating
        apiKeyField.isEnabled = !generating
    }

    private fun setStatus(text: String, isError: Boolean = false) {
        statusLabel.text = text.ifBlank { " " }
        statusLabel.foreground = if (isError) JBColor.RED else UIUtil.getLabelForeground()
    }

    private fun showFailure(error: Throwable) {
        setStatus(error.message ?: "操作失败", isError = true)
        updateControls()
    }

    private class RepositoryRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val path = value as? Path
            val text = path?.let { it.fileName?.toString() ?: it.toString() }.orEmpty()
            val component = super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            if (component is JLabel) {
                component.toolTipText = path?.toString()
            }
            return component
        }
    }

    private companion object {
        const val COLUMNS = 44
    }
}
