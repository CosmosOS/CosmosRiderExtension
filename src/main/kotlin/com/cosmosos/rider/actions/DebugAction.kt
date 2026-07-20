package com.cosmosos.rider.actions

import com.cosmosos.rider.services.CosmosProcessService
import com.cosmosos.rider.services.CosmosProjectService
import com.cosmosos.rider.util.PlatformUtil
import com.cosmosos.rider.util.ProjectConfig
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import java.io.File

class DebugAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = CosmosProjectService.getInstance(project)
        val projectInfo = service.getProjectInfo()

        if (projectInfo == null) {
            Messages.showErrorDialog(project, "No Cosmos project found", "Debug Error")
            return
        }

        val arch = projectInfo.arch
        val outputDir = File(projectInfo.projectDir, "output-$arch")
        val binDir = File(projectInfo.projectDir, "bin/Debug/net10.0/linux-$arch")

        // Check if build exists
        if (!outputDir.exists()) {
            val build = Messages.showYesNoDialog(
                project,
                "No build found for $arch. Build first?",
                "Build Required",
                Messages.getWarningIcon()
            )
            if (build == Messages.YES) {
                BuildAction().actionPerformed(e)
            }
            return
        }

        // Find ISO
        val isoFiles = outputDir.listFiles()?.filter { it.name.endsWith(".iso") } ?: emptyList()
        if (isoFiles.isEmpty()) {
            Messages.showErrorDialog(project, "No ISO file found. Please build the project first.", "Debug Error")
            return
        }

        // Find ELF for debugging symbols
        var elfPath: String? = null
        if (binDir.exists()) {
            val elfFiles = binDir.listFiles()?.filter { it.name.endsWith(".elf") } ?: emptyList()
            if (elfFiles.isNotEmpty()) {
                elfPath = elfFiles[0].absolutePath
            }
        }

        if (elfPath == null) {
            val build = Messages.showYesNoDialog(
                project,
                "Build incomplete for $arch (missing ELF for debugging). Rebuild?",
                "Build Required",
                Messages.getWarningIcon()
            )
            if (build == Messages.YES) {
                BuildAction().actionPerformed(e)
            }
            return
        }

        val isoPath = isoFiles[0].absolutePath
        val gdbPort = 1234
        val props = ProjectConfig.parseProjectProperties(projectInfo.csprojPath)
        val qemuConfig = props.qemu
        val platformInfo = PlatformUtil.getPlatformInfo()

        val qemuCmd: String
        val qemuArgs = mutableListOf<String>()

        if (arch == "x64") {
            qemuCmd = PlatformUtil.findCommand("qemu-system-x86_64") ?: "qemu-system-x86_64"
            qemuArgs.addAll(listOf(
                "-M", qemuConfig.machineType,
                "-cpu", qemuConfig.cpuModel,
                "-m", qemuConfig.memory,
                "-cdrom", isoPath,
                "-display", if (!props.enableGraphics) "none" else platformInfo.qemuDisplay,
                "-vga", "std",
                "-no-reboot", "-no-shutdown",
                "-s", "-S"  // GDB server on port 1234, freeze CPU at startup
            ))

            if (qemuConfig.serialMode == "stdio") {
                qemuArgs.addAll(listOf("-serial", "stdio"))
            }

            if (qemuConfig.enableNetwork) {
                val ports = qemuConfig.networkPorts.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val portForwards = ports.joinToString(",") { "hostfwd=udp::$it-:$it" }
                qemuArgs.addAll(listOf("-netdev", "user,id=net0${if (portForwards.isNotEmpty()) ",$portForwards" else ""}"))
                qemuArgs.addAll(listOf("-device", "e1000,netdev=net0"))
            } else {
                qemuArgs.addAll(listOf("-nic", "none"))
            }
        } else {
            qemuCmd = PlatformUtil.findCommand("qemu-system-aarch64") ?: "qemu-system-aarch64"
            qemuArgs.addAll(listOf(
                "-M", qemuConfig.machineType,
                "-cpu", qemuConfig.cpuModel,
                "-m", qemuConfig.memory
            ))

            val biosPath = platformInfo.arm64UefiBios
            if (biosPath != null) {
                qemuArgs.addAll(listOf("-bios", biosPath))
            }

            qemuArgs.addAll(listOf(
                "-drive", "if=none,id=cd,file=$isoPath",
                "-device", "virtio-scsi-pci",
                "-device", "scsi-cd,drive=cd,bootindex=0",
                "-device", "virtio-keyboard-device",
                "-device", "ramfb",
                "-display", if (!props.enableGraphics) "none" else "${platformInfo.qemuDisplay},show-cursor=on",
                "-nic", "none",
                "-s", "-S"
            ))

            if (qemuConfig.serialMode == "stdio") {
                qemuArgs.addAll(listOf("-serial", "stdio"))
            }
        }

        // Add extra arguments
        if (qemuConfig.extraArgs.isNotBlank()) {
            qemuArgs.addAll(qemuConfig.extraArgs.split(" ").filter { it.isNotEmpty() })
        }

        // Start QEMU with GDB server
        val processService = CosmosProcessService.getInstance(project)
        processService.runProcess(
            title = "Debug ${projectInfo.name} ($arch) - QEMU",
            executable = qemuCmd,
            args = qemuArgs,
            workDir = projectInfo.projectDir
        )

        // Notify user to connect GDB
        Messages.showInfoMessage(
            project,
            """QEMU started with GDB server on port $gdbPort.
               |CPU is frozen, waiting for debugger connection.
               |
               |ELF file: $elfPath
               |
               |To connect GDB, create a "GDB Remote Debug" run configuration in Rider:
               |  - Target: Remote
               |  - Host: localhost
               |  - Port: $gdbPort
               |  - Symbol file: $elfPath
               |
               |Or run manually:
               |  ${platformInfo.gdbCommand} -ex "target remote localhost:$gdbPort" "$elfPath"
            """.trimMargin(),
            "GDB Debug Session"
        )
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
                CosmosProjectService.getInstance(project).isCosmosProject()
    }
}
