package com.commitmate.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * IDEA 原生通知封装。
 *
 * 只传递面向用户的简短描述，绝不包含 API Key 或 Diff 内容。
 */
object CommitMateNotifications {

    private const val GROUP_ID = "CommitMate"

    fun info(project: Project, message: String) {
        notify(project, message, NotificationType.INFORMATION)
    }

    fun warning(project: Project, message: String) {
        notify(project, message, NotificationType.WARNING)
    }

    fun error(project: Project, message: String) {
        notify(project, message, NotificationType.ERROR)
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
        group.createNotification(message, type).notify(project)
    }
}
