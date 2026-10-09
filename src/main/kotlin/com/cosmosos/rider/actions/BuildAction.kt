package com.cosmosos.rider.actions

import com.intellij.openapi.actionSystem.AnActionEvent

class BuildAction : CosmosProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val info = CosmosCommands.projectInfoOrError(project, "Build Error") ?: return
        CosmosCommands.build(project, info)
    }
}
