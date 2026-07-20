package com.cosmosos.rider

import com.cosmosos.rider.services.CosmosProjectService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

class CosmosStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = CosmosProjectService.getInstance(project)
        if (service.isCosmosProject()) {
            ApplicationManager.getApplication().invokeLater {
                ToolWindowManager.getInstance(project).getToolWindow("Cosmos")?.show()
            }
        }
    }
}
