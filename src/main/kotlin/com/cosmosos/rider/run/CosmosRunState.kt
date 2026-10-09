package com.cosmosos.rider.run

import com.cosmosos.rider.util.PlatformUtil
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment

/**
 * Run executor state: boots the kernel through `cosmos run`, which owns the
 * QEMU command line. The Stop button kills cosmos and the QEMU under it.
 */
class CosmosRunState(
    environment: ExecutionEnvironment,
    private val configuration: CosmosRunConfiguration
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val target = CosmosLaunch.resolveTarget(environment.project, configuration)
        val spec = CosmosLaunch.prepare(target, debug = false)

        val handler = KillableColoredProcessHandler(spec.commandLine)
        val header = buildList {
            add("Running ${target.name} (${target.arch}) via cosmos run")
            add("Platform: ${PlatformUtil.platformName}")
            addAll(spec.notes)
            add("")
        }
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {
                handler.notifyTextAvailable(header.joinToString("\n", postfix = "\n"), ProcessOutputTypes.SYSTEM)
            }
        })
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}
