package com.cosmosos.rider.actions

import com.cosmosos.rider.services.CosmosProjectService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import java.io.File

class CleanAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = CosmosProjectService.getInstance(project)
        val projectInfo = service.getProjectInfo()

        if (projectInfo == null) {
            Messages.showErrorDialog(project, "No Cosmos project found", "Clean Error")
            return
        }

        val confirm = Messages.showYesNoDialog(
            project,
            "Delete all build outputs?",
            "Clean Build",
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

        val dirsToClean = listOf("output-x64", "output-arm64", "bin", "obj")
        var cleaned = 0

        for (dir in dirsToClean) {
            val dirFile = File(projectInfo.projectDir, dir)
            if (dirFile.exists()) {
                dirFile.deleteRecursively()
                cleaned++
            }
        }

        Messages.showInfoMessage(project, "Cleaned $cleaned directories", "Clean Complete")
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
                CosmosProjectService.getInstance(project).isCosmosProject()
    }
}
