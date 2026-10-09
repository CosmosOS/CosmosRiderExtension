package com.cosmosos.rider.util

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object CosmosNotifications {
    private const val GROUP_ID = "Cosmos OS"

    fun notify(
        project: Project?,
        content: String,
        type: NotificationType = NotificationType.INFORMATION,
        vararg actions: Pair<String, () -> Unit>
    ): Notification {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(content, type)
        for ((label, handler) in actions) {
            notification.addAction(NotificationAction.createSimpleExpiring(label) { handler() })
        }
        notification.notify(project)
        return notification
    }

    fun info(project: Project?, content: String) = notify(project, content, NotificationType.INFORMATION)

    fun warn(project: Project?, content: String) = notify(project, content, NotificationType.WARNING)

    fun error(project: Project?, content: String) = notify(project, content, NotificationType.ERROR)
}
