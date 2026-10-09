package com.cosmosos.rider.run

import com.cosmosos.rider.debugger.CosmosDebugProcess
import com.cosmosos.rider.util.KernelArtifacts
import com.cosmosos.rider.util.PortUtil
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager

/**
 * Debug executor for Cosmos kernels: boots `cosmos run --debug` (QEMU halted,
 * gdbstub on 1234) and opens a [CosmosDebugProcess] on it. The
 * session owns cosmos, so Stop takes QEMU and gdb down together.
 */
class CosmosDebugRunner : GenericProgramRunner<RunnerSettings>() {

    override fun getRunnerId() = "CosmosKernelDebugRunner"

    override fun canRun(executorId: String, profile: RunProfile) =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is CosmosKernelRunProfile

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        FileDocumentManager.getInstance().saveAllDocuments()
        val project = environment.project
        val target = (environment.runProfile as CosmosKernelRunProfile).resolveKernelTarget(project)

        val elf = KernelArtifacts.resolveKernelElf(target.rootDir, target.projectDir, target.name, target.arch)
            ?: throw ExecutionException("No ELF for ${target.name} (${target.arch}). Rebuild before debugging.")

        if (PortUtil.isPortInUse(CosmosDebugProcess.GDB_PORT)) {
            throw ExecutionException(
                "Port ${CosmosDebugProcess.GDB_PORT} is already in use — a previous debug session left QEMU running. " +
                    "Run `pkill -f qemu-system` to clean up."
            )
        }

        val spec = CosmosLaunch.prepare(target, debug = true)

        val handler = KillableColoredProcessHandler(spec.commandLine)
        ProcessTerminatedListener.attach(handler)

        val session = try {
            XDebuggerManager.getInstance(project).startSession(environment, object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess =
                    CosmosDebugProcess(session, spec, elf, handler)
            })
        } catch (e: ExecutionException) {
            handler.destroyProcess()
            throw e
        }
        return session.runContentDescriptor
    }
}
