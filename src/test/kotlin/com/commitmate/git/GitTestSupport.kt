package com.commitmate.git

import java.nio.file.Files
import java.nio.file.Path

/** 测试用 Git 辅助方法。 */
internal object GitTestSupport {

    val isGitAvailable: Boolean by lazy {
        runCatching {
            val process = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    fun git(workingDirectory: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        check(exitCode == 0) { "git ${args.joinToString(" ")} 执行失败（$exitCode）：$output" }
        return output
    }

    fun initRepository(root: Path) {
        Files.createDirectories(root)
        git(root, "init", "-q")
        git(root, "config", "user.email", "commit-mate@example.com")
        git(root, "config", "user.name", "Commit Mate Test")
        git(root, "config", "commit.gpgsign", "false")
        git(root, "config", "core.autocrlf", "false")
    }

    fun writeFile(root: Path, relativePath: String, content: String) {
        val file = root.resolve(relativePath)
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, content)
    }

    fun commitAll(root: Path, message: String) {
        git(root, "add", "-A")
        git(root, "commit", "-q", "-m", message)
    }
}
