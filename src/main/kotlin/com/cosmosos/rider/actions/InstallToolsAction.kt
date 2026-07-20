package com.cosmosos.rider.actions

import com.cosmosos.rider.services.CosmosProcessService
import com.cosmosos.rider.util.PlatformUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class InstallToolsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        if (!PlatformUtil.isCosmosToolsInstalled) {
            Messages.showInfoMessage(
                project,
                "First install Cosmos Tools by running in a terminal:\n\ndotnet tool install -g Cosmos.Tools\n\nThen run this action again.",
                "Install Cosmos Tools"
            )
            return
        }

        val cosmosPath = PlatformUtil.findCommand("cosmos") ?: "cosmos"
        val processService = CosmosProcessService.getInstance(project)
        processService.runProcess(
            title = "Install Development Tools",
            executable = cosmosPath,
            args = listOf("install")
        )
    }
}
