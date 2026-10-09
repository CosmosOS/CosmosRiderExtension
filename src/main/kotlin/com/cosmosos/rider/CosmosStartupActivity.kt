package com.cosmosos.rider

import com.cosmosos.rider.actions.CosmosCommands
import com.cosmosos.rider.services.CosmosProjectService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

class CosmosStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!CosmosProjectService.getInstance(project).isCosmosProject()) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            // Make "Cosmos Kernel" available in the Run/Debug selector right
            // away, so the toolbar's Run and Debug buttons boot the kernel.
            CosmosCommands.kernelRunConfiguration(project)
            ToolWindowManager.getInstance(project).getToolWindow("Cosmos")?.show()
        }
    }
}
