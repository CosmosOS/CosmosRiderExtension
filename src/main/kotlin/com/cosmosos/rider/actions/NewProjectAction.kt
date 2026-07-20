package com.cosmosos.rider.actions

import com.cosmosos.rider.util.PlatformUtil
import com.cosmosos.rider.util.ProjectConfig
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import java.io.File

class NewProjectAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        // Check if cosmos is installed
        if (!PlatformUtil.isCosmosToolsInstalled) {
            val install = Messages.showYesNoDialog(
                project,
                "Cosmos Tools is required to create projects. Install now?\n\nRun: dotnet tool install -g Cosmos.Tools && cosmos install",
                "Cosmos Tools Required",
                Messages.getWarningIcon()
            )
            if (install != Messages.YES) return

            Messages.showInfoMessage(
                project,
                "Run the following command in a terminal:\n\ndotnet tool install -g Cosmos.Tools && cosmos install\n\nThen try creating the project again.",
                "Install Cosmos Tools"
            )
            return
        }

        // Check if templates are installed
        val templatesInstalled = PlatformUtil.execCommand("dotnet new list cosmos-kernel")
            ?.contains("cosmos-kernel") == true

        if (!templatesInstalled) {
            Messages.showInfoMessage(
                project,
                "Run the following command in a terminal:\n\ndotnet new install Cosmos.Build.Templates\n\nThen try creating the project again.",
                "Install Cosmos Templates"
            )
            return
        }

        // Ask for project name
        val projectName = Messages.showInputDialog(
            project,
            "Enter the kernel project name:",
            "New Cosmos Kernel Project",
            Messages.getQuestionIcon(),
            "MyKernel",
            null
        ) ?: return

        if (!projectName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*$"))) {
            Messages.showErrorDialog(
                project,
                "Project name must start with a letter and contain only letters, numbers, and underscores.",
                "Invalid Project Name"
            )
            return
        }

        // Ask for architecture
        val archOptions = arrayOf("x64 (Intel/AMD 64-bit)", "arm64 (ARM 64-bit)")
        val archChoice = Messages.showDialog(
            project,
            "Select target architecture:",
            "Target Architecture",
            archOptions,
            0,
            Messages.getQuestionIcon()
        )
        if (archChoice < 0) return
        val arch = if (archChoice == 1) "arm64" else "x64"

        // Ask for location
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        descriptor.title = "Select Project Location"
        val chosen = FileChooserFactory.getInstance().createPathChooser(descriptor, project, null)
        var projectPath: String? = null

        chosen.choose(null) { files ->
            if (files.isNotEmpty()) {
                projectPath = File(files[0].path, projectName).absolutePath
            }
        }

        val path = projectPath ?: return

        // Create the project
        try {
            val dir = File(path)
            dir.mkdirs()

            val cmd = "dotnet new cosmos-kernel -n $projectName --force"
            val result = PlatformUtil.execCommand(cmd, workDir = path, timeoutMs = 30000)

            if (result != null) {
                // Save architecture config
                val cosmosDir = File(path, ".cosmos")
                cosmosDir.mkdirs()
                val config = com.cosmosos.rider.util.CosmosConfigJson(
                    targetArch = arch,
                    qemu = ProjectConfig.getDefaultQemuConfig(arch)
                )
                ProjectConfig.saveCosmosConfig(path, config)

                Messages.showInfoMessage(
                    project,
                    "Cosmos kernel project '$projectName' created successfully!\nTarget: $arch\nLocation: $path\n\nOpen this folder in Rider to start developing.",
                    "Project Created"
                )

                // Try to open the project
                ProjectManager.getInstance().loadAndOpenProject(path)
            } else {
                Messages.showErrorDialog(
                    project,
                    "Failed to create project. Make sure Cosmos templates are installed:\ndotnet new install Cosmos.Build.Templates",
                    "Project Creation Failed"
                )
            }
        } catch (ex: Exception) {
            Messages.showErrorDialog(
                project,
                "Failed to create project: ${ex.message}",
                "Error"
            )
        }
    }
}
