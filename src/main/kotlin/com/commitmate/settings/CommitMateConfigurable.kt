package com.commitmate.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** Settings → Tools → Commit Mate 配置页。 */
class CommitMateConfigurable : Configurable {

    private val settings: CommitMateSettings = service()

    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val temperatureSpinner = JSpinner(SpinnerNumberModel(0.2, 0.0, 2.0, 0.05))
    private val maxDiffField = JBTextField()
    private val timeoutField = JBTextField()
    private val workingTreeCheckBox = JBCheckBox("暂存区为空时，改用工作区未提交的变更")

    private val container = JPanel()

    override fun getDisplayName(): String = "Commit Mate"

    override fun createComponent(): JComponent {
        baseUrlField.columns = COLUMNS
        modelField.columns = COLUMNS
        apiKeyField.columns = COLUMNS
        maxDiffField.columns = COLUMNS
        timeoutField.columns = COLUMNS
        timeoutField.toolTipText = "单次模型请求的读取超时时间（秒）"

        val hint = JBLabel("API Key 通过 IntelliJ PasswordSafe 保存；留空表示清除已保存的 Key。")
        hint.foreground = JBColor.GRAY

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("API Base URL:", baseUrlField)
            .addLabeledComponent("Model:", modelField)
            .addLabeledComponent("API Key:", apiKeyField)
            .addLabeledComponent("Temperature:", temperatureSpinner)
            .addLabeledComponent("最大 Diff 字符数:", maxDiffField)
            .addLabeledComponent("请求超时（秒）:", timeoutField)
            .addComponent(workingTreeCheckBox)
            .addComponent(JBLabel("关闭后只读取 git 暂存区（git add 过的内容）。").apply { foreground = JBColor.GRAY })
            .addComponent(hint)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        container.layout = java.awt.BorderLayout()
        container.border = JBUI.Borders.empty(8)
        container.add(form, java.awt.BorderLayout.CENTER)

        reset()
        return container
    }

    override fun isModified(): Boolean {
        val apiKey = getStoredApiKey().orEmpty()
        return baseUrlField.text.trim() != settings.baseUrl ||
            modelField.text.trim() != settings.model ||
            String(apiKeyField.password) != apiKey ||
            (temperatureSpinner.value as Number).toDouble() != settings.temperature ||
            maxDiffField.text.trim().toIntOrNull() != settings.maxDiffCharacters ||
            timeoutField.text.trim().toIntOrNull() != settings.timeoutSeconds ||
            workingTreeCheckBox.isSelected != settings.useWorkingTreeChanges
    }

    override fun apply() {
        settings.baseUrl = baseUrlField.text
        settings.model = modelField.text
        settings.temperature = (temperatureSpinner.value as Number).toDouble()
        maxDiffField.text.trim().toIntOrNull()?.let { settings.maxDiffCharacters = it }
        timeoutField.text.trim().toIntOrNull()?.let { settings.timeoutSeconds = it }
        settings.useWorkingTreeChanges = workingTreeCheckBox.isSelected
        settings.setApiKey(String(apiKeyField.password))
    }

    override fun reset() {
        baseUrlField.text = settings.baseUrl
        modelField.text = settings.model
        apiKeyField.text = getStoredApiKey().orEmpty()
        temperatureSpinner.value = settings.temperature
        maxDiffField.text = settings.maxDiffCharacters.toString()
        timeoutField.text = settings.timeoutSeconds.toString()
        workingTreeCheckBox.isSelected = settings.useWorkingTreeChanges
    }

    override fun disposeUIResources() {
        // 组件由 Swing 自行回收，无需额外处理
    }

    private fun getStoredApiKey(): String? = settings.getApiKey()

    private companion object {
        const val COLUMNS = 42
    }
}
