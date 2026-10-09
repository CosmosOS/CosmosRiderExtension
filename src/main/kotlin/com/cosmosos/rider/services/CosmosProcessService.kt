package com.cosmosos.rider.services

import com.cosmosos.rider.util.LogProcessor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

@Service(Service.Level.PROJECT)
class CosmosProcessService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): CosmosProcessService =
            project.getService(CosmosProcessService::class.java)
    }

    /**
     * Starts [commandLine] in a new Run tool window tab.
     *
     * With [joinWrappedLines] the output goes through [LogProcessor], which
     * strips ANSI and re-joins lines the Cosmos build hard-wraps at 80
     * columns; otherwise ANSI colors are rendered and the console forwards
     * typed input to the process.
     */
    fun runProcess(
        title: String,
        commandLine: GeneralCommandLine,
        header: List<String> = emptyList(),
        joinWrappedLines: Boolean = false,
        onExit: ((Int) -> Unit)? = null
    ): ProcessHandler {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val handler: ProcessHandler

        if (joinWrappedLines) {
            handler = OSProcessHandler(commandLine)
            val processor = LogProcessor(true) { console.print(it, ConsoleViewContentType.NORMAL_OUTPUT) }
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType == ProcessOutputTypes.SYSTEM) {
                        console.print(event.text, ConsoleViewContentType.SYSTEM_OUTPUT)
                    } else {
                        processor.append(event.text)
                    }
                }

                override fun processTerminated(event: ProcessEvent) = processor.flush()
            })
        } else {
            handler = KillableColoredProcessHandler(commandLine)
            console.attachToProcess(handler)
        }
        ProcessTerminatedListener.attach(handler)

        for (line in header) {
            console.print(line + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }

        val descriptor = RunContentDescriptor(console, handler, console.component, title)
        Disposer.register(descriptor, console)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)

        if (onExit != null) {
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    ApplicationManager.getApplication().invokeLater { onExit(event.exitCode) }
                }
            })
        }

        handler.startNotify()
        return handler
    }
}
