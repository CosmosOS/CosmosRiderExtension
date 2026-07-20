package com.cosmosos.rider.actions

import com.cosmosos.rider.services.CosmosProcessService
import com.cosmosos.rider.util.PlatformUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class CheckToolsAction : AnAction() {
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

        val cosmosPath = PlatformUtil.findCommand("cosmos") ?: "cosmos"
        val processService = CosmosProcessService.getInstance(project)
        processService.runProcess(
            title = "Check Development Tools",
            executable = cosmosPath,
            args = listOf("check")
        )
    }
}
