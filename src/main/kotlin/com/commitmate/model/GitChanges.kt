package com.commitmate.model

/** 变更来源：暂存区，或工作区（未暂存）的修改。 */
enum class ChangeSource {
    /** `git diff --cached`：用户已经 `git add` 的内容。 */
    STAGED,

    /** `git diff`：工作区里尚未暂存的修改（仅在暂存区为空时使用）。 */
    WORKING_TREE,
}

/** Git 文件变更类型，取值对齐 `git diff --name-status` 的状态字符。 */
enum class ChangeType(
    private val code: Char,
    val displayName: String,
) {
    ADDED('A', "新增"),
    MODIFIED('M', "修改"),
    DELETED('D', "删除"),
    RENAMED('R', "重命名"),
    COPIED('C', "复制"),
    TYPE_CHANGED('T', "类型变更"),
    UNMERGED('U', "未合并"),
    UNKNOWN('?', "未知"),
    ;

    companion object {
        fun fromCode(code: String): ChangeType {
            val first = code.firstOrNull()?.uppercaseChar() ?: return UNKNOWN
            return entries.firstOrNull { it.code == first } ?: UNKNOWN
        }
    }
}

/** 单个文件的变更信息。 */
data class StagedFileChange(
    val type: ChangeType,
    val path: String,
    val oldPath: String? = null,
) {
    /** 用于展示的路径，重命名时显示为 `old → new`。 */
    val displayPath: String
        get() = if (!oldPath.isNullOrBlank() && oldPath != path) "$oldPath → $path" else path
}

/**
 * 经过长度控制后的 Diff 内容。
 *
 * [truncated] 为 true 时，UI 与 Prompt 都必须明确告知用户 / 模型“内容不完整”，
 * 不允许静默截断。
 */
data class StagedDiff(
    val text: String,
    val truncated: Boolean = false,
    val originalCharacters: Int = text.length,
    val includedCharacters: Int = text.length,
    val omittedFiles: List<String> = emptyList(),
    val omittedLines: Int = 0,
) {
    companion object {
        val EMPTY = StagedDiff(text = "")
    }
}

/** 一次变更读取的完整结果。 */
data class StagedChanges(
    val repositoryRoot: String,
    val files: List<StagedFileChange> = emptyList(),
    val diff: StagedDiff = StagedDiff.EMPTY,
    val source: ChangeSource = ChangeSource.STAGED,
) {
    val isEmpty: Boolean
        get() = files.isEmpty()

    val isWorkingTree: Boolean
        get() = source == ChangeSource.WORKING_TREE

    val addedCount: Int
        get() = files.count { it.type == ChangeType.ADDED }

    val modifiedCount: Int
        get() = files.count { it.type == ChangeType.MODIFIED }

    val deletedCount: Int
        get() = files.count { it.type == ChangeType.DELETED }

    val renamedCount: Int
        get() = files.count { it.type == ChangeType.RENAMED }

    /** 仓库目录名，用于 Prompt 与 UI 展示。 */
    val repositoryName: String
        get() = repositoryRoot.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
}
