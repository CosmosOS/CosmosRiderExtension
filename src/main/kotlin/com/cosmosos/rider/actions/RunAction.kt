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

class RunAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = CosmosProjectService.getInstance(project)
        val projectInfo = service.getProjectInfo()

        if (projectInfo == null) {
            Messages.showErrorDialog(project, "No Cosmos project found", "Run Error")
            return
        }

        val arch = projectInfo.arch
        val outputDir = File(projectInfo.projectDir, "output-$arch")

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

        val isoFiles = outputDir.listFiles()?.filter { it.name.endsWith(".iso") } ?: emptyList()
        if (isoFiles.isEmpty()) {
            Messages.showErrorDialog(project, "No ISO file found. Please build the project first.", "Run Error")
            return
        }

        val isoPath = isoFiles[0].absolutePath
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
                "-no-reboot", "-no-shutdown"
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
                "-nic", "none"
            ))

            if (qemuConfig.serialMode == "stdio") {
                qemuArgs.addAll(listOf("-serial", "stdio"))
            }
        }

        // Add extra arguments
        if (qemuConfig.extraArgs.isNotBlank()) {
            qemuArgs.addAll(qemuConfig.extraArgs.split(" ").filter { it.isNotEmpty() })
        }

        val processService = CosmosProcessService.getInstance(project)
        processService.runProcess(
            title = "Run ${projectInfo.name} ($arch)",
            executable = qemuCmd,
            args = qemuArgs,
            workDir = projectInfo.projectDir
        )
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
                CosmosProjectService.getInstance(project).isCosmosProject()
    }
}
