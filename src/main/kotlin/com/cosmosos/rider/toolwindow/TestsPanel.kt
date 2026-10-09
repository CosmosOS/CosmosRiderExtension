package com.cosmosos.rider.toolwindow

import com.cosmosos.rider.testing.CosmosTestRunConfiguration
import com.cosmosos.rider.testing.CosmosTestRunConfigurationType
import com.cosmosos.rider.testing.TestDiscovery
import com.cosmosos.rider.testing.TestKernel
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JList

/**
 * The Cosmos kernel test suites (tests/Kernels/Cosmos.Kernel.Tests.*). Runs
 * go through a "Cosmos Kernel Tests" run configuration, so results land in
 * the standard test tree; Debug boots the suite's kernel under gdb.
 */
class TestsPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val listModel = DefaultListModel<TestKernel>()
    private val list = JBList(listModel)
    private val properties = PropertiesComponent.getInstance(project)

    // ci = headless QEMU (faster), dev = visual QEMU window.
    private var devMode: Boolean
        get() = properties.getValue(MODE_KEY, "ci") == "dev"
        set(value) = properties.setValue(MODE_KEY, if (value) "dev" else "ci", "ci")

    init {
        list.cellRenderer = object : ColoredListCellRenderer<TestKernel>() {
            override fun customizeCellRenderer(
                list: JList<out TestKernel>,
                value: TestKernel,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                icon = AllIcons.RunConfigurations.TestState.Run
                append(value.suiteName)
                append("  " + value.kernelName, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        list.emptyText.text = "No test kernels under tests/Kernels"
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && list.selectedValue != null) runTests("x64", DefaultRunExecutor.getRunExecutorInstance())
            }
        })

        val group = DefaultActionGroup(
            testAction("Run x64", "Run the selected suites (all when none is selected) on x64", AllIcons.Actions.Execute, "x64", false),
            testAction("Run arm64", "Run the selected suites (all when none is selected) on arm64", AllIcons.Actions.RunAll, "arm64", false),
            testAction("Debug x64", "Boot the selected suite's kernel on x64 under gdb", AllIcons.Actions.StartDebugger, "x64", true),
            testAction("Debug arm64", "Boot the selected suite's kernel on arm64 under gdb", AllIcons.Actions.Resume, "arm64", true),
            Separator.getInstance(),
            object : ToggleAction("Visual QEMU (Dev Mode)", "Run tests in a visible QEMU window instead of headless", AllIcons.Actions.Show) {
                override fun isSelected(e: AnActionEvent) = devMode
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    devMode = state
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            object : DumbAwareAction("Refresh", "Look for test kernels again", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            }
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("CosmosTests", group, true)
        toolbar.targetComponent = list
        setToolbar(toolbar.component)
        setContent(JBScrollPane(list))
        refresh()
    }

    private fun testAction(text: String, description: String, icon: Icon, arch: String, debug: Boolean) =
        object : DumbAwareAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) {
                val executor = if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()
                runTests(arch, executor)
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !listModel.isEmpty && (!debug || list.selectedValue != null)
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun refresh() {
        listModel.clear()
        TestDiscovery.findTestKernels(project.basePath).forEach(listModel::addElement)
    }

    private fun runTests(arch: String, executor: Executor) {
        val selected = list.selectedValuesList
        val debug = executor.id == DefaultDebugExecutor.EXECUTOR_ID
        if (debug && selected.size > 1) {
            Messages.showWarningDialog(project, "Debugging only the first selected suite: ${selected.first().suiteName}.", "Cosmos Tests")
        }
        // One suite runs as itself; several (or none selected) run as "all".
        val suite = when {
            debug -> selected.first().suiteName
            selected.size == 1 -> selected.first().suiteName
            else -> ""
        }
        val name = "Cosmos Tests: ${suite.ifEmpty { "All" }} ($arch)"

        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration(name, CosmosTestRunConfigurationType.getInstance().factory)
        (settings.configuration as CosmosTestRunConfiguration).apply {
            this.suite = suite
            this.arch = arch
            this.mode = if (devMode) "dev" else "ci"
        }
        runManager.setTemporaryConfiguration(settings)
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }

    companion object {
        private const val MODE_KEY = "cosmos.testMode"
    }
}
