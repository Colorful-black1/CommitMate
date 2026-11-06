package com.commitmate.ui

import com.commitmate.model.StagedChanges
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionListener
import javax.swing.JComponent
import javax.swing.JPanel

/** 只读的暂存区 Diff 预览窗口。 */
class DiffPreviewDialog(
    project: Project,
    private val changes: StagedChanges,
) : DialogWrapper(project) {

    init {
        title = "Commit Mate - 暂存区 Diff"
        setOKButtonText("关闭")
        init()
    }

    override fun createCancelAction(): ActionListener? = null

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.border = JBUI.Borders.empty(12)
        installEscapeToClose(root) { doCancelAction() }

        root.add(JBLabel(buildHeader()), BorderLayout.NORTH)

        val textArea = JBTextArea(changes.diff.text)
        textArea.isEditable = false
        textArea.lineWrap = false
        textArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        textArea.caretPosition = 0

        val scrollPane = JBScrollPane(textArea)
        scrollPane.preferredSize = Dimension(960, 600)
        root.add(scrollPane, BorderLayout.CENTER)

        return root
    }

    private fun buildHeader(): String {
        val diff = changes.diff
        val builder = StringBuilder()
        builder.append("仓库：").append(changes.repositoryRoot)
        builder.append("    已暂存 ").append(changes.files.size).append(" 个文件")
        if (changes.isWorkingTree) {
            builder.append("\n注意：暂存区为空，这里展示的是工作区中尚未暂存的修改。")
        }
        if (diff.truncated) {
            builder.append("\n")
            builder.append("注意：Diff 已截断，原始 ")
            builder.append(diff.originalCharacters)
            builder.append(" 字符，当前展示 ")
            builder.append(diff.includedCharacters)
            builder.append(" 字符")
            if (diff.omittedFiles.isNotEmpty()) {
                builder.append("，省略 ").append(diff.omittedFiles.size).append(" 个文件")
            }
            builder.append("。")
        }
        return builder.toString()
    }
}
