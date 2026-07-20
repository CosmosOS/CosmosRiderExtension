package com.cosmosos.rider.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

data class QemuConfig(
    var memory: String = "512M",
    var machineType: String = "q35",
    var cpuModel: String = "max",
    var enableNetwork: Boolean = false,
    var networkPorts: String = "5555",
    var serialMode: String = "stdio",
    var extraArgs: String = ""
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
    var gccFlags: String = "",
    var packages: List<PackageRef> = emptyList(),
    var qemu: QemuConfig = QemuConfig()
)

data class PackageRef(
    val name: String,
    val version: String
)

data class CosmosConfigJson(
    var targetArch: String? = null,
    var qemu: QemuConfig? = null
)

object ProjectConfig {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val x64MachineTypes = listOf("q35", "pc")
    private val arm64MachineTypes = listOf("virt")
    private val x64CpuModels = listOf("max", "qemu64", "host")
    private val arm64CpuModels = listOf("cortex-a72", "cortex-a53", "max")

    fun getDefaultQemuConfig(arch: String): QemuConfig {
        return QemuConfig(
            memory = "512M",
            machineType = if (arch == "arm64") "virt" else "q35",
            cpuModel = if (arch == "arm64") "cortex-a72" else "max",
            enableNetwork = false,
            networkPorts = "5555",
            serialMode = "stdio",
            extraArgs = ""
        )
    }

    fun loadCosmosConfig(projectDir: String): CosmosConfigJson {
        val configFile = File(projectDir, ".cosmos/config.json")
        return try {
            if (configFile.exists()) {
                gson.fromJson(configFile.readText(), CosmosConfigJson::class.java) ?: CosmosConfigJson()
            } else {
                CosmosConfigJson()
            }
        } catch (_: Exception) {
            CosmosConfigJson()
        }
    }

    fun saveCosmosConfig(projectDir: String, config: CosmosConfigJson) {
        val cosmosDir = File(projectDir, ".cosmos")
        cosmosDir.mkdirs()
        File(cosmosDir, "config.json").writeText(gson.toJson(config))
    }

    fun loadQemuConfig(projectDir: String, arch: String): QemuConfig {
        val defaults = getDefaultQemuConfig(arch)
        val config = loadCosmosConfig(projectDir)
        val qemu = config.qemu ?: return defaults

        val merged = qemu.copy()

        // Validate machine type matches architecture
        if (arch == "arm64") {
            if (merged.machineType !in arm64MachineTypes) merged.machineType = defaults.machineType
            if (merged.cpuModel !in arm64CpuModels) merged.cpuModel = defaults.cpuModel
        } else {
            if (merged.machineType !in x64MachineTypes) merged.machineType = defaults.machineType
            if (merged.cpuModel !in x64CpuModels) merged.cpuModel = defaults.cpuModel
        }

        return merged
    }

    fun saveQemuConfig(projectDir: String, qemu: QemuConfig) {
        val config = loadCosmosConfig(projectDir)
        config.qemu = qemu
        saveCosmosConfig(projectDir, config)
    }

    fun parseProjectProperties(csprojPath: String): ProjectProperties {
        val file = File(csprojPath)
        val content = file.readText()
        val name = file.nameWithoutExtension
        val projectDir = file.parent

        fun getProperty(prop: String): String {
            val regex = Regex("<$prop>([^<]*)</$prop>")
            return regex.find(content)?.groupValues?.get(1) ?: ""
        }

        // Get package references
        val packageRegex = Regex("""<PackageReference\s+Include="([^"]+)"\s+Version="([^"]+)"""")
        val packages = packageRegex.findAll(content)
            .filter { !it.groupValues[1].startsWith("Cosmos.Build.") && !it.groupValues[1].startsWith("Cosmos.Kernel.Native") }
            .map { PackageRef(it.groupValues[1], it.groupValues[2]) }
            .toList()

        // Read arch from config
        val cosmosConfig = loadCosmosConfig(projectDir)
        val targetArch = cosmosConfig.targetArch ?: "x64"

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
            gccFlags = getProperty("GCCCompilerFlags"),
            packages = packages,
            qemu = loadQemuConfig(projectDir, targetArch)
        )
    }

    fun saveProjectProperties(csprojPath: String, props: ProjectProperties) {
        val file = File(csprojPath)
        var content = file.readText()
        val projectDir = file.parent

        fun setProperty(prop: String, value: String) {
            val regex = Regex("<$prop>[^<]*</$prop>")
            if (regex.containsMatchIn(content)) {
                content = content.replace(regex, "<$prop>$value</$prop>")
            } else {
                val pgMatch = Regex("<PropertyGroup[^>]*>").find(content)
                if (pgMatch != null) {
                    content = content.replaceFirst(pgMatch.value, "${pgMatch.value}\n    <$prop>$value</$prop>")
                }
            }
        }

        fun removeProperty(prop: String) {
            content = content.replace(Regex("""\s*<$prop>[^<]*</$prop>"""), "")
        }

        setProperty("TargetFramework", props.targetFramework.ifEmpty { "net10.0" })

        // Save targetArch to .cosmos/config.json
        val config = loadCosmosConfig(projectDir)
        config.targetArch = props.targetArch.ifEmpty { "x64" }
        saveCosmosConfig(projectDir, config)

        if (props.kernelClass.isNotEmpty()) setProperty("CosmosKernelClass", props.kernelClass)

        if (props.gccFlags.isNotEmpty()) setProperty("GCCCompilerFlags", props.gccFlags)
        else removeProperty("GCCCompilerFlags")

        // Feature toggles: remove property when enabled (default), set to false when disabled
        val features = mapOf(
            "CosmosEnableInterrupts" to props.enableInterrupts,
            "CosmosEnableTimer" to props.enableTimer,
            "CosmosEnableGraphics" to props.enableGraphics,
            "CosmosEnableKeyboard" to props.enableKeyboard,
            "CosmosEnableMouse" to props.enableMouse,
            "CosmosEnableNetwork" to props.enableNetwork,
            "CosmosEnableScheduler" to props.enableScheduler
        )
        for ((prop, enabled) in features) {
            if (enabled) removeProperty(prop) else setProperty(prop, "false")
        }

        file.writeText(content)
    }
}
