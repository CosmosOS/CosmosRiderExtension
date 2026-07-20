package com.cosmosos.rider.services

import com.cosmosos.rider.util.PlatformUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File

@Service(Service.Level.PROJECT)
class CosmosProcessService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): CosmosProcessService =
            project.getService(CosmosProcessService::class.java)
    }

    fun runProcess(
        title: String,
        executable: String,
        args: List<String>,
        workDir: String? = null,
        extraEnv: Map<String, String> = emptyMap(),
        onExit: ((Int) -> Unit)? = null
    ): OSProcessHandler {
        val commandLine = PlatformUtil.createCommandLine(executable, *args.toTypedArray())
        if (workDir != null) commandLine.workDirectory = File(workDir)
        commandLine.environment.putAll(extraEnv)

        val handler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)

        val descriptor = RunContentDescriptor(console, handler, console.component, title)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)

        if (onExit != null) {
            handler.addProcessListener(object : ProcessAdapter() {
                override fun processTerminated(event: ProcessEvent) {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        onExit(event.exitCode)
                    }
                }
            })
        }

        handler.startNotify()
        return handler
    }
}
