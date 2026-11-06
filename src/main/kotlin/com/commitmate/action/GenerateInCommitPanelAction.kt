package com.commitmate.action

import com.commitmate.git.GitDiffService
import com.commitmate.service.CommitMessageGenerationService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import java.nio.file.Path

/**
 * 提交面板工具栏里的按钮：
 *
 * - 不打开对话框，直接把提交信息**流式写入** IDE 的提交框；
 * - 只处理提交面板中**勾选**的文件；
 * - 再点一次可取消正在进行的生成。
 *
 * 仍然只读取变更、不执行任何 Git 写操作；是否提交由用户自己决定。
 */
class GenerateInCommitPanelAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.dataContext.getData(CommonDataKeys.PROJECT) ?: return
        if (project.isDisposed) return

        val control = event.dataContext.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val document = event.dataContext.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT)
        val workflowUi = event.dataContext.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)

        val includedChanges: List<Change> = workflowUi?.getIncludedChanges().orEmpty()
        val includedPaths: Set<Path> = includedChanges
            .mapNotNull { change ->
                val revision = change.afterRevision ?: change.beforeRevision
                revision?.file?.path?.let { path -> Path.of(path) }
            }
            .toSet()

        val repositories = project.service<GitDiffService>().findRepositories()

        project.service<CommitMessageGenerationService>()
            .toggle(control, document, includedPaths, repositories)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
