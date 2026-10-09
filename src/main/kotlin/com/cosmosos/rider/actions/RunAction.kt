package com.cosmosos.rider.actions

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.AnActionEvent

// Boots the kernel through the "Cosmos Kernel" run configuration, so the Run
// tool window's Stop button takes cosmos and QEMU down.
class RunAction : CosmosProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CosmosCommands.launch(project, DefaultRunExecutor.getRunExecutorInstance())
    }
}
