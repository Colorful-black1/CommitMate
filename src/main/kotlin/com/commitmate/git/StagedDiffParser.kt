package com.commitmate.git

import com.commitmate.model.ChangeType
import com.commitmate.model.StagedFileChange

/**
 * 解析 `git diff --cached --name-status -z` 的输出。
 *
 * `-z` 使用 NUL 作为分隔符，每条记录形如：
 * - 普通变更：`M` `path`
 * - 重命名/复制：`R100` `oldPath` `newPath`
 *
 * 使用 NUL 分隔可以避免文件名中的空格、引号、中文路径带来的解析歧义。
 */
object StagedDiffParser {

    private const val NUL = '\u0000'

    fun parseNameStatusZ(output: String): List<StagedFileChange> {
        if (output.isBlank()) return emptyList()

        val tokens = output.split(NUL)
        val changes = ArrayList<StagedFileChange>()
        var index = 0
        while (index < tokens.size) {
            val rawStatus = tokens[index].trim()
            if (rawStatus.isEmpty()) {
                index++
                continue
            }
            val type = ChangeType.fromCode(rawStatus)
            when (type) {
                ChangeType.RENAMED, ChangeType.COPIED -> {
                    val oldPath = tokens.getOrNull(index + 1) ?: break
                    val newPath = tokens.getOrNull(index + 2) ?: break
                    changes.add(StagedFileChange(type = type, path = newPath, oldPath = oldPath))
                    index += 3
                }

                else -> {
                    val path = tokens.getOrNull(index + 1) ?: break
                    changes.add(StagedFileChange(type = type, path = path))
                    index += 2
                }
            }
        }
        return changes
    }
}
