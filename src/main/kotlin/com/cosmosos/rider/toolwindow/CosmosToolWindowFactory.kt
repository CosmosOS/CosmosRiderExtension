package com.cosmosos.rider.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CosmosToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        val projectPanel = ProjectPanel(project)
        val projectContent = contentFactory.createContent(projectPanel, "Project", false)
        toolWindow.contentManager.addContent(projectContent)

        val toolsPanel = ToolsPanel(project)
        val toolsContent = contentFactory.createContent(toolsPanel, "Tools", false)
        toolWindow.contentManager.addContent(toolsContent)
    }
}
