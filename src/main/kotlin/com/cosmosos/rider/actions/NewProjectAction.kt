package com.cosmosos.rider.actions

import com.cosmosos.rider.services.CosmosProjectService
import com.cosmosos.rider.util.CosmosNotifications
import com.cosmosos.rider.util.PlatformUtil
import com.cosmosos.rider.util.ProjectConfig
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class NewProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        if (!PlatformUtil.isCosmosToolsInstalled) {
            val install = Messages.showYesNoDialog(
                project,
                "Cosmos Tools is required to create projects. Install now?",
                "Cosmos Tools Required",
                Messages.getWarningIcon()
            )
            if (install != Messages.YES) return
            if (project != null) {
                CosmosCommands.runInstaller(project, "Cosmos Setup", "dotnet tool install -g Cosmos.Tools && cosmos install")
                Messages.showInfoMessage(
                    project,
                    "Installing Cosmos Tools. Run \"New Kernel Project\" again once the installation completes.",
                    "Install Cosmos Tools"
                )
            } else {
                Messages.showInfoMessage(
                    "Run the following command in a terminal, then try again:\n\ndotnet tool install -g Cosmos.Tools && cosmos install",
                    "Install Cosmos Tools"
                )
            }
            return
        }

        val templatesInstalled = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<Boolean, RuntimeException> {
                PlatformUtil.execCommand("dotnet new list cosmos-kernel", timeoutMs = 30000)?.contains("cosmos-kernel") == true
            },
            "Checking Cosmos templates", true, project
        )
        if (!templatesInstalled) {
            if (project != null) {
                CosmosCommands.runInstaller(project, "Cosmos Setup", "dotnet new install Cosmos.Build.Templates")
                Messages.showInfoMessage(
                    project,
                    "Installing Cosmos templates. Run \"New Kernel Project\" again once the installation completes.",
                    "Install Cosmos Templates"
                )
            } else {
                Messages.showInfoMessage(
                    "Run the following command in a terminal, then try again:\n\ndotnet new install Cosmos.Build.Templates",
                    "Install Cosmos Templates"
                )
            }
            return
        }

        val projectName = Messages.showInputDialog(
            project,
            "Enter the kernel project name:",
            "New Cosmos Kernel Project",
            Messages.getQuestionIcon(),
            "MyKernel",
            object : InputValidator {
                override fun checkInput(inputString: String) = NAME_REGEX.matches(inputString)
                override fun canClose(inputString: String) = checkInput(inputString)
            }
        ) ?: return

        val archOptions = arrayOf("x64 — Intel/AMD 64-bit", "arm64 — ARM 64-bit")
        val archChoice = Messages.showDialog(
            project,
            "Select target architecture:\n\nx64 is recommended for most users; arm64 targets Raspberry Pi and Apple Silicon VMs.",
            "Target Architecture",
            archOptions,
            0,
            Messages.getQuestionIcon()
        )
        if (archChoice < 0) return
        val arch = if (archChoice == 1) "arm64" else "x64"

        val basePath = project?.basePath
        val locationOptions = if (basePath != null) {
            arrayOf("Current Folder", "New Folder", "Choose Location…")
        } else {
            arrayOf("Choose Location…")
        }
        val locationChoice = Messages.showDialog(
            project,
            if (basePath != null) "Where should the project be created?\n\nCurrent folder: $basePath" else "Where should the project be created?",
            "Project Location",
            locationOptions,
            0,
            Messages.getQuestionIcon()
        )
        if (locationChoice < 0) return

        val createInCurrentDir = basePath != null && locationChoice == 0
        val projectPath = when {
            createInCurrentDir -> basePath!!
            basePath != null && locationChoice == 1 -> File(basePath, projectName).absolutePath
            else -> {
                val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("Select Project Location")
                val folder = FileChooser.chooseFile(descriptor, project, null) ?: return
                File(folder.path, projectName).absolutePath
            }
        }

        val result = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<Pair<Int, String>, RuntimeException> {
                createProject(projectPath, projectName, createInCurrentDir)
            },
            "Creating Cosmos kernel project", false, project
        )
        if (result.first != 0) {
            Messages.showErrorDialog(
                project,
                "Failed to create project (exit code ${result.first}).\n\n${result.second.takeLast(2000)}",
                "Project Creation Failed"
            )
            return
        }

        ProjectConfig.writeNewProjectConfig(projectPath, arch)

        val csproj = File(projectPath, "$projectName.csproj")
        val version = csproj.takeIf { it.isFile }?.readText()
            ?.let { Regex("""<PackageReference\s+Include="Cosmos.Kernel"\s+Version="([^"]+)"""").find(it)?.groupValues?.get(1) }
        val created = "Cosmos kernel \"$projectName\" created successfully! (Target: $arch${version?.let { ", Cosmos gen3 v$it" } ?: ""})"

        if (createInCurrentDir && project != null) {
            CosmosProjectService.getInstance(project).fireProjectChanged()
            CosmosNotifications.info(project, created)
        } else {
            CosmosNotifications.info(project, created)
            ProjectUtil.openOrImport(Path.of(if (csproj.isFile) csproj.absolutePath else projectPath), project, false)
        }
    }

    private fun createProject(projectPath: String, projectName: String, inCurrentDir: Boolean): Pair<Int, String> {
        return try {
            File(projectPath).mkdirs()
            // -o . keeps the files in the chosen folder instead of a subfolder.
            val args = mutableListOf(PlatformUtil.findCommand("dotnet") ?: "dotnet", "new", "cosmos-kernel", "-n", projectName)
            if (inCurrentDir) args += listOf("-o", ".")
            args += "--force"
            val process = ProcessBuilder(args)
                .directory(File(projectPath))
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File(if (SystemInfo.isWindows) "NUL" else "/dev/null")))
                .apply { environment().putAll(PlatformUtil.getEnvWithDotnetTools()) }
                .start()
            val output = StringBuilder()
            val reader = Thread { runCatching { output.append(process.inputStream.bufferedReader().readText()) } }
                .apply { isDaemon = true; start() }
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return -1 to "dotnet new timed out"
            }
            reader.join(1000)
            process.exitValue() to output.toString()
        } catch (ex: Exception) {
            -1 to (ex.message ?: ex.toString())
        }
    }

    companion object {
        private val NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*$")
    }
}
