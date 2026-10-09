package com.cosmosos.rider.toolwindow

import com.cosmosos.rider.services.CosmosProjectListener
import com.cosmosos.rider.services.CosmosProjectService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListSelectionModel

class ToolsPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {

    private val listModel = DefaultListModel<CosmosProjectService.ToolStatus>()
    private val list = JBList(listModel)
    private val refreshing = AtomicBoolean(false)

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<CosmosProjectService.ToolStatus>() {
            override fun customizeCellRenderer(
                list: JList<out CosmosProjectService.ToolStatus>,
                value: CosmosProjectService.ToolStatus,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                icon = if (value.installed) AllIcons.General.InspectionsOK else AllIcons.General.Error
                append(value.displayName)
                append("  " + value.version, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = if (value.installed) "${value.displayName}: ${value.version}" else "${value.displayName} is not installed"
            }
        }

        val group = DefaultActionGroup(
            object : DumbAwareAction("Refresh", "Check the installed tools again", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refreshTools()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            },
            ActionManager.getInstance().getAction("Cosmos.CheckTools"),
            ActionManager.getInstance().getAction("Cosmos.InstallTools")
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("CosmosTools", group, true)
        toolbar.targetComponent = list
        setToolbar(toolbar.component)
        setContent(JBScrollPane(list))

        project.messageBus.connect(parent).subscribe(CosmosProjectService.TOOLS_TOPIC, CosmosProjectListener { refreshTools() })
        refreshTools()
    }

    private fun refreshTools() {
        if (!refreshing.compareAndSet(false, true)) return
        list.setPaintBusy(true)
        list.emptyText.text = "Checking tools…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val tools = try {
                CosmosProjectService.getInstance(project).checkTools()
            } finally {
                refreshing.set(false)
            }
            ApplicationManager.getApplication().invokeLater {
                list.setPaintBusy(false)
                listModel.clear()
                tools.forEach(listModel::addElement)
                list.emptyText.text = "No tools reported"
            }
        }
    }
}
