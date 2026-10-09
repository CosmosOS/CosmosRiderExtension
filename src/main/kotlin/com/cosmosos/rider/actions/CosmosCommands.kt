package com.cosmosos.rider.actions

import com.cosmosos.rider.run.CosmosRunConfiguration
import com.cosmosos.rider.run.CosmosRunConfigurationType
import com.cosmosos.rider.services.CosmosProcessService
import com.cosmosos.rider.services.CosmosProjectService
import com.cosmosos.rider.services.ProjectInfo
import com.cosmosos.rider.util.CosmosNotifications
import com.cosmosos.rider.util.KernelArtifacts
import com.cosmosos.rider.util.PlatformUtil
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo

object CosmosCommands {

    fun projectInfoOrError(project: Project, title: String): ProjectInfo? {
        val info = CosmosProjectService.getInstance(project).getProjectInfo()
        if (info == null) Messages.showErrorDialog(project, "No Cosmos project found", title)
        return info
    }

    /** Asks Debug/Release, then runs `cosmos build` in the Run tool window. */
    fun build(project: Project, info: ProjectInfo, arch: String = info.arch) {
        val configs = arrayOf("Debug", "Release")
        val choice = Messages.showDialog(
            project,
            "Debug builds keep symbols for the debugger; Release builds are optimized.",
            "Build Configuration",
            configs,
            0,
            Messages.getQuestionIcon()
        )
        if (choice < 0) return
        val config = configs[choice]

        val cosmos = PlatformUtil.findCommand("cosmos") ?: "cosmos"
        val args = listOf("build", "-p", info.projectDir, "-a", arch, "-c", config, "-v")
        val commandLine = PlatformUtil.createCommandLine(cosmos, *args.toTypedArray())
            .withWorkDirectory(info.projectDir)
            // Wide columns and CI mode keep the build log from wrapping and
            // from drawing progress bars.
            .withEnvironment(mapOf("COLUMNS" to "1000", "CI" to "true"))

        CosmosProcessService.getInstance(project).runProcess(
            title = "Build ${info.name} ($arch)",
            commandLine = commandLine,
            header = listOf("Building ${info.name} for $arch ($config)...", "", "> cosmos ${args.joinToString(" ")}", ""),
            joinWrappedLines = true
        ) { exitCode ->
            if (exitCode == 0) {
                CosmosNotifications.info(project, "Build completed: ${info.name} ($arch)")
            } else {
                CosmosNotifications.error(project, "Build failed with exit code $exitCode")
            }
        }
    }

    // The run configuration Run/Debug use for the open project, created on
    // first need (the counterpart of VS Code's launch.json entry).
    fun kernelRunConfiguration(project: Project, select: Boolean = false): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val existing = runManager.getConfigurationSettingsList(CosmosRunConfigurationType.getInstance())
            .firstOrNull { (it.configuration as? CosmosRunConfiguration)?.projectDir.isNullOrEmpty() }
        if (existing != null) return existing

        val settings = runManager.createConfiguration("Cosmos Kernel", CosmosRunConfigurationType.getInstance().factory)
        runManager.addConfiguration(settings)
        if (select || runManager.selectedConfiguration == null) runManager.selectedConfiguration = settings
        return settings
    }

    /**
     * Run or Debug the open project's kernel. A missing build is offered
     * instead of failing; debugging also needs the ELF for symbols.
     */
    fun launch(project: Project, executor: Executor) {
        val title = if (executor.id == DefaultDebugExecutor.EXECUTOR_ID) "Debug" else "Run"
        val info = projectInfoOrError(project, "$title Error") ?: return
        val arch = info.arch

        if (!KernelArtifacts.outputDirExists(info.projectDir, arch)) {
            if (Messages.showYesNoDialog(project, "No build found for $arch. Build first?", "Build Required", Messages.getWarningIcon()) == Messages.YES) {
                build(project, info, arch)
            }
            return
        }
        if (KernelArtifacts.findIso(info.projectDir, arch) == null) {
            Messages.showErrorDialog(project, "No ISO file found. Please build the project first.", "$title Error")
            return
        }
        if (executor.id == DefaultDebugExecutor.EXECUTOR_ID &&
            KernelArtifacts.resolveKernelElf(project.basePath, info.projectDir, info.name, arch) == null
        ) {
            if (Messages.showYesNoDialog(
                    project,
                    "Build incomplete for $arch (missing ELF for debugging). Rebuild?",
                    "Build Required",
                    Messages.getWarningIcon()
                ) == Messages.YES
            ) {
                build(project, info, arch)
            }
            return
        }

        ProgramRunnerUtil.executeConfiguration(kernelRunConfiguration(project), executor)
    }

    /**
     * Runs a shell command in the Run tool window through a pseudo-terminal,
     * so installers that prompt (sudo, confirmations) can be answered there.
     */
    fun runInstaller(project: Project, title: String, command: String, onExit: ((Int) -> Unit)? = null) {
        val shell = if (SystemInfo.isWindows) listOf("cmd.exe", "/c", command) else listOf("/bin/sh", "-c", command)
        val commandLine: GeneralCommandLine = PtyCommandLine(shell)
            .withInitialColumns(160)
            .withEnvironment("PATH", PlatformUtil.pathWithCosmosTools)
            .withCharset(Charsets.UTF_8)
        project.basePath?.let { commandLine.withWorkDirectory(it) }
        CosmosProcessService.getInstance(project).runProcess(title, commandLine, header = listOf("> $command", ""), onExit = onExit)
    }
}

/** Base for actions that need an open Cosmos project. */
abstract class CosmosProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && CosmosProjectService.getInstance(project).isCosmosProject()
    }
}
