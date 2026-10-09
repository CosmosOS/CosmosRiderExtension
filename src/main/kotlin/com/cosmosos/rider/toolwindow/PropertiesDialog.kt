package com.cosmosos.rider.toolwindow

import com.cosmosos.rider.services.CosmosProjectService
import com.cosmosos.rider.util.Choice
import com.cosmosos.rider.util.DiskConfig
import com.cosmosos.rider.util.ProjectConfig
import com.cosmosos.rider.util.ProjectProperties
import com.cosmosos.rider.util.QemuChoices
import com.cosmosos.rider.util.QemuConfig
import com.cosmosos.rider.util.QemuOptions
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.Component
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class PropertiesDialog(
    private val project: Project,
    private val csprojPath: String,
    private val props: ProjectProperties
) : DialogWrapper(project) {

    // A combo over QEMU option choices: entries without a kernel driver are
    // listed greyed out for reference and can't be picked.
    private class ChoiceCombo : ComboBox<Choice>() {
        private var last: Choice? = null

        init {
            renderer = object : SimpleListCellRenderer<Choice>() {
                override fun customize(list: JList<out Choice>, value: Choice?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    text = value?.label.orEmpty()
                    if (value != null && !value.supported) foreground = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
                }
            }
            addActionListener {
                val chosen = selectedItem as? Choice
                if (chosen != null && !chosen.supported) selectedItem = last else last = chosen
            }
        }

        fun setChoices(choices: List<Choice>, value: String) {
            removeAllItems()
            choices.forEach(::addItem)
            val pick = choices.firstOrNull { it.value == value && it.supported } ?: choices.firstOrNull { it.supported }
            last = pick
            selectedItem = pick
        }

        val value: String
            get() = (selectedItem as? Choice)?.value.orEmpty()
    }

    // General
    private val targetFrameworkCombo = ChoiceCombo().apply {
        setChoices(listOf(Choice("net10.0", ".NET 10")), props.targetFramework)
    }
    private val targetArchCombo = ChoiceCombo().apply {
        setChoices(listOf(Choice("x64", "x64 (Intel/AMD 64-bit)"), Choice("arm64", "ARM64")), props.targetArch)
    }
    private val kernelClassField = JBTextField(props.kernelClass)

    // Features
    private val interrupts = JBCheckBox("Interrupts", props.enableInterrupts)
    private val timer = JBCheckBox("Timer", props.enableTimer)
    private val keyboard = JBCheckBox("Keyboard support", props.enableKeyboard)
    private val mouse = JBCheckBox("Mouse support", props.enableMouse)
    private val network = JBCheckBox("Network support", props.enableNetwork)
    private val scheduler = JBCheckBox("Scheduler support", props.enableScheduler)
    private val pci = JBCheckBox("PCI support", props.enablePCI)
    private val storage = JBCheckBox("Storage support", props.enableStorage)
    private val fat = JBCheckBox("FAT filesystem", props.enableFat)
    private val audio = JBCheckBox("Audio support", props.enableAudio)
    private val graphics = JBCheckBox("Graphic support", props.enableGraphics)
    private val uart = JBCheckBox("UART / Serial", props.enableUART)

    // Advanced
    private val gccFlagsField = JBTextField(props.gccFlags).apply { emptyText.text = "Uses SDK defaults if empty" }

    // QEMU
    private val memoryCombo = ChoiceCombo()
    private val machineCombo = ChoiceCombo()
    private val cpuCombo = ChoiceCombo()
    private val serialCombo = ChoiceCombo()
    private val nicCombo = ChoiceCombo()
    private val keyboardCombo = ChoiceCombo()
    private val mouseCombo = ChoiceCombo()
    private val audioCombo = ChoiceCombo()
    private val portForwardsField = JBTextField(props.qemu.portForwards.joinToString(" ")).apply { emptyText.text = "tcp::2323-:23" }
    private val extraArgsField = JBTextField(props.qemu.extraArgs).apply { emptyText.text = "-device ich9-ahci" }

    private val disksModel = ListTableModel<DiskConfig>(
        object : ColumnInfo<DiskConfig, String>("Path") {
            override fun valueOf(item: DiskConfig) = item.path
            override fun isCellEditable(item: DiskConfig) = true
            override fun setValue(item: DiskConfig, value: String) {
                item.path = value
            }
        },
        object : ColumnInfo<DiskConfig, String>("Type") {
            override fun valueOf(item: DiskConfig) = item.type
            override fun isCellEditable(item: DiskConfig) = true
            override fun setValue(item: DiskConfig, value: String) {
                item.type = value
            }
            override fun getEditor(item: DiskConfig): TableCellEditor = DefaultCellEditor(ComboBox(QemuChoices.diskTypes.toTypedArray()))
            override fun getRenderer(item: DiskConfig): TableCellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
                ): Component = super.getTableCellRendererComponent(
                    table, if (value == "nvme") "NVMe" else "AHCI", isSelected, hasFocus, row, column
                )
            }
            override fun getWidth(table: JTable) = JBUI.scale(90)
        },
        object : ColumnInfo<DiskConfig, String>("Size") {
            override fun valueOf(item: DiskConfig) = item.size
            override fun isCellEditable(item: DiskConfig) = true
            override fun setValue(item: DiskConfig, value: String) {
                item.size = value
            }
            override fun getWidth(table: JTable) = JBUI.scale(80)
        }
    ).apply { items = props.qemu.disks.map { it.copy() } }
    private val disksTable = TableView(disksModel).apply {
        emptyText.text = "No disks attached."
        setShowGrid(false)
    }

    init {
        title = "${props.name} - Properties"
        setOKButtonText("Save")

        fillArchDependentCombos(props.targetArch, props.qemu)
        memoryCombo.setChoices(QemuChoices.memorySizes, props.qemu.memory)
        serialCombo.setChoices(QemuChoices.serialModes, props.qemu.serialMode)

        targetArchCombo.addActionListener {
            // Keep what the new architecture also offers, default the rest.
            fillArchDependentCombos(targetArchCombo.value, currentQemu())
        }
        listOf(interrupts, timer, pci, storage).forEach { it.addActionListener { updateFeatureStates() } }
        updateFeatureStates()

        init()
    }

    private fun fillArchDependentCombos(arch: String, current: QemuConfig) {
        val defaults = ProjectConfig.getDefaultQemuConfig(arch)
        fun pick(choices: List<Choice>, value: String, default: String) =
            if (QemuChoices.isSupported(choices, value)) value else default
        QemuChoices.machineTypes(arch).let { machineCombo.setChoices(it, pick(it, current.machineType, defaults.machineType)) }
        QemuChoices.cpuModels(arch).let { cpuCombo.setChoices(it, pick(it, current.cpuModel, defaults.cpuModel)) }
        QemuChoices.networkCards(arch).let { nicCombo.setChoices(it, pick(it, current.networkCard, defaults.networkCard)) }
        QemuChoices.keyboards(arch).let { keyboardCombo.setChoices(it, pick(it, current.keyboard, defaults.keyboard)) }
        QemuChoices.mice(arch).let { mouseCombo.setChoices(it, pick(it, current.mouse, defaults.mouse)) }
        QemuChoices.audioDevices(arch).let { audioCombo.setChoices(it, pick(it, current.audio, defaults.audio)) }
    }

    // Mirrors the cascade in Sdk.targets, which is what the build applies.
    // Graphics and UART are deliberately outside it: the SDK never cascades
    // them, so greying them out would misreport what the build keeps on.
    private fun updateFeatureStates() {
        val interruptsOn = interrupts.isSelected
        val timerOn = interruptsOn && timer.isSelected
        val pciOn = interruptsOn && pci.isSelected
        val storageOn = pciOn && storage.isSelected
        listOf(timer, keyboard, mouse, network, pci).forEach { it.isEnabled = interruptsOn }
        scheduler.isEnabled = timerOn
        storage.isEnabled = pciOn
        audio.isEnabled = pciOn
        fat.isEnabled = storageOn
    }

    override fun createCenterPanel(): JComponent {
        val content = panel {
            group("General") {
                row(".NET version:") { cell(targetFrameworkCombo) }
                row("Target architecture:") { cell(targetArchCombo) }
                row("Kernel entry class:") {
                    cell(kernelClassField).align(AlignX.FILL)
                        .comment("Fully qualified class name (e.g., MyKernel.Kernel)")
                }
            }

            group("Features") {
                row {
                    cell(interrupts).comment("Interrupt support; disabling also disables Timer, Keyboard, Mouse, Network, Scheduler, PCI and Storage")
                }
                indent {
                    row { cell(timer).comment("Timer support; disabling also disables Scheduler") }
                    indent {
                        row { cell(scheduler).comment("Process and thread scheduling") }
                    }
                    row { cell(keyboard).comment("Keyboard input handling") }
                    row { cell(mouse).comment("Mouse input handling") }
                    row { cell(network).comment("Network stack and drivers") }
                    row {
                        cell(pci).comment("PCI/PCIe bus enumeration. Every PCI device driver needs it — E1000E, AHCI, NVMe and VirtIO over PCI. Disabling also disables Storage")
                    }
                    indent {
                        row { cell(storage).comment("AHCI/SATA and NVMe block devices. Disabling also disables FAT") }
                        indent {
                            row { cell(fat).comment("FAT filesystem support, mounted on a storage block device") }
                        }
                        row { cell(audio).comment("HD Audio playback over PCI") }
                    }
                }
                row { cell(graphics).comment("Enable graphics display") }
                row {
                    cell(uart).comment("Serial port output. Disabling it silences the serial console the debugger and test runner read")
                }
            }

            collapsibleGroup("Advanced") {
                row("GCC compiler flags:") { cell(gccFlagsField).align(AlignX.FILL) }
            }

            group("QEMU Configuration") {
                group("Machine") {
                    row("Memory:") {
                        cell(memoryCombo).comment("How much RAM the virtual machine gives your kernel. More lets the kernel allocate more, but uses more host memory.")
                    }
                    row("Machine type:") {
                        cell(machineCombo).comment("The emulated motherboard/chipset. Q35 is the modern x64 default, PC the legacy i440FX; arm64 uses the generic ARM virt machine.")
                    }
                    row("CPU model:") {
                        cell(cpuCombo).comment("The processor QEMU emulates. \"Max\" exposes every CPU feature QEMU supports; \"Host\" passes your real CPU through (fastest, needs KVM).")
                    }
                    row("Serial output:") {
                        cell(serialCombo).comment("Where the kernel's serial console goes — the text from Console.Write / Serial output. \"Standard I/O\" streams it into the Run console.")
                    }
                }
                group("Devices") {
                    row("Network card:") {
                        cell(nicCombo).comment("The network adapter the kernel sees. Cards without a kernel driver are greyed out. x64 supports Intel E1000E and VirtIO over PCI (VirtIO needs PCI enabled); arm64 supports VirtIO over MMIO.")
                    }
                    row("Port forwards:") {
                        cell(portForwardsField).align(AlignX.FILL)
                            .comment("Host ports forwarded to the guest, separated by spaces, each as [tcp|udp]:[hostaddr]:hostport-[guestaddr]:guestport. tcp::2323-:23 reaches the guest's port 23 (Telnet) at localhost:2323. Needs a network card.")
                    }
                    row("Keyboard:") {
                        cell(keyboardCombo).comment("The keyboard device the kernel reads. x64 has PS/2 built into the q35 chipset, plus VirtIO over PCI (needs PCI enabled); the arm64 virt machine has no PS/2 and uses VirtIO over MMIO.")
                    }
                    row("Mouse:") {
                        cell(mouseCombo).comment("The pointing device the kernel reads. Same support as the keyboard.")
                    }
                    row("Audio:") {
                        cell(audioCombo).comment("The sound card the kernel plays through. A codec is attached alongside the controller and the host backend is QEMU's default; audio needs PCI enabled. The HD Audio driver has only been run on x64.")
                    }
                }
                group("Storage") {
                    row {
                        comment("Disk images attached to the kernel at boot. A missing image is created at the given size on launch. Paths are relative to the project folder.")
                    }
                    row {
                        val decorated = ToolbarDecorator.createDecorator(disksTable)
                            .setAddAction {
                                disksModel.addRow(DiskConfig())
                                val row = disksModel.rowCount - 1
                                disksTable.selectionModel.setSelectionInterval(row, row)
                                disksTable.editCellAt(row, 0)
                            }
                            .setRemoveAction {
                                disksTable.cellEditor?.stopCellEditing()
                                disksTable.selectedObjects.toList().forEach { disk ->
                                    disksModel.removeRow(disksModel.items.indexOf(disk))
                                }
                            }
                            .disableUpDownActions()
                            .setPreferredSize(JBUI.size(-1, 130))
                            .createPanel()
                        cell(decorated).align(Align.FILL)
                    }
                }
                group("Advanced") {
                    row("Extra arguments:") {
                        cell(extraArgsField).align(AlignX.FILL)
                            .comment("Raw flags appended to the QEMU launch command, for options not covered above (e.g. -device …). Leave empty if unsure.")
                    }
                }
            }

            group("Packages") {
                if (props.packages.isEmpty()) {
                    row { comment("No additional packages") }
                } else {
                    for (pkg in props.packages) {
                        row {
                            label(pkg.name)
                            comment(pkg.version)
                        }
                    }
                }
            }
        }

        return JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            preferredSize = JBUI.size(760, 640)
        }
    }

    override fun createLeftSideActions(): Array<Action> = arrayOf(object : AbstractAction("Open .csproj") {
        override fun actionPerformed(e: ActionEvent) {
            doCancelAction()
            LocalFileSystem.getInstance().refreshAndFindFileByPath(csprojPath)?.let {
                OpenFileDescriptor(project, it).navigate(true)
            }
        }
    })

    override fun doValidate(): ValidationInfo? {
        for (disk in disksModel.items) {
            if (disk.path.isBlank()) continue
            val size = disk.size.trim().ifEmpty { "256M" }
            val bytes = QemuOptions.parseSizeBytes(size)
            if (bytes == null || bytes <= 0) {
                return ValidationInfo("Invalid disk size \"$size\" for ${disk.path}", disksTable)
            }
        }
        return null
    }

    private fun currentQemu(): QemuConfig = props.qemu.copy(
        memory = memoryCombo.value,
        machineType = machineCombo.value,
        cpuModel = cpuCombo.value,
        serialMode = serialCombo.value,
        networkCard = nicCombo.value,
        portForwards = portForwardsField.text.split(Regex("[\\s,]+")).filter { it.isNotBlank() },
        keyboard = keyboardCombo.value,
        mouse = mouseCombo.value,
        audio = audioCombo.value,
        extraArgs = extraArgsField.text.trim(),
        disks = disksModel.items.filter { it.path.isNotBlank() }.map { it.copy(path = it.path.trim(), size = it.size.trim().ifEmpty { "256M" }) }
    )

    override fun doOKAction() {
        disksTable.cellEditor?.stopCellEditing()
        val updated = props.copy(
            targetFramework = targetFrameworkCombo.value.ifEmpty { "net10.0" },
            targetArch = targetArchCombo.value.ifEmpty { "x64" },
            kernelClass = kernelClassField.text.trim(),
            enableInterrupts = interrupts.isSelected,
            enableTimer = timer.isSelected,
            enableKeyboard = keyboard.isSelected,
            enableMouse = mouse.isSelected,
            enableNetwork = network.isSelected,
            enableScheduler = scheduler.isSelected,
            enablePCI = pci.isSelected,
            enableStorage = storage.isSelected,
            enableFat = fat.isSelected,
            enableAudio = audio.isSelected,
            enableGraphics = graphics.isSelected,
            enableUART = uart.isSelected,
            gccFlags = gccFlagsField.text.trim(),
            qemu = currentQemu()
        )
        try {
            ProjectConfig.saveProjectProperties(csprojPath, updated)
            ProjectConfig.saveQemuConfig(java.io.File(csprojPath).parent, updated.qemu)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Failed to save: ${e.message}", "Project Properties")
            return
        }
        CosmosProjectService.getInstance(project).fireProjectChanged()
        super.doOKAction()
    }
}
