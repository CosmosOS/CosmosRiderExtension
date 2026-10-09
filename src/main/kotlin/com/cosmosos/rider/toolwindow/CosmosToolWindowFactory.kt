package com.cosmosos.rider.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CosmosToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val manager = toolWindow.contentManager

        fun addTab(title: String, create: (com.intellij.openapi.Disposable) -> javax.swing.JComponent) {
            val disposable = Disposer.newDisposable("Cosmos $title tab")
            val content = contentFactory.createContent(create(disposable), title, false)
            content.setDisposer(disposable)
            manager.addContent(content)
        }

        addTab("Project") { ProjectPanel(project, it) }
        addTab("Tools") { ToolsPanel(project, it) }
    }
}
