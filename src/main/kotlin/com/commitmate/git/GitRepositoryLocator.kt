package com.commitmate.git

import java.nio.file.Files
import java.nio.file.Path

/**
 * Git 仓库定位：从若干候选目录向上查找首个包含 `.git` 的目录。
 *
 * 不假设 `project.basePath` 就是仓库根目录，也支持一个 IDEA 项目包含多个 Git Root。
 * 纯 Kotlin 实现，不依赖 IntelliJ Platform，便于单元测试。
 */
object GitRepositoryLocator {

    private const val GIT_ENTRY = ".git"
    private const val MAX_DEPTH = 64

    /** 目录本身是否是一个 Git 仓库根目录（`.git` 既可能是目录，也可能是 submodule/worktree 的文件）。 */
    fun isRepositoryRoot(directory: Path): Boolean {
        val gitEntry = directory.resolve(GIT_ENTRY)
        return Files.isDirectory(gitEntry) || Files.isRegularFile(gitEntry)
    }

    /** 从 [start] 开始向上查找仓库根目录，找不到返回 null。 */
    fun findRepositoryRoot(start: Path): Path? {
        var current: Path? = start.toAbsolutePath().normalize()
        if (!Files.isDirectory(current)) {
            current = current?.parent
        }
        var depth = 0
        while (current != null && depth++ < MAX_DEPTH) {
            if (isRepositoryRoot(current)) return current
            current = current.parent
        }
        return null
    }

    /** 对一组候选目录去重后定位仓库根目录，结果按路径排序以保证顺序稳定。 */
    fun findRepositoryRoots(candidates: Iterable<Path>): List<Path> {
        val roots = LinkedHashSet<Path>()
        for (candidate in candidates) {
            val directory = candidate.toAbsolutePath().normalize()
            if (!Files.isDirectory(directory)) continue
            findRepositoryRoot(directory)?.let { roots.add(it) }
        }
        return roots.sortedBy { it.toString() }
    }
}
