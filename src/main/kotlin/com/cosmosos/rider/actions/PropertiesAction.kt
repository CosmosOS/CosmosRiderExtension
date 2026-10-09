package com.cosmosos.rider.actions

import com.cosmosos.rider.toolwindow.PropertiesDialog
import com.cosmosos.rider.util.ProjectConfig
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class PropertiesAction : CosmosProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val info = CosmosCommands.projectInfoOrError(project, "Properties Error") ?: return
        val props = try {
            ProjectConfig.parseProjectProperties(info.csprojPath)
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, "Failed to read ${info.csprojPath}: ${ex.message}", "Properties Error")
            return
        }
        PropertiesDialog(project, info.csprojPath, props).show()
    }
}
