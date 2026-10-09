package com.cosmosos.rider.actions

import com.cosmosos.rider.services.CosmosProcessService
import com.cosmosos.rider.services.CosmosProjectService
import com.cosmosos.rider.util.CosmosNotifications
import com.cosmosos.rider.util.PlatformUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

class CheckToolsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        if (!PlatformUtil.isCosmosToolsInstalled) {
            Messages.showWarningDialog(
                project,
                "Cosmos Tools is not installed.\n\nRun: dotnet tool install -g Cosmos.Tools",
                "Tools Check"
            )
            return
        }

        // The Tools tab re-reads `cosmos check` when the tools may have changed.
        CosmosProjectService.getInstance(project).fireToolsChanged()

        val cosmos = PlatformUtil.findCommand("cosmos") ?: "cosmos"
        CosmosProcessService.getInstance(project).runProcess(
            title = "Check Development Tools",
            commandLine = PlatformUtil.createCommandLine(cosmos, "check"),
            header = listOf("Checking development tools...", "")
        ) { exitCode ->
            if (exitCode != 0) {
                CosmosNotifications.notify(
                    project,
                    "Some development tools are missing. Run \"Install Tools\" to install them.",
                    NotificationType.WARNING,
                    "Install Tools" to { InstallToolsAction.install(project) }
                )
            }
        }
    }
}
