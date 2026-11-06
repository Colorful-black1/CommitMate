package com.commitmate.git

/**
 * 按文件过滤 unified diff 文本。
 *
 * `git diff` 的输出以 `diff --git a/xxx b/xxx` 分块；这里按仓库相对路径保留需要的块，
 * 用于"只对提交面板中勾选的文件生成提交信息"。
 */
object DiffSectionFilter {

    private const val SECTION_PREFIX = "diff --git "

    fun filter(diff: String, relativePaths: Set<String>): String {
        if (diff.isEmpty() || relativePaths.isEmpty()) return diff

        val wanted = relativePaths.map { it.replace('\\', '/') }.toSet()
        val lines = diff.split('\n')
        val firstSection = lines.indexOfFirst { it.startsWith(SECTION_PREFIX) }
        if (firstSection < 0) return diff

        val builder = StringBuilder()
        if (firstSection > 0) {
            val preamble = lines.subList(0, firstSection).joinToString("\n")
            if (preamble.isNotBlank()) builder.append(preamble).append('\n')
        }

        var current: StringBuilder? = null
        for (index in firstSection until lines.size) {
            val line = lines[index]
            if (line.startsWith(SECTION_PREFIX)) {
                current?.takeIf { matches(it, wanted) }?.let { builder.append(it).append('\n') }
                current = StringBuilder(line)
            } else {
                current?.append('\n')?.append(line)
            }
        }
        current?.takeIf { matches(it, wanted) }?.let { builder.append(it).append('\n') }
        return builder.toString()
    }

    private fun matches(section: StringBuilder, wanted: Set<String>): Boolean {
        val header = section.lineSequence().firstOrNull()?.removePrefix(SECTION_PREFIX) ?: return false
        val separator = header.lastIndexOf(" b/")
        if (separator <= 2) return false
        val left = header.substring(2, separator).trim('"')
        val right = header.substring(separator + 3).trim('"')
        return left in wanted || right in wanted
    }
}
