package com.cosmosos.rider.testing

import com.cosmosos.rider.CosmosIcons
import com.cosmosos.rider.run.CosmosKernelRunProfile
import com.cosmosos.rider.run.KernelTarget
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.StoredProperty
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class CosmosTestRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Cosmos Kernel Tests",
    "Run Cosmos kernel test suites in QEMU",
    CosmosIcons.Cosmos
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId() = ID

            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                CosmosTestRunConfiguration(project, this, "Cosmos Kernel Tests")

            override fun getOptionsClass() = CosmosTestRunConfigurationOptions::class.java
        })
    }

    val factory: ConfigurationFactory
        get() = configurationFactories.first()

    companion object {
        const val ID = "CosmosKernelTests"

        fun getInstance(): CosmosTestRunConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(CosmosTestRunConfigurationType::class.java)
    }
}

class CosmosTestRunConfigurationOptions : RunConfigurationOptions() {
    // Empty means every suite under tests/Kernels.
    private val suiteProperty: StoredProperty<String?> = string("").provideDelegate(this, "suite")
    private val archProperty: StoredProperty<String?> = string("x64").provideDelegate(this, "arch")
    // "ci" = headless QEMU (faster), "dev" = visual QEMU window.
    private val modeProperty: StoredProperty<String?> = string("ci").provideDelegate(this, "mode")
    // 0 means the per-suite default.
    private val timeoutProperty: StoredProperty<Int> = property(0).provideDelegate(this, "timeoutSeconds")

    var suite: String
        get() = suiteProperty.getValue(this).orEmpty()
        set(value) = suiteProperty.setValue(this, value)

    var arch: String
        get() = archProperty.getValue(this)?.ifEmpty { null } ?: "x64"
        set(value) = archProperty.setValue(this, value)

    var mode: String
        get() = modeProperty.getValue(this)?.ifEmpty { null } ?: "ci"
        set(value) = modeProperty.setValue(this, value)

    var timeoutSeconds: Int
        get() = timeoutProperty.getValue(this)
        set(value) = timeoutProperty.setValue(this, value)
}

class CosmosTestRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<CosmosTestRunConfigurationOptions>(project, factory, name), CosmosKernelRunProfile {

    override fun getOptions(): CosmosTestRunConfigurationOptions = super.getOptions() as CosmosTestRunConfigurationOptions

    var suite: String
        get() = options.suite
        set(value) {
            options.suite = value
        }

    var arch: String
        get() = options.arch
        set(value) {
            options.arch = value
        }

    var mode: String
        get() = options.mode
        set(value) {
            options.mode = value
        }

    var timeoutSeconds: Int
        get() = options.timeoutSeconds
        set(value) {
            options.timeoutSeconds = value
        }

    fun selectedSuites(): List<TestKernel> {
        val all = TestDiscovery.findTestKernels(project.basePath)
        return if (suite.isEmpty()) all else all.filter { it.suiteName == suite }
    }

    // Debugging boots one test kernel under gdb; with several suites selected
    // only the first one is debugged.
    override fun resolveKernelTarget(project: Project): KernelTarget {
        val kernel = selectedSuites().firstOrNull()
            ?: throw ExecutionException("No Cosmos test kernel found for '${suite.ifEmpty { "all suites" }}'")
        return KernelTarget(kernel.kernelName, kernel.csprojPath, kernel.projectDir, arch, project.basePath)
    }

    override fun checkConfiguration() {
        if (arch != "x64" && arch != "arm64") throw RuntimeConfigurationError("Unknown architecture '$arch'")
        if (selectedSuites().isEmpty()) {
            throw RuntimeConfigurationError(
                if (suite.isEmpty()) "No test kernels under tests/Kernels" else "Test suite '$suite' not found under tests/Kernels"
            )
        }
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = CosmosTestRunConfigurationEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        CosmosTestRunState(environment, this)
}

class CosmosTestRunConfigurationEditor(project: Project) : SettingsEditor<CosmosTestRunConfiguration>() {
    private val suiteCombo = ComboBox(
        (listOf("") + TestDiscovery.findTestKernels(project.basePath).map { it.suiteName }).toTypedArray()
    ).apply {
        isEditable = true
        renderer = SimpleListCellRenderer.create("All suites") { it.ifEmpty { "All suites" } }
    }
    private val archCombo = ComboBox(arrayOf("x64", "arm64"))
    private val modeCombo = ComboBox(arrayOf("ci", "dev")).apply {
        renderer = SimpleListCellRenderer.create("") {
            if (it == "dev") "dev — visual QEMU window (slower)" else "ci — headless QEMU (faster)"
        }
    }
    private val timeoutField = JBTextField()

    override fun resetEditorFrom(configuration: CosmosTestRunConfiguration) {
        suiteCombo.selectedItem = configuration.suite
        archCombo.selectedItem = configuration.arch
        modeCombo.selectedItem = configuration.mode
        timeoutField.text = configuration.timeoutSeconds.takeIf { it > 0 }?.toString().orEmpty()
    }

    override fun applyEditorTo(configuration: CosmosTestRunConfiguration) {
        configuration.suite = (suiteCombo.editor.item as? String ?: suiteCombo.selectedItem as? String).orEmpty().trim()
        configuration.arch = archCombo.selectedItem as? String ?: "x64"
        configuration.mode = modeCombo.selectedItem as? String ?: "ci"
        configuration.timeoutSeconds = timeoutField.text.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    override fun createEditor(): JComponent = panel {
        row("Suite:") { cell(suiteCombo) }
        row("Architecture:") { cell(archCombo) }
        row("Mode:") { cell(modeCombo) }
        row("Timeout (s):") {
            cell(timeoutField).comment("Leave empty for the suite's default.")
        }
    }
}
