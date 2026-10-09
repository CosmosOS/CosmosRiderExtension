package com.cosmosos.rider.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import java.io.File

data class DiskConfig(
    // Image path, absolute or relative to the project directory.
    var path: String = "",
    // Controller the guest sees the disk through: "ahci" or "nvme".
    var type: String = "ahci",
    // Size used only when the image has to be created (e.g. "256M", "1G").
    var size: String = "256M"
)

data class QemuConfig(
    var memory: String = "512M",
    var machineType: String = "q35",
    var cpuModel: String = "max",
    var enableNetwork: Boolean = false,
    var networkPorts: String = "5555",
    var serialMode: String = "stdio",
    // QEMU NIC model exposed to the guest, or "none" for no network card.
    var networkCard: String = "none",
    // Host ports forwarded to the guest, each a QEMU hostfwd rule such as
    // "tcp::2323-:23". They ride on the network card's user-mode backend.
    var portForwards: List<String> = emptyList(),
    var keyboard: String = "ps2",
    var mouse: String = "ps2",
    // HD Audio controller model, or "none". The codec is the launcher's job.
    var audio: String = "none",
    var extraArgs: String = "",
    var disks: List<DiskConfig> = emptyList()
)

data class ProjectProperties(
    var name: String = "",
    var targetFramework: String = "net10.0",
    var targetArch: String = "x64",
    var kernelClass: String = "",
    var enableInterrupts: Boolean = true,
    var enableTimer: Boolean = true,
    var enableGraphics: Boolean = true,
    var enableKeyboard: Boolean = true,
    var enableMouse: Boolean = true,
    var enableNetwork: Boolean = true,
    var enableScheduler: Boolean = true,
    var enableUART: Boolean = true,
    var enablePCI: Boolean = true,
    var enableStorage: Boolean = true,
    var enableFat: Boolean = true,
    var enableAudio: Boolean = true,
    var gccFlags: String = "",
    var packages: List<PackageRef> = emptyList(),
    var qemu: QemuConfig = QemuConfig()
)

data class PackageRef(
    val name: String,
    val version: String
)

// One entry of a QEMU option list. Unsupported entries have no kernel driver
// (or don't exist on the architecture): the properties dialog shows them
// greyed out, and loading a config resets them to the default.
data class Choice(val value: String, val label: String, val supported: Boolean = true) {
    override fun toString() = label
}

object QemuChoices {
    val memorySizes = listOf(
        Choice("256M", "256 MB"),
        Choice("512M", "512 MB"),
        Choice("1G", "1 GB"),
        Choice("2G", "2 GB"),
        Choice("4G", "4 GB")
    )

    val serialModes = listOf(
        Choice("stdio", "Standard I/O (Run console)"),
        Choice("none", "Disabled")
    )

    fun machineTypes(arch: String) = if (arch == "arm64") listOf(
        Choice("virt", "Virt (ARM Virtual Machine)")
    ) else listOf(
        Choice("q35", "Q35 (Modern chipset)"),
        Choice("pc", "PC (Legacy i440FX)")
    )

    fun cpuModels(arch: String) = if (arch == "arm64") listOf(
        Choice("cortex-a72", "Cortex-A72"),
        Choice("cortex-a53", "Cortex-A53"),
        Choice("max", "Max (All features)")
    ) else listOf(
        Choice("max", "Max (All features)"),
        Choice("qemu64", "QEMU64 (Basic)"),
        Choice("host", "Host (Pass-through)")
    )

    // The virtio drivers are transport-agnostic: one VirtioNet binds over
    // virtio-mmio on the arm64 virt machine and over virtio-pci on q35, which
    // has no virtio-mmio window. The model is *-pci on x64 and *-device on
    // arm64 because those are genuinely different QEMU devices.
    fun networkCards(arch: String) = if (arch == "arm64") listOf(
        Choice("none", "None (no network card)"),
        Choice("virtio-net-device", "VirtIO (virtio-net-device)"),
        Choice("e1000e", "Intel E1000E — x64 only", supported = false)
    ) else listOf(
        Choice("none", "None (no network card)"),
        Choice("e1000e", "Intel E1000E (PCIe)"),
        Choice("virtio-net-pci", "VirtIO (virtio-net-pci)"),
        Choice("e1000", "Intel E1000 — no driver", supported = false),
        Choice("rtl8139", "Realtek RTL8139 — no driver", supported = false)
    )

    // x64 has PS/2 (i8042) built into q35, plus virtio-input over PCI; the
    // arm64 virt machine has no PS/2 controller and uses virtio-input over MMIO.
    fun keyboards(arch: String) = if (arch == "arm64") listOf(
        Choice("none", "None (no keyboard)"),
        Choice("virtio-keyboard-device", "VirtIO Keyboard (MMIO)"),
        Choice("ps2", "PS/2 — virt has no i8042", supported = false)
    ) else listOf(
        Choice("none", "None (no keyboard)"),
        Choice("ps2", "PS/2 (i8042)"),
        Choice("virtio-keyboard-pci", "VirtIO Keyboard (PCI)"),
        Choice("virtio-keyboard-device", "VirtIO Keyboard (MMIO) — arm64 only", supported = false)
    )

    fun mice(arch: String) = if (arch == "arm64") listOf(
        Choice("none", "None (no mouse)"),
        Choice("virtio-mouse-device", "VirtIO Mouse (MMIO)"),
        Choice("ps2", "PS/2 — virt has no i8042", supported = false)
    ) else listOf(
        Choice("none", "None (no mouse)"),
        Choice("ps2", "PS/2 (i8042)"),
        Choice("virtio-mouse-pci", "VirtIO Mouse (PCI)"),
        Choice("virtio-mouse-device", "VirtIO Mouse (MMIO) — arm64 only", supported = false)
    )

    // The HD Audio driver binds over PCI, which the arm64 virt machine also
    // has, but it has only been run on x64, so arm64 stays at "none".
    fun audioDevices(arch: String) = if (arch == "arm64") listOf(
        Choice("none", "None (no audio)"),
        Choice("intel-hda", "Intel HD Audio — x64 only", supported = false)
    ) else listOf(
        Choice("none", "None (no audio)"),
        Choice("intel-hda", "Intel HD Audio (ICH6)"),
        Choice("ich9-intel-hda", "Intel HD Audio (ICH9)"),
        Choice("ac97", "Intel AC97 — no driver", supported = false),
        Choice("es1370", "ENSONIQ ES1370 — no driver", supported = false)
    )

    val diskTypes = listOf("ahci", "nvme")

    fun isSupported(choices: List<Choice>, value: String) = choices.any { it.value == value && it.supported }
}

object ProjectConfig {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun getDefaultQemuConfig(arch: String): QemuConfig {
        return QemuConfig(
            memory = "512M",
            machineType = if (arch == "arm64") "virt" else "q35",
            cpuModel = if (arch == "arm64") "cortex-a72" else "max",
            enableNetwork = false,
            networkPorts = "5555",
            serialMode = "stdio",
            networkCard = "none",
            portForwards = emptyList(),
            // x64 gets PS/2 from the chipset; arm64 virt needs virtio-input.
            keyboard = if (arch == "arm64") "virtio-keyboard-device" else "ps2",
            mouse = if (arch == "arm64") "virtio-mouse-device" else "ps2",
            // Off by default: a project that never asked for audio launches
            // exactly as it did before the selector existed.
            audio = "none",
            extraArgs = "",
            disks = emptyList()
        )
    }

    private fun configFile(projectDir: String) = File(File(projectDir, ".cosmos"), "config.json")

    // The whole config.json as a JSON object, so saving one part keeps every
    // key this plugin doesn't know about.
    private fun readConfigJson(projectDir: String): JsonObject {
        val file = configFile(projectDir)
        return try {
            if (file.exists()) JsonParser.parseString(file.readText()).asJsonObject else JsonObject()
        } catch (_: Exception) {
            JsonObject()
        }
    }

    private fun writeConfigJson(projectDir: String, config: JsonObject) {
        val file = configFile(projectDir)
        file.parentFile.mkdirs()
        file.writeText(gson.toJson(config))
        refreshVfs(file)
    }

    // Lets the IDE see the edit without waiting for its file watcher.
    private fun refreshVfs(file: File) {
        if (ApplicationManager.getApplication() != null) VfsUtil.markDirtyAndRefresh(true, false, false, file)
    }

    fun loadTargetArch(projectDir: String): String {
        return readConfigJson(projectDir).string("targetArch")?.takeIf { it.isNotEmpty() } ?: "x64"
    }

    fun saveTargetArch(projectDir: String, arch: String) {
        val config = readConfigJson(projectDir)
        config.addProperty("targetArch", arch.ifEmpty { "x64" })
        writeConfigJson(projectDir, config)
    }

    fun loadQemuConfig(projectDir: String, arch: String): QemuConfig {
        val defaults = getDefaultQemuConfig(arch)
        val qemu = readConfigJson(projectDir).get("qemu")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return defaults

        val merged = defaults.copy(
            memory = qemu.string("memory") ?: defaults.memory,
            machineType = qemu.string("machineType") ?: defaults.machineType,
            cpuModel = qemu.string("cpuModel") ?: defaults.cpuModel,
            enableNetwork = qemu.boolean("enableNetwork") ?: defaults.enableNetwork,
            networkPorts = qemu.string("networkPorts") ?: defaults.networkPorts,
            serialMode = qemu.string("serialMode") ?: defaults.serialMode,
            networkCard = qemu.string("networkCard") ?: defaults.networkCard,
            portForwards = normalizePortForwards(qemu.get("portForwards")),
            keyboard = qemu.string("keyboard") ?: defaults.keyboard,
            mouse = qemu.string("mouse") ?: defaults.mouse,
            audio = qemu.string("audio") ?: defaults.audio,
            extraArgs = qemu.string("extraArgs") ?: defaults.extraArgs,
            disks = normalizeDisks(qemu.get("disks"))
        )

        // A value the architecture doesn't offer is reset to the default
        // rather than passed on to QEMU.
        if (!QemuChoices.isSupported(QemuChoices.machineTypes(arch), merged.machineType)) merged.machineType = defaults.machineType
        if (!QemuChoices.isSupported(QemuChoices.cpuModels(arch), merged.cpuModel)) merged.cpuModel = defaults.cpuModel
        if (!QemuChoices.isSupported(QemuChoices.networkCards(arch), merged.networkCard)) merged.networkCard = defaults.networkCard
        if (!QemuChoices.isSupported(QemuChoices.keyboards(arch), merged.keyboard)) merged.keyboard = defaults.keyboard
        if (!QemuChoices.isSupported(QemuChoices.mice(arch), merged.mouse)) merged.mouse = defaults.mouse
        if (!QemuChoices.isSupported(QemuChoices.audioDevices(arch), merged.audio)) merged.audio = defaults.audio

        return merged
    }

    // Port forwards may be absent in older configs, or hand-written as one
    // string; normalize to a clean list. A string is split on spaces and
    // commas, which no hostfwd rule contains.
    fun normalizePortForwards(value: JsonElement?): List<String> {
        val items: List<String> = when {
            value == null || value.isJsonNull -> emptyList()
            value.isJsonPrimitive -> value.asString.split(Regex("[\\s,]+"))
            value.isJsonArray -> value.asJsonArray.mapNotNull { e ->
                e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
            else -> emptyList()
        }
        return items.map { it.trim() }.filter { it.isNotEmpty() }
    }

    // Disks may be absent or malformed in older configs; normalize so
    // consumers never have to defend against it.
    private fun normalizeDisks(value: JsonElement?): List<DiskConfig> {
        if (value == null || !value.isJsonArray) return emptyList()
        return value.asJsonArray.mapNotNull { e ->
            val obj = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val path = obj.string("path") ?: return@mapNotNull null
            DiskConfig(
                path = path,
                type = if (obj.string("type") == "nvme") "nvme" else "ahci",
                size = obj.string("size")?.takeIf { it.isNotBlank() } ?: "256M"
            )
        }
    }

    fun saveQemuConfig(projectDir: String, qemu: QemuConfig) {
        val config = readConfigJson(projectDir)
        config.add("qemu", qemuToJson(qemu))
        writeConfigJson(projectDir, config)
    }

    fun qemuToJson(qemu: QemuConfig): JsonObject = JsonObject().apply {
        addProperty("memory", qemu.memory)
        addProperty("machineType", qemu.machineType)
        addProperty("cpuModel", qemu.cpuModel)
        addProperty("serialMode", qemu.serialMode)
        addProperty("networkCard", qemu.networkCard)
        add("portForwards", JsonArray().apply { qemu.portForwards.forEach { add(it) } })
        addProperty("keyboard", qemu.keyboard)
        addProperty("mouse", qemu.mouse)
        addProperty("audio", qemu.audio)
        // Legacy network toggle/ports are no longer editable; keep whatever
        // the project already had.
        addProperty("enableNetwork", qemu.enableNetwork)
        addProperty("networkPorts", qemu.networkPorts)
        addProperty("extraArgs", qemu.extraArgs)
        add("disks", JsonArray().apply {
            // Only rows that name a path are persisted; blank rows are UI scratch.
            qemu.disks.filter { it.path.isNotBlank() }.forEach { disk ->
                add(JsonObject().apply {
                    addProperty("path", disk.path.trim())
                    addProperty("type", if (disk.type == "nvme") "nvme" else "ahci")
                    addProperty("size", disk.size.trim().ifEmpty { "256M" })
                })
            }
        })
    }

    // Writes a fresh .cosmos/config.json for a newly created project.
    fun writeNewProjectConfig(projectDir: String, arch: String) {
        val config = JsonObject()
        config.addProperty("targetArch", arch)
        config.add("qemu", qemuToJson(getDefaultQemuConfig(arch)))
        writeConfigJson(projectDir, config)
    }

    fun parseProjectProperties(csprojPath: String): ProjectProperties {
        val file = File(csprojPath)
        val content = file.readText()
        val name = file.nameWithoutExtension
        val projectDir = file.parent

        fun getProperty(prop: String): String {
            val raw = Regex("<$prop>([^<]*)</$prop>").find(content)?.groupValues?.get(1) ?: ""
            return StringUtil.unescapeXmlEntities(raw)
        }

        val packages = Regex("""<PackageReference\s+Include="([^"]+)"\s+Version="([^"]+)"""")
            .findAll(content)
            .filter { !it.groupValues[1].startsWith("Cosmos.Build.") && !it.groupValues[1].startsWith("Cosmos.Kernel.Native") }
            .map { PackageRef(it.groupValues[1], it.groupValues[2]) }
            .toList()

        val targetArch = loadTargetArch(projectDir)

        return ProjectProperties(
            name = name,
            targetFramework = getProperty("TargetFramework").ifEmpty { "net10.0" },
            targetArch = targetArch,
            kernelClass = getProperty("CosmosKernelClass").ifEmpty { "$name.Kernel" },
            enableInterrupts = getProperty("CosmosEnableInterrupts") != "false",
            enableTimer = getProperty("CosmosEnableTimer") != "false",
            enableGraphics = getProperty("CosmosEnableGraphics") != "false",
            enableKeyboard = getProperty("CosmosEnableKeyboard") != "false",
            enableMouse = getProperty("CosmosEnableMouse") != "false",
            enableNetwork = getProperty("CosmosEnableNetwork") != "false",
            enableScheduler = getProperty("CosmosEnableScheduler") != "false",
            enableUART = getProperty("CosmosEnableUART") != "false",
            enablePCI = getProperty("CosmosEnablePCI") != "false",
            enableStorage = getProperty("CosmosEnableStorage") != "false",
            enableFat = getProperty("CosmosEnableFat") != "false",
            enableAudio = getProperty("CosmosEnableAudio") != "false",
            gccFlags = getProperty("GCCCompilerFlags"),
            packages = packages,
            qemu = loadQemuConfig(projectDir, targetArch)
        )
    }

    fun saveProjectProperties(csprojPath: String, props: ProjectProperties) {
        val file = File(csprojPath)
        var content = file.readText()

        // Replacement text is escaped (or produced by a lambda) so a '$' or
        // '\' in a value is written literally, not read as a group reference.
        fun setProperty(prop: String, value: String) {
            val element = "<$prop>${StringUtil.escapeXmlEntities(value)}</$prop>"
            val regex = Regex("<$prop>[^<]*</$prop>")
            if (regex.containsMatchIn(content)) {
                content = regex.replaceFirst(content, Regex.escapeReplacement(element))
            } else {
                // Add to the first PropertyGroup, with or without attributes.
                val pgMatch = Regex("<PropertyGroup[^>]*>").find(content)
                if (pgMatch != null) {
                    content = content.replaceRange(pgMatch.range, "${pgMatch.value}\n    $element")
                }
            }
        }

        fun removeProperty(prop: String) {
            content = Regex("""\s*<$prop>[^<]*</$prop>""").replace(content) { "" }
        }

        setProperty("TargetFramework", props.targetFramework.ifEmpty { "net10.0" })

        // The architecture lives in .cosmos/config.json, not the csproj.
        saveTargetArch(file.parent, props.targetArch)

        if (props.kernelClass.isNotEmpty()) setProperty("CosmosKernelClass", props.kernelClass)

        if (props.gccFlags.isNotEmpty()) setProperty("GCCCompilerFlags", props.gccFlags)
        else removeProperty("GCCCompilerFlags")

        // Feature switches: enabled is the SDK default, so it's written as an
        // absent property; only a disabled feature gets an explicit "false".
        val features = linkedMapOf(
            "CosmosEnableInterrupts" to props.enableInterrupts,
            "CosmosEnableTimer" to props.enableTimer,
            "CosmosEnableGraphics" to props.enableGraphics,
            "CosmosEnableKeyboard" to props.enableKeyboard,
            "CosmosEnableMouse" to props.enableMouse,
            "CosmosEnableNetwork" to props.enableNetwork,
            "CosmosEnableScheduler" to props.enableScheduler,
            "CosmosEnableUART" to props.enableUART,
            "CosmosEnablePCI" to props.enablePCI,
            "CosmosEnableStorage" to props.enableStorage,
            "CosmosEnableFat" to props.enableFat,
            "CosmosEnableAudio" to props.enableAudio
        )
        for ((prop, enabled) in features) {
            if (enabled) removeProperty(prop) else setProperty(prop, "false")
        }

        file.writeText(content)
        refreshVfs(file)
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.boolean(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
