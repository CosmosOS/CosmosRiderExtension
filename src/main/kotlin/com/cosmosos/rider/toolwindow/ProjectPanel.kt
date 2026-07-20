package com.cosmosos.rider.toolwindow

import com.cosmosos.rider.services.CosmosProjectService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class ProjectPanel(private val project: Project) : JPanel(BorderLayout()) {

    private data class ActionItem(
        val label: String,
        val description: String,
        val actionId: String,
        val icon: Icon
    )

    init {
        val service = CosmosProjectService.getInstance(project)
        val projectInfo = service.getProjectInfo()

        if (projectInfo != null) {
            val archLabel = if (projectInfo.arch == "arm64") "ARM64" else "x64"
            val archDesc = if (projectInfo.arch == "arm64") "ARM 64-bit" else "Intel/AMD 64-bit"

            val items = listOf(
                ActionItem("Properties", "Edit project settings", "Cosmos.Properties", AllIcons.General.Settings),
                ActionItem("Build", "Build for $archDesc", "Cosmos.Build", AllIcons.Actions.Compile),
                ActionItem("Run", "Run in QEMU ($archLabel)", "Cosmos.Run", AllIcons.Actions.Execute),
                ActionItem("Debug", "Debug with GDB ($archLabel)", "Cosmos.Debug", AllIcons.Actions.StartDebugger),
                ActionItem("Clean", "Remove build outputs", "Cosmos.Clean", AllIcons.Actions.GC)
            )

            val listModel = DefaultListModel<ActionItem>()
            items.forEach { listModel.addElement(it) }

            val list = JBList(listModel)
            list.cellRenderer = ActionItemRenderer()
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION

            list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val index = list.locationToIndex(e.point)
                        if (index >= 0) {
                            val item = listModel.getElementAt(index)
                            val action = ActionManager.getInstance().getAction(item.actionId)
                            action?.actionPerformed(
                                AnActionEvent.createFromAnAction(
                                    action,
                                    null,
                                    "CosmosToolWindow",
                                    DataContext { if (it == com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name) project else null }
                                )
                            )
                        }
                    }
                }
            })

            // Header
            val headerLabel = JLabel("  ${projectInfo.name} ($archLabel)")
            headerLabel.font = headerLabel.font.deriveFont(headerLabel.font.size2D + 2f)
            headerLabel.border = BorderFactory.createEmptyBorder(8, 4, 8, 4)

            add(headerLabel, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        } else {
            val label = JLabel("No Cosmos project found", SwingConstants.CENTER)
            add(label, BorderLayout.CENTER)
        }
    }

    private class ActionItemRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is ActionItem) {
                text = value.label
                toolTipText = value.description
                icon = value.icon
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            }
            return this
        }
    }
}
