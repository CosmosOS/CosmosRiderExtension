package com.cosmosos.rider.toolwindow

import com.cosmosos.rider.services.CosmosProjectListener
import com.cosmosos.rider.services.CosmosProjectService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class ProjectPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    private data class ActionItem(
        val label: String,
        val description: String,
        val actionId: String,
        val icon: Icon
    )

    private val header = JBLabel().apply {
        font = font.deriveFont(font.size2D + 2f)
        border = JBUI.Borders.empty(8, 8)
    }
    private val listModel = DefaultListModel<ActionItem>()
    private val list = JBList(listModel)

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<ActionItem>() {
            override fun customizeCellRenderer(
                list: JList<out ActionItem>,
                value: ActionItem,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                icon = value.icon
                append(value.label)
                append("  " + value.description, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = value.description
                border = JBUI.Borders.empty(4, 4)
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val index = list.locationToIndex(e.point)
                    if (index >= 0 && list.getCellBounds(index, index)?.contains(e.point) == true) invoke(listModel[index])
                }
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) list.selectedValue?.let(::invoke)
            }
        })
        list.emptyText.text = "No Cosmos project found"

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)

        project.messageBus.connect(parent).subscribe(CosmosProjectService.TOPIC, CosmosProjectListener { refresh() })
        refresh()
    }

    private fun invoke(item: ActionItem) {
        val action = ActionManager.getInstance().getAction(item.actionId) ?: return
        val dataContext = DataManager.getInstance().getDataContext(list)
        val event = AnActionEvent.createEvent(action, dataContext, null, "CosmosToolWindow", ActionUiKind.NONE, null)
        ActionUtil.performAction(action, event)
    }

    private fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val info = CosmosProjectService.getInstance(project).getProjectInfo()
            ApplicationManager.getApplication().invokeLater {
                listModel.clear()
                if (info == null) {
                    header.text = ""
                    return@invokeLater
                }
                val archLabel = if (info.arch == "arm64") "ARM64" else "x64"
                val archDesc = if (info.arch == "arm64") "ARM 64-bit" else "Intel/AMD 64-bit"
                header.text = "${info.name} ($archLabel)"
                listOf(
                    ActionItem("Properties", "Edit project settings", "Cosmos.Properties", AllIcons.General.Settings),
                    ActionItem("Build", "Build for $archDesc", "Cosmos.Build", AllIcons.Actions.Compile),
                    ActionItem("Run", "Run in QEMU ($archLabel)", "Cosmos.Run", AllIcons.Actions.Execute),
                    ActionItem("Debug", "Debug with GDB ($archLabel)", "Cosmos.Debug", AllIcons.Actions.StartDebugger),
                    ActionItem("Clean", "Remove build outputs", "Cosmos.Clean", AllIcons.Actions.GC)
                ).forEach(listModel::addElement)
            }
        }
    }
}
