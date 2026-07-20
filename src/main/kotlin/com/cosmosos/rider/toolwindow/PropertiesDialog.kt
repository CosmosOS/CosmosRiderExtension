package com.cosmosos.rider.toolwindow

import com.cosmosos.rider.util.ProjectConfig
import com.cosmosos.rider.util.ProjectProperties
import com.cosmosos.rider.util.QemuConfig
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

class PropertiesDialog(
    project: Project,
    private val csprojPath: String,
    private val props: ProjectProperties
) : DialogWrapper(project) {

    // General
    private val targetFrameworkCombo = ComboBox(arrayOf("net10.0"))
    private val targetArchCombo = ComboBox(arrayOf("x64", "arm64"))
    private val kernelClassField = JBTextField(props.kernelClass)

    // Features
    private val enableInterrupts = JCheckBox("Interrupts", props.enableInterrupts)
    private val enableTimer = JCheckBox("Timer", props.enableTimer)
    private val enableKeyboard = JCheckBox("Keyboard", props.enableKeyboard)
    private val enableMouse = JCheckBox("Mouse", props.enableMouse)
    private val enableNetwork = JCheckBox("Network", props.enableNetwork)
    private val enableScheduler = JCheckBox("Scheduler", props.enableScheduler)
    private val enableGraphics = JCheckBox("Graphics", props.enableGraphics)

    // Advanced
    private val gccFlagsField = JBTextField(props.gccFlags)

    // QEMU
    private val qemuMemoryCombo = ComboBox(arrayOf("256M", "512M", "1G", "2G", "4G"))
    private val qemuMachineTypeCombo = ComboBox<String>()
    private val qemuCpuModelCombo = ComboBox<String>()
    private val qemuSerialModeCombo = ComboBox(arrayOf("stdio", "none"))
    private val qemuEnableNetworkCheck = JCheckBox("Enable Network", props.qemu.enableNetwork)
    private val qemuNetworkPortsField = JBTextField(props.qemu.networkPorts)
    private val qemuExtraArgsField = JBTextField(props.qemu.extraArgs)

    // Feature toggle children that depend on parent state
    private val interruptChildren = listOf<JCheckBox>()

    init {
        title = "${props.name} - Properties"
        init()

        // Set initial values
        targetFrameworkCombo.selectedItem = props.targetFramework
        targetArchCombo.selectedItem = props.targetArch
        qemuMemoryCombo.selectedItem = props.qemu.memory
        qemuSerialModeCombo.selectedItem = props.qemu.serialMode

        updateArchDependentFields()
        updateFeatureVisibility()

        // Listeners
        targetArchCombo.addActionListener { updateArchDependentFields() }
        enableInterrupts.addActionListener { updateFeatureVisibility() }
        enableTimer.addActionListener { updateFeatureVisibility() }
        qemuEnableNetworkCheck.addActionListener {
            qemuNetworkPortsField.isEnabled = qemuEnableNetworkCheck.isSelected
        }
        qemuNetworkPortsField.isEnabled = qemuEnableNetworkCheck.isSelected
    }

    private fun updateArchDependentFields() {
        val arch = targetArchCombo.selectedItem as String

        qemuMachineTypeCombo.removeAllItems()
        qemuCpuModelCombo.removeAllItems()

        if (arch == "arm64") {
            qemuMachineTypeCombo.addItem("virt")
            qemuCpuModelCombo.addItem("cortex-a72")
            qemuCpuModelCombo.addItem("cortex-a53")
            qemuCpuModelCombo.addItem("max")
        } else {
            qemuMachineTypeCombo.addItem("q35")
            qemuMachineTypeCombo.addItem("pc")
            qemuCpuModelCombo.addItem("max")
            qemuCpuModelCombo.addItem("qemu64")
            qemuCpuModelCombo.addItem("host")
        }

        // Try to restore saved values
        qemuMachineTypeCombo.selectedItem = props.qemu.machineType
        qemuCpuModelCombo.selectedItem = props.qemu.cpuModel
    }

    private fun updateFeatureVisibility() {
        val interruptsOn = enableInterrupts.isSelected
        val timerOn = interruptsOn && enableTimer.isSelected

        enableTimer.isEnabled = interruptsOn
        enableKeyboard.isEnabled = interruptsOn
        enableMouse.isEnabled = interruptsOn
        enableNetwork.isEnabled = interruptsOn
        enableGraphics.isEnabled = interruptsOn
        enableScheduler.isEnabled = timerOn
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel()
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)

        mainPanel.add(createGeneralSection())
        mainPanel.add(Box.createVerticalStrut(12))
        mainPanel.add(createFeaturesSection())
        mainPanel.add(Box.createVerticalStrut(12))
        mainPanel.add(createAdvancedSection())
        mainPanel.add(Box.createVerticalStrut(12))
        mainPanel.add(createQemuSection())

        if (props.packages.isNotEmpty()) {
            mainPanel.add(Box.createVerticalStrut(12))
            mainPanel.add(createPackagesSection())
        }

        val scrollPane = JBScrollPane(mainPanel)
        scrollPane.preferredSize = java.awt.Dimension(500, 600)
        return scrollPane
    }

    private fun createSection(title: String, content: JPanel): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder(title)
        panel.add(content, BorderLayout.CENTER)
        return panel
    }

    private fun createGeneralSection(): JPanel {
        val content = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 4, 8)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        content.add(JBLabel(".NET Version:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        content.add(targetFrameworkCombo, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        content.add(JBLabel("Target Architecture:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        content.add(targetArchCombo, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        content.add(JBLabel("Kernel Entry Class:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        content.add(kernelClassField, gbc)

        return createSection("General", content)
    }

    private fun createFeaturesSection(): JPanel {
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)

        content.add(enableInterrupts)
        content.add(Box.createVerticalStrut(2))
        content.add(createIndented(enableTimer))
        content.add(Box.createVerticalStrut(2))
        content.add(createIndented(enableKeyboard))
        content.add(Box.createVerticalStrut(2))
        content.add(createIndented(enableMouse))
        content.add(Box.createVerticalStrut(2))
        content.add(createIndented(enableNetwork))
        content.add(Box.createVerticalStrut(2))
        content.add(createIndented(enableGraphics))
        content.add(Box.createVerticalStrut(2))
        content.add(createDoubleIndented(enableScheduler))

        return createSection("Features", content)
    }

    private fun createIndented(component: JComponent): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(0, 20, 0, 0)
        panel.add(component, BorderLayout.WEST)
        return panel
    }

    private fun createDoubleIndented(component: JComponent): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(0, 40, 0, 0)
        panel.add(component, BorderLayout.WEST)
        return panel
    }

    private fun createAdvancedSection(): JPanel {
        val content = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 4, 8)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        content.add(JBLabel("GCC Compiler Flags:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        content.add(gccFlagsField, gbc)

        return createSection("Advanced", content)
    }

    private fun createQemuSection(): JPanel {
        val content = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 4, 8)
        }

        var row = 0

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        content.add(JBLabel("Memory:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        content.add(qemuMemoryCombo, gbc)

        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        content.add(JBLabel("Machine Type:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        content.add(qemuMachineTypeCombo, gbc)

        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        content.add(JBLabel("CPU Model:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        content.add(qemuCpuModelCombo, gbc)

        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        content.add(JBLabel("Serial Output:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        content.add(qemuSerialModeCombo, gbc)

        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2
        content.add(qemuEnableNetworkCheck, gbc)
        gbc.gridwidth = 1

        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        content.add(JBLabel("Network Ports:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        content.add(qemuNetworkPortsField, gbc)

        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        content.add(JBLabel("Extra Arguments:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        content.add(qemuExtraArgsField, gbc)

        return createSection("QEMU Configuration", content)
    }

    private fun createPackagesSection(): JPanel {
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)

        for (pkg in props.packages) {
            val row = JPanel(BorderLayout())
            row.add(JBLabel(pkg.name), BorderLayout.WEST)
            row.add(JBLabel(pkg.version), BorderLayout.EAST)
            row.border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
            content.add(row)
        }

        return createSection("Packages", content)
    }

    fun getUpdatedProperties(): ProjectProperties {
        return props.copy(
            targetFramework = targetFrameworkCombo.selectedItem as String,
            targetArch = targetArchCombo.selectedItem as String,
            kernelClass = kernelClassField.text,
            enableInterrupts = enableInterrupts.isSelected,
            enableTimer = enableTimer.isSelected,
            enableKeyboard = enableKeyboard.isSelected,
            enableMouse = enableMouse.isSelected,
            enableNetwork = enableNetwork.isSelected,
            enableGraphics = enableGraphics.isSelected,
            enableScheduler = enableScheduler.isSelected,
            gccFlags = gccFlagsField.text,
            qemu = QemuConfig(
                memory = qemuMemoryCombo.selectedItem as String,
                machineType = qemuMachineTypeCombo.selectedItem as? String ?: "q35",
                cpuModel = qemuCpuModelCombo.selectedItem as? String ?: "max",
                serialMode = qemuSerialModeCombo.selectedItem as String,
                enableNetwork = qemuEnableNetworkCheck.isSelected,
                networkPorts = qemuNetworkPortsField.text,
                extraArgs = qemuExtraArgsField.text
            )
        )
    }

    override fun doOKAction() {
        val updated = getUpdatedProperties()
        ProjectConfig.saveProjectProperties(csprojPath, updated)
        ProjectConfig.saveQemuConfig(java.io.File(csprojPath).parent, updated.qemu)
        super.doOKAction()
    }
}
