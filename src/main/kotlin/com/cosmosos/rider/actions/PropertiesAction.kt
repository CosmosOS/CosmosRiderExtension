package com.cosmosos.rider.actions

import com.cosmosos.rider.services.CosmosProjectService
import com.cosmosos.rider.toolwindow.PropertiesDialog
import com.cosmosos.rider.util.ProjectConfig
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class PropertiesAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = CosmosProjectService.getInstance(project)
        val projectInfo = service.getProjectInfo()

        if (projectInfo == null) {
            Messages.showErrorDialog(project, "No Cosmos project found", "Properties Error")
            return
        }

        val props = ProjectConfig.parseProjectProperties(projectInfo.csprojPath)
        val dialog = PropertiesDialog(project, projectInfo.csprojPath, props)
        dialog.show()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
                CosmosProjectService.getInstance(project).isCosmosProject()
    }
}
