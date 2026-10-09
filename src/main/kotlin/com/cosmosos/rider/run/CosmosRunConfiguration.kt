package com.cosmosos.rider.run

import com.cosmosos.rider.CosmosIcons
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.StoredProperty
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class CosmosRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Cosmos Kernel",
    "Run or debug a Cosmos kernel in QEMU",
    CosmosIcons.Cosmos
) {
    init {
        addFactory(CosmosRunConfigurationFactory(this))
    }

    val factory: ConfigurationFactory
        get() = configurationFactories.first()

    companion object {
        const val ID = "CosmosKernel"

        fun getInstance(): CosmosRunConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(CosmosRunConfigurationType::class.java)
    }
}

class CosmosRunConfigurationFactory(type: CosmosRunConfigurationType) : ConfigurationFactory(type) {
    override fun getId() = CosmosRunConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        CosmosRunConfiguration(project, this, "Cosmos Kernel")

    override fun getOptionsClass() = CosmosRunConfigurationOptions::class.java
}

class CosmosRunConfigurationOptions : RunConfigurationOptions() {
    // Empty means "the project's configured architecture".
    private val archProperty: StoredProperty<String?> = string("").provideDelegate(this, "arch")

    // Set together to target a kernel other than the open project's (the
    // Tests tab uses them to debug a test kernel).
    private val projectDirProperty: StoredProperty<String?> = string("").provideDelegate(this, "projectDir")
    private val kernelNameProperty: StoredProperty<String?> = string("").provideDelegate(this, "kernelName")

    var arch: String
        get() = archProperty.getValue(this).orEmpty()
        set(value) = archProperty.setValue(this, value)

    var projectDir: String
        get() = projectDirProperty.getValue(this).orEmpty()
        set(value) = projectDirProperty.setValue(this, value)

    var kernelName: String
        get() = kernelNameProperty.getValue(this).orEmpty()
        set(value) = kernelNameProperty.setValue(this, value)
}

// A run profile that boots one kernel; the Debug runner accepts any of them.
interface CosmosKernelRunProfile : RunProfile {
    fun resolveKernelTarget(project: Project): KernelTarget
}

class CosmosRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<CosmosRunConfigurationOptions>(project, factory, name), CosmosKernelRunProfile {

    override fun getOptions(): CosmosRunConfigurationOptions = super.getOptions() as CosmosRunConfigurationOptions

    var arch: String
        get() = options.arch
        set(value) {
            options.arch = value
        }

    var projectDir: String
        get() = options.projectDir
        set(value) {
            options.projectDir = value
        }

    var kernelName: String
        get() = options.kernelName
        set(value) {
            options.kernelName = value
        }

    override fun resolveKernelTarget(project: Project): KernelTarget = CosmosLaunch.resolveTarget(project, this)

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = CosmosRunConfigurationEditor()

    override fun checkConfiguration() {
        if (arch.isNotEmpty() && arch != "x64" && arch != "arm64") {
            throw RuntimeConfigurationError("Unknown architecture '$arch'")
        }
        CosmosLaunch.resolveTargetOrNull(project, this)
            ?: throw RuntimeConfigurationError("No Cosmos kernel project found")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        CosmosRunState(environment, this)
}

class CosmosRunConfigurationEditor : SettingsEditor<CosmosRunConfiguration>() {
    private val archCombo = ComboBox(arrayOf("", "x64", "arm64")).apply {
        renderer = SimpleListCellRenderer.create("Project default") { it.ifEmpty { "Project default" } }
    }

    override fun resetEditorFrom(configuration: CosmosRunConfiguration) {
        archCombo.selectedItem = configuration.arch
    }

    override fun applyEditorTo(configuration: CosmosRunConfiguration) {
        configuration.arch = archCombo.selectedItem as? String ?: ""
    }

    override fun createEditor(): JComponent = panel {
        row("Architecture:") {
            cell(archCombo)
                .comment("The kernel boots from output-&lt;arch&gt;; build it for that architecture first.")
        }
        row {
            comment("QEMU devices, memory and disks come from the Cosmos project properties.")
        }
    }
}
