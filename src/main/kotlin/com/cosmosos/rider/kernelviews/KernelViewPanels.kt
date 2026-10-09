package com.cosmosos.rider.kernelviews

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.StatusBar
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList

private fun toolbarFor(
    place: String,
    target: JComponent,
    onRefresh: () -> Unit,
    copyText: () -> String,
    copyLabel: String,
    project: Project
): JComponent {
    val group = DefaultActionGroup(
        object : DumbAwareAction("Refresh", "Read the snapshot again now", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = onRefresh()
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        },
        object : DumbAwareAction("Copy", "Copy the view as text", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                CopyPasteManager.getInstance().setContents(StringSelection(copyText()))
                StatusBar.Info.set("$copyLabel copied to clipboard", project)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }
    )
    val toolbar = ActionManager.getInstance().createActionToolbar(place, group, false)
    toolbar.targetComponent = target
    return toolbar.component
}

/** "Kernel Threads" debug tab: the scheduler's thread table, live. */
class KernelThreadsPanel(project: Project, private val views: KernelLiveViews) : SimpleToolWindowPanel(false, true) {
    private val listModel = DefaultListModel<KernelThreadInfo>()
    private val list = JBList(listModel)

    init {
        list.cellRenderer = object : ColoredListCellRenderer<KernelThreadInfo>() {
            override fun customizeCellRenderer(
                list: JList<out KernelThreadInfo>,
                value: KernelThreadInfo,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                icon = iconForState(value.state)
                append("#${value.id} [${value.state}]${value.cpuLabel}")
                val flags = value.flagNames
                append(
                    if (flags.isEmpty()) "  slot ${value.slot}" else "  slot ${value.slot} · ${flags.joinToString("|")}",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES
                )
            }
        }
        setContent(JBScrollPane(list))
        toolbar = toolbarFor("CosmosKernelThreads", list, { views.refreshNow(views.threads) }, views.threads::serialize, "Kernel Threads", project)
        views.threads.addListener(::update)
        update()
    }

    private fun update() {
        val model = views.threads
        listModel.clear()
        model.threads.forEach(listModel::addElement)
        list.emptyText.text = model.message ?: "(empty)"
    }

    private fun iconForState(state: String): Icon = when (state) {
        "Running" -> AllIcons.Debugger.ThreadRunning
        "Ready" -> AllIcons.Debugger.ThreadSuspended
        "Blocked" -> AllIcons.Debugger.ThreadFrozen
        "Sleeping" -> AllIcons.Debugger.ThreadAtBreakpoint
        "Dead" -> AllIcons.Actions.Cancel
        else -> AllIcons.Debugger.ThreadDaemon
    }
}

data class Metric(val label: String, val value: String, val icon: Icon, val tooltip: String? = null)

private class MetricsList : JBList<Metric>(DefaultListModel()) {
    init {
        cellRenderer = object : ColoredListCellRenderer<Metric>() {
            override fun customizeCellRenderer(
                list: JList<out Metric>,
                value: Metric,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                icon = value.icon
                append(value.label)
                append("  " + value.value, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = value.tooltip
            }
        }
    }

    fun show(metrics: List<Metric>, emptyMessage: String) {
        val model = model as DefaultListModel<Metric>
        model.clear()
        metrics.forEach(model::addElement)
        emptyText.text = emptyMessage
    }
}

/** "Kernel GC" debug tab: the garbage collector's counters. */
class KernelGcPanel(project: Project, private val views: KernelLiveViews) : SimpleToolWindowPanel(false, true) {
    private val list = MetricsList()

    init {
        setContent(JBScrollPane(list))
        toolbar = toolbarFor("CosmosKernelGC", list, { views.refreshNow(views.gc) }, views.gc::serialize, "Kernel GC", project)
        views.gc.addListener(::update)
        update()
    }

    private fun update() {
        val s = views.gc.stats
        val f = KernelSnapshots::formatBytes
        val metrics = if (s == null) emptyList() else listOf(
            Metric("Status", if (s.initialized) "initialized" else "not initialized",
                if (s.initialized) AllIcons.General.InspectionsOK else AllIcons.General.Information),
            Metric("Heap size", f(s.heapSizeBytes), AllIcons.Nodes.DataTables),
            Metric("Fragmented", f(s.fragmentedBytes), AllIcons.Nodes.Plugin),
            Metric("Total committed", f(s.totalCommittedBytes), AllIcons.Nodes.Package),
            Metric("Total allocated", f(s.totalAllocatedBytes), AllIcons.Nodes.DataSchema),
            Metric("Pinned objects", s.pinnedObjectsCount.toString(), AllIcons.General.Pin),
            Metric("Collections", s.collectionCount.toString(), AllIcons.Actions.Refresh),
            Metric("Objects freed", s.totalObjectsFreed.toString(), AllIcons.Actions.GC),
            Metric("Memory load", f(s.memoryLoadBytes), AllIcons.Debugger.Db_primitive),
            Metric("Segment size", f(s.gcSegmentSize), AllIcons.Nodes.Folder),
            Metric("Last GC %time-in-GC", "${s.lastGCPercentTimeInGC}%", AllIcons.Debugger.Watch),
            Metric("Last gen0 size (before)", f(s.lastGen0SizeBefore), AllIcons.General.ArrowUp),
            Metric("Last gen0 frag (before)", f(s.lastGen0FragBefore), AllIcons.General.ArrowUp),
            Metric("Last gen0 size (after)", f(s.lastGen0SizeAfter), AllIcons.General.ArrowDown),
            Metric("Last gen0 frag (after)", f(s.lastGen0FragAfter), AllIcons.General.ArrowDown)
        )
        list.show(metrics, views.gc.message ?: "Waiting for GC snapshot…")
    }
}

/** "Kernel Memory" debug tab: page allocator counters above the RAT memory map. */
class KernelMemoryPanel(project: Project, private val views: KernelLiveViews) : SimpleToolWindowPanel(false, true) {
    private val list = MetricsList()
    private val map = MemoryMapComponent()

    init {
        val splitter = JBSplitter(true, 0.35f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = JBScrollPane(map).apply { horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER }
        }
        setContent(splitter)
        toolbar = toolbarFor("CosmosKernelMemory", list, { views.refreshNow(views.memory) }, views.memory::serialize, "Kernel Memory", project)
        views.memory.addListener(::update)
        update()
    }

    private fun update() {
        val model = views.memory
        val s = model.stats
        val f = KernelSnapshots::formatBytes
        val metrics = if (s == null) emptyList() else {
            val used = s.totalPageCount - s.freePageCount
            listOf(
                Metric("Status", if (s.initialized) "initialized" else "not initialized",
                    if (s.initialized) AllIcons.General.InspectionsOK else AllIcons.General.Information),
                Metric("Page size", "${s.pageSize} B", AllIcons.Nodes.Constant),
                Metric("RAM start", KernelSnapshots.formatHex(s.ramStart), AllIcons.General.ArrowRight,
                    "Base of the heap data area."),
                Metric("RAM size", f(s.ramSize), AllIcons.Nodes.DataTables,
                    "Heap data area only (RAT pages excluded)."),
                Metric("RAT address", KernelSnapshots.formatHex(s.ratAddress), AllIcons.Nodes.DataSchema,
                    "Region Allocation Table base. By design the RAT sits at the end of the heap, so this is also where the heap ends."),
                Metric("Total pages", s.totalPageCount.toString(), AllIcons.Debugger.Db_primitive),
                Metric("Free pages", "${s.freePageCount} (${f(s.freePageCount * s.pageSize)})", AllIcons.Actions.Checked),
                Metric("Used pages", "$used (${f(used * s.pageSize)})", AllIcons.Nodes.DataSchema)
            )
        }
        list.show(metrics, model.message ?: "Waiting for memory snapshot…")
        map.update(s, model.extents, model.extentsError, model.message)
    }
}
