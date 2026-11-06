package com.commitmate.core

/**
 * 用户主动取消（或 IDE 取消）正在执行的任务时抛出。
 *
 * 独立于 [com.intellij.openapi.progress.ProcessCanceledException]，这样纯逻辑层不需要依赖
 * IntelliJ Platform，单元测试可以直接覆盖取消路径。
 */
class OperationCanceledException(message: String = "操作已取消") : RuntimeException(message)
