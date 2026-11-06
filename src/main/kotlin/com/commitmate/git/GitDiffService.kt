package com.commitmate.git

import com.commitmate.model.StagedChanges
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.Path

/**
 * 项目级 Git 服务：定位项目中的 Git 仓库，并读取指定仓库的暂存区变更。
 *
 * 仅使用 IntelliJ Platform 通用 API（[ProjectRootManager]、[Project]），不依赖 Git 插件、
 * 也不依赖任何语言模块，因此可以在所有 JetBrains IDE 中使用。
 */
@Service(Service.Level.PROJECT)
class GitDiffService(private val project: Project) {

    private val reader = StagedChangesReader()

    /** 当前项目中所有可用的 Git 仓库根目录（去重、排序）。 */
    fun findRepositories(): List<Path> {
        val candidates = LinkedHashSet<Path>()
        project.basePath?.let { candidates.add(Path.of(it)) }
        project.guessProjectDir()?.path?.let { candidates.add(Path.of(it)) }
        for (contentRoot in ProjectRootManager.getInstance(project).contentRoots) {
            candidates.add(Path.of(contentRoot.path))
        }
        return GitRepositoryLocator.findRepositoryRoots(candidates)
    }

    /**
     * 读取 [repositoryRoot] 的待提交变更，[maxDiffCharacters] 为 Diff 最大字符数。
     *
     * 优先暂存区；[allowWorkingTreeFallback] 为 true 且暂存区为空时，改用工作区未提交的修改。
     * [includePaths] 非空时只保留这些文件（用于只处理提交面板里勾选的文件）。
     */
    fun readStagedChanges(
        repositoryRoot: Path,
        maxDiffCharacters: Int,
        allowWorkingTreeFallback: Boolean = true,
        includePaths: Set<Path> = emptySet(),
    ): StagedChanges = reader.read(repositoryRoot, maxDiffCharacters, allowWorkingTreeFallback, includePaths)

    companion object {
        fun getInstance(project: Project): GitDiffService = project.service()
    }
}
