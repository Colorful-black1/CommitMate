package com.commitmate.git

import com.commitmate.model.ChangeSource
import com.commitmate.model.StagedChanges
import java.nio.file.Path

/**
 * 读取某个 Git 仓库中待提交的变更。
 *
 * 优先读取暂存区（`git diff --cached`）；当暂存区为空且允许回退时，再读取工作区中
 * 尚未暂存的修改（`git diff`）。
 *
 * 这样兼容两种使用习惯：
 * - 在终端 / IDE 里先 `git add` 再生成；
 * - 直接用 IDEA 新版提交面板（它不会提前 `git add`，只有点 Commit 时才写索引）。
 *
 * 只读：只执行 `git diff`，不会执行 add / commit / reset / checkout；
 * 也不会读取未跟踪文件（`git diff` 本身不包含 untracked 文件）。
 */
class StagedChangesReader(
    private val runner: GitCommandRunner = GitCommandRunner(),
) {

    fun read(
        repositoryRoot: Path,
        maxDiffCharacters: Int,
        allowWorkingTreeFallback: Boolean = true,
        includePaths: Set<Path> = emptySet(),
    ): StagedChanges {
        val staged = readFrom(repositoryRoot, maxDiffCharacters, ChangeSource.STAGED, includePaths)
        if (!staged.isEmpty || !allowWorkingTreeFallback) {
            return staged
        }
        return readFrom(repositoryRoot, maxDiffCharacters, ChangeSource.WORKING_TREE, includePaths)
    }

    private fun readFrom(
        repositoryRoot: Path,
        maxDiffCharacters: Int,
        source: ChangeSource,
        includePaths: Set<Path>,
    ): StagedChanges {
        val workingDirectory = repositoryRoot.toFile()
        val scopeArgs = if (source == ChangeSource.STAGED) listOf("--cached") else emptyList()
        val relativeFilter = toRelativePaths(repositoryRoot, includePaths)

        val nameStatus = runner.run(
            workingDirectory = workingDirectory,
            args = listOf("diff") + scopeArgs + listOf(
                "--name-status", "--find-renames", "-z", "--no-ext-diff", "--no-color",
            ),
        )
        ensureSuccess(nameStatus, "读取变更文件列表")

        val allFiles = StagedDiffParser.parseNameStatusZ(nameStatus.stdoutText)
        val files = if (relativeFilter.isEmpty()) {
            allFiles
        } else {
            allFiles.filter { it.path in relativeFilter || it.oldPath in relativeFilter }
        }
        if (files.isEmpty()) {
            return StagedChanges(repositoryRoot = repositoryRoot.toString(), source = source)
        }

        val diffResult = runner.run(
            workingDirectory = workingDirectory,
            args = listOf("diff") + scopeArgs + listOf(
                "--no-ext-diff", "--unified=3", "--no-color", "--find-renames",
            ),
        )
        ensureSuccess(diffResult, "读取 Diff")

        val rawDiff = if (relativeFilter.isEmpty()) {
            diffResult.stdoutText
        } else {
            DiffSectionFilter.filter(diffResult.stdoutText, relativeFilter)
        }
        val compacted = DiffCompactor.compact(rawDiff, maxDiffCharacters)
        val diff = if (diffResult.outputLimitExceeded) {
            compacted.copy(
                truncated = true,
                originalCharacters = maxOf(compacted.originalCharacters, rawDiff.length),
                includedCharacters = compacted.text.length,
                omittedLines = maxOf(
                    compacted.omittedLines,
                    rawDiff.count { it == '\n' } - compacted.text.count { it == '\n' },
                ),
            )
        } else {
            compacted
        }

        return StagedChanges(
            repositoryRoot = repositoryRoot.toString(),
            files = files,
            diff = diff,
            source = source,
        )
    }

    /** 把绝对路径集合换算成当前仓库的相对路径（POSIX 分隔符）。 */
    private fun toRelativePaths(repositoryRoot: Path, includePaths: Set<Path>): Set<String> {
        if (includePaths.isEmpty()) return emptySet()
        val root = repositoryRoot.toAbsolutePath().normalize()
        val result = HashSet<String>(includePaths.size)
        for (path in includePaths) {
            val absolute = path.toAbsolutePath().normalize()
            if (!absolute.startsWith(root) || absolute == root) continue
            val relative = root.relativize(absolute).toString().replace('\\', '/')
            if (relative.isNotEmpty()) result.add(relative)
        }
        return result
    }

    private fun ensureSuccess(result: GitCommandResult, action: String) {
        if (result.exitCode == 0) return
        val detail = result.stderr.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val message = if (detail.isEmpty()) {
            "$action 失败（git 退出码 ${result.exitCode}）"
        } else {
            "$action 失败（git 退出码 ${result.exitCode}）：$detail"
        }
        throw GitCommandException(message, result.exitCode)
    }
}
