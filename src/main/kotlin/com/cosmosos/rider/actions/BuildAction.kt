package com.cosmosos.rider.actions

import com.cosmosos.rider.services.CosmosProcessService
import com.cosmosos.rider.services.CosmosProjectService
import com.cosmosos.rider.util.PlatformUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class BuildAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = CosmosProjectService.getInstance(project)
        val projectInfo = service.getProjectInfo() ?: return

        // Ask for build configuration
        val configs = arrayOf("Debug", "Release")
        val configChoice = com.intellij.openapi.ui.Messages.showDialog(
            project,
            "Select build configuration:",
            "Build Configuration",
            configs,
            0,
            com.intellij.openapi.ui.Messages.getQuestionIcon()
        )
        if (configChoice < 0) return
        val config = configs[configChoice]

        val cosmosPath = PlatformUtil.findCommand("cosmos") ?: "cosmos"

        val args = listOf(
            "build",
            "-p", projectInfo.projectDir,
            "-a", projectInfo.arch,
            "-c", config,
            "-v"
        )

        val processService = CosmosProcessService.getInstance(project)
        processService.runProcess(
            title = "Build ${projectInfo.name} (${projectInfo.arch})",
            executable = cosmosPath,
            args = args,
            workDir = projectInfo.projectDir,
            extraEnv = mapOf("COLUMNS" to "1000", "CI" to "true")
        )
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
                CosmosProjectService.getInstance(project).isCosmosProject()
    }
}
