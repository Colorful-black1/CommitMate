package com.commitmate.git

import com.commitmate.model.StagedDiff

/**
 * Diff 长度控制。
 *
 * 策略：
 * 1. 未超限时原样返回，绝不改动内容；
 * 2. 超限时按 `diff --git` 切分为文件块，均分预算，优先完整保留“头信息 + 前若干行”；
 * 3. 无论是文件块被裁剪还是整体被硬截断，都会记录省略的行数与文件，并写入显式提示，
 *    保证 UI 与模型都知道“内容不完整”，不会静默截断。
 */
object DiffCompactor {

    private const val SECTION_PREFIX = "diff --git "
    private const val MIN_SECTION_BUDGET = 400
    private const val NOTICE_RESERVE = 160

    fun compact(diff: String, maxCharacters: Int): StagedDiff {
        require(maxCharacters > 0) { "maxCharacters 必须为正数" }

        val originalLength = diff.length
        if (originalLength <= maxCharacters) {
            return StagedDiff(
                text = diff,
                truncated = false,
                originalCharacters = originalLength,
                includedCharacters = originalLength,
            )
        }

        val sections = splitIntoSections(diff)
        if (sections.isEmpty()) {
            val text = hardCut(diff, maxCharacters)
            return StagedDiff(
                text = text,
                truncated = true,
                originalCharacters = originalLength,
                includedCharacters = text.length,
                omittedLines = diff.count { it == '\n' } - text.count { it == '\n' },
            )
        }

        val perSectionBudget = maxOf(MIN_SECTION_BUDGET, maxCharacters / sections.size)
        val builder = StringBuilder(maxCharacters + NOTICE_RESERVE)
        val omittedFiles = LinkedHashSet<String>()
        var omittedLines = 0

        for (section in sections) {
            val remaining = maxCharacters - builder.length
            if (remaining <= NOTICE_RESERVE) {
                omittedFiles.add(filePathOf(section))
                omittedLines += section.count { it == '\n' }
                continue
            }
            val allowance = minOf(perSectionBudget, remaining)
            if (section.length <= allowance) {
                builder.append(section).append('\n')
                continue
            }
            val truncated = truncateSection(section, allowance)
            builder.append(truncated.text)
            omittedFiles.add(filePathOf(section))
            omittedLines += truncated.omittedLines
        }

        var text = builder.toString()
        if (text.length > maxCharacters) {
            text = hardCut(text, maxCharacters)
        }

        return StagedDiff(
            text = text,
            truncated = true,
            originalCharacters = originalLength,
            includedCharacters = text.length,
            omittedFiles = omittedFiles.toList(),
            omittedLines = omittedLines,
        )
    }

    private fun splitIntoSections(diff: String): List<String> {
        val sections = ArrayList<StringBuilder>()
        var current: StringBuilder? = null
        for (line in diff.split('\n')) {
            if (line.startsWith(SECTION_PREFIX)) {
                current?.let { sections.add(it) }
                current = StringBuilder(line)
            } else {
                val target = current ?: StringBuilder().also { current = it }
                if (target.isNotEmpty()) target.append('\n')
                target.append(line)
            }
        }
        current?.let { sections.add(it) }
        return sections.map { it.toString() }
    }

    private class TruncatedSection(val text: String, val omittedLines: Int)

    private fun truncateSection(section: String, allowance: Int): TruncatedSection {
        val lines = section.split('\n')
        val kept = ArrayList<String>(lines.size)
        var used = 0

        val headerEnd = lines.indexOfFirst { it.startsWith("@@") }.let { if (it < 0) lines.size else it }
        for (index in 0 until headerEnd) {
            kept.add(lines[index])
            used += lines[index].length + 1
        }

        var index = headerEnd
        while (index < lines.size && used + lines[index].length + 1 <= allowance - NOTICE_RESERVE) {
            kept.add(lines[index])
            used += lines[index].length + 1
            index++
        }

        val omitted = lines.size - index
        if (omitted > 0) {
            kept.add("... [Commit Mate] 该文件另有 $omitted 行 diff 因长度限制被省略 ...")
        }
        return TruncatedSection(kept.joinToString("\n") + "\n", omitted)
    }

    private fun hardCut(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val marker = "\n... [Commit Mate] 内容因长度限制被截断 ...\n"
        val keep = (limit - marker.length).coerceAtLeast(0)
        val cut = text.substring(0, minOf(keep, text.length))
        val lastNewline = cut.lastIndexOf('\n')
        val body = if (lastNewline > 0) cut.substring(0, lastNewline) else cut
        return (body + marker).take(limit)
    }

    private fun filePathOf(section: String): String {
        val header = section.lineSequence().firstOrNull()?.removePrefix(SECTION_PREFIX) ?: return "未知文件"
        val marker = " b/"
        val index = header.lastIndexOf(marker)
        return if (index >= 0) header.substring(index + marker.length).trim() else header.trim()
    }
}
