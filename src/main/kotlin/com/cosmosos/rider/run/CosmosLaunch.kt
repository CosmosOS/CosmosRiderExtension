package com.cosmosos.rider.run

import com.cosmosos.rider.services.CosmosProjectService
import com.cosmosos.rider.util.KernelArtifacts
import com.cosmosos.rider.util.PlatformUtil
import com.cosmosos.rider.util.ProjectConfig
import com.cosmosos.rider.util.ProjectProperties
import com.cosmosos.rider.util.QemuOptions
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File

// The kernel a launch boots: the open project's, or an explicit one (a test
// kernel picked in the Tests tab).
data class KernelTarget(
    val name: String,
    val csprojPath: String,
    val projectDir: String,
    val arch: String,
    // Repo root holding the artifacts/ directory Cosmos.Sdk writes to.
    val rootDir: String?
)

// Everything needed to start `cosmos run` for one kernel.
data class CosmosLaunchSpec(
    val target: KernelTarget,
    val iso: File,
    val properties: ProjectProperties?,
    val commandLine: GeneralCommandLine,
    // Messages produced while preparing (created disk images, …).
    val notes: List<String>
)

object CosmosLaunch {

    fun resolveTargetOrNull(project: Project, config: CosmosRunConfiguration): KernelTarget? {
        if (config.projectDir.isNotEmpty() && config.kernelName.isNotEmpty()) {
            val csproj = File(config.projectDir, "${config.kernelName}.csproj")
            if (csproj.isFile) {
                return KernelTarget(
                    name = config.kernelName,
                    csprojPath = csproj.absolutePath,
                    projectDir = config.projectDir,
                    arch = config.arch.ifEmpty { "x64" },
                    rootDir = project.basePath
                )
            }
        }
        val info = CosmosProjectService.getInstance(project).getProjectInfo() ?: return null
        return KernelTarget(
            name = info.name,
            csprojPath = info.csprojPath,
            projectDir = info.projectDir,
            arch = config.arch.ifEmpty { info.arch },
            rootDir = project.basePath
        )
    }

    fun resolveTarget(project: Project, config: CosmosRunConfiguration): KernelTarget =
        resolveTargetOrNull(project, config) ?: throw ExecutionException("No Cosmos project found")

    fun findIso(target: KernelTarget): File {
        if (!KernelArtifacts.outputDirExists(target.projectDir, target.arch)) {
            throw ExecutionException("No build found for ${target.arch}. Build the kernel first.")
        }
        return KernelArtifacts.findIso(target.projectDir, target.arch)
            ?: throw ExecutionException("No ISO file found in output-${target.arch}. Please build the project first.")
    }

    /**
     * Builds the `cosmos run` command for [target]. Run and Debug share it so
     * the VM is the same either way; [debug] adds `--debug` (QEMU waits for gdb
     * on port 1234) and [qemuPassthrough] puts extra raw QEMU flags (the QMP
     * socket) in front of the project's own after `--`.
     */
    fun prepare(
        target: KernelTarget,
        debug: Boolean,
        qemuPassthrough: List<String> = emptyList()
    ): CosmosLaunchSpec {
        val iso = findIso(target)
        val cosmos = PlatformUtil.cosmosToolsPath
            ?: throw ExecutionException("cosmos CLI not installed. Install Cosmos.Tools as a dotnet global tool.")

        val notes = mutableListOf<String>()
        val args = mutableListOf("run", "-a", target.arch, "--iso", iso.absolutePath)
        if (debug) args += "--debug"

        val props = try {
            ProjectConfig.parseProjectProperties(target.csprojPath)
        } catch (e: Exception) {
            notes += "Could not read project properties (${e.message}); using defaults."
            null
        }

        val extraArgs = mutableListOf<String>()
        if (props != null) {
            // Headless only when the project turned graphics off.
            if (!props.enableGraphics) args += "--headless"
            QemuOptions.parseMemoryMb(props.qemu.memory)?.let { args += listOf("-m", it.toString()) }
            args += QemuOptions.buildCpuArgs(props.qemu.cpuModel)
            args += QemuOptions.buildNicArgs(props.qemu.networkCard)
            args += QemuOptions.buildHostForwardArgs(props.qemu.portForwards)
            args += QemuOptions.buildInputArgs(props.qemu.keyboard, props.qemu.mouse)
            args += QemuOptions.buildAudioArgs(props.qemu.audio)
            try {
                args += QemuOptions.prepareDiskArgs(target.projectDir, props.qemu.disks) { notes += it }
            } catch (e: Exception) {
                throw ExecutionException("Failed to prepare disk image: ${e.message}")
            }
            extraArgs += QemuOptions.splitExtraArgs(props.qemu.extraArgs)
        }

        // Raw QEMU flags go last, after `--`: cosmos run appends whatever
        // follows it to the QEMU command line as is.
        val passthrough = qemuPassthrough + extraArgs
        if (passthrough.isNotEmpty()) {
            args += "--"
            args += passthrough
        }

        val commandLine = PlatformUtil.createCommandLine(cosmos, *args.toTypedArray())
            .withWorkDirectory(target.projectDir)
            // QEMU dies under a piped stdin when launched from a GUI process
            // on Windows; give cosmos (and the QEMU it spawns) the null device.
            .withInput(File(if (SystemInfo.isWindows) "NUL" else "/dev/null"))

        return CosmosLaunchSpec(target, iso, props, commandLine, notes)
    }
}
