package com.cosmosos.rider.actions

import com.cosmosos.rider.services.CosmosProjectService
import com.cosmosos.rider.util.PlatformUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

class InstallToolsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        install(e.project ?: return)
    }

    companion object {
        // Installs Cosmos.Tools first when it's missing, then the toolchain.
        // Runs in a pseudo-terminal so `cosmos install` can prompt.
        fun install(project: Project) {
            val command = if (PlatformUtil.isCosmosToolsInstalled) {
                "cosmos install"
            } else {
                "dotnet tool install -g Cosmos.Tools && cosmos install"
            }
            CosmosCommands.runInstaller(project, "Install Development Tools", command) {
                CosmosProjectService.getInstance(project).fireToolsChanged()
            }
        }
    }
}
