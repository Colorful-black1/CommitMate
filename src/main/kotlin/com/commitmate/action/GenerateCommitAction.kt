package com.commitmate.action

import com.commitmate.ui.CommitMateDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vcs.VcsDataKeys

/**
 * Commit Mate 对话框入口。
 *
 * 出现位置：Tools 菜单、VCS 菜单、`Ctrl+Alt+G`，以及 Action 搜索。
 * 只负责打开对话框，不执行任何 Git 写操作。
 *
 * 提交面板里的一键生成走 [GenerateInCommitPanelAction]，不会弹窗。
 */
class GenerateCommitAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.dataContext.getData(CommonDataKeys.PROJECT) ?: return
        if (project.isDisposed) return
        // 在提交面板上下文中打开时，对话框会多出"填入提交框"按钮
        val commitMessageControl = event.dataContext.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        CommitMateDialog(project, commitMessageControl).show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
