package com.cosmosos.rider.actions

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.actionSystem.AnActionEvent

// Starts a Cosmos debug session: QEMU, gdb and the C# breakpoints all live in
// the session, so a single Stop ends everything.
class DebugAction : CosmosProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CosmosCommands.launch(project, DefaultDebugExecutor.getDebugExecutorInstance())
    }
}
