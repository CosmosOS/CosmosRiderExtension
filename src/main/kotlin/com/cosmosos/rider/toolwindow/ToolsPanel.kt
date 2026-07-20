package com.cosmosos.rider.toolwindow

import com.cosmosos.rider.services.CosmosProjectService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*

class ToolsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<CosmosProjectService.ToolStatus>()
    private val list = JBList(listModel)

    init {
        list.cellRenderer = ToolItemRenderer()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION

        val toolbar = JPanel(BorderLayout())
        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { refreshTools() }
        toolbar.add(refreshButton, BorderLayout.EAST)
        toolbar.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)

        refreshTools()
    }

    private fun refreshTools() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val service = CosmosProjectService.getInstance(project)
            val tools = service.checkTools()

            SwingUtilities.invokeLater {
                listModel.clear()
                tools.forEach { listModel.addElement(it) }
            }
        }
    }

    private class ToolItemRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is CosmosProjectService.ToolStatus) {
                text = "${value.displayName} - ${value.version}"
                icon = if (value.installed) AllIcons.General.InspectionsOK else AllIcons.General.Error
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            }
            return this
        }
    }
}
