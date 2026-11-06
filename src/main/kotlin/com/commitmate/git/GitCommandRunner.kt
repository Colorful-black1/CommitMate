package com.commitmate.git

import com.commitmate.core.OperationCanceledException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Git 命令执行失败（无法启动、非 0 退出码或超时）。 */
class GitCommandException(
    message: String,
    val exitCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Git 命令执行结果。 */
class GitCommandResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
    /** 输出超过 [GitCommandRunner] 设置的上限而被截断。 */
    val outputLimitExceeded: Boolean = false,
) {
    val stdoutText: String
        get() = String(stdout, StandardCharsets.UTF_8)
}

/**
 * 使用 [ProcessBuilder] 执行 git 命令，不经过 shell，避免命令拼接带来的注入风险。
 *
 * 只读约定：
 * - 调用方只允许传入 `diff` / `rev-parse` 这类只读子命令；
 * - 强制 `GIT_TERMINAL_PROMPT=0`，不会因为缺少凭据而卡在交互提示；
 * - 强制 `GIT_OPTIONAL_LOCKS=0`，避免 git 在读取时写回索引文件。
 */
class GitCommandRunner(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) {

    fun run(
        workingDirectory: File,
        args: List<String>,
        isCanceled: () -> Boolean = { false },
    ): GitCommandResult {
        if (!workingDirectory.isDirectory) {
            throw GitCommandException("Git 仓库目录不存在：${workingDirectory.absolutePath}")
        }

        val command = ArrayList<String>(args.size + 1).apply {
            add("git")
            addAll(args)
        }

        val builder = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(false)
        builder.environment().apply {
            put("GIT_TERMINAL_PROMPT", "0")
            put("GIT_OPTIONAL_LOCKS", "0")
            put("GIT_PAGER", "cat")
        }

        val process = try {
            builder.start()
        } catch (e: IOException) {
            throw GitCommandException("无法启动 git 命令，请确认已安装 Git 并加入 PATH。", cause = e)
        }

        val stderr = StringBuilder()
        val stderrReader = Thread {
            try {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    reader.forEachLine { line ->
                        synchronized(stderr) { stderr.appendLine(line) }
                    }
                }
            } catch (_: IOException) {
                // 进程被强制结束时忽略
            }
        }
        stderrReader.isDaemon = true
        stderrReader.start()

        val stdout = ByteArrayOutputStream()
        var limitExceeded = false
        try {
            process.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (isCanceled()) {
                        process.destroy()
                        throw OperationCanceledException()
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (stdout.size() + read > maxOutputBytes) {
                        stdout.write(buffer, 0, maxOutputBytes - stdout.size())
                        limitExceeded = true
                        process.destroy()
                        break
                    }
                    stdout.write(buffer, 0, read)
                }
            }
        } catch (e: IOException) {
            if (isCanceled()) {
                process.destroy()
                throw OperationCanceledException()
            }
            throw GitCommandException("读取 git 输出失败：${e.message}", cause = e)
        }

        val finished = try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroy()
            throw OperationCanceledException()
        }
        if (!finished) {
            process.destroyForcibly()
            throw GitCommandException("git 命令执行超时（${timeoutMillis} 毫秒），仓库可能过大。")
        }
        stderrReader.join(1_000)

        return GitCommandResult(
            exitCode = process.exitValue(),
            stdout = stdout.toByteArray(),
            stderr = stderr.toString(),
            outputLimitExceeded = limitExceeded,
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000L
        const val DEFAULT_MAX_OUTPUT_BYTES: Int = 32 * 1024 * 1024
    }
}
