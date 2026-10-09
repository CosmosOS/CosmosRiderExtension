package com.cosmosos.rider.actions

import com.cosmosos.rider.util.CosmosNotifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import java.io.File

class CleanAction : CosmosProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val info = CosmosCommands.projectInfoOrError(project, "Clean Error") ?: return

        if (Messages.showYesNoDialog(project, "Delete all build outputs?", "Clean Build", Messages.getWarningIcon()) != Messages.YES) {
            return
        }

        object : Task.Backgroundable(project, "Cleaning build outputs", false) {
            private var cleaned = 0

            override fun run(indicator: ProgressIndicator) {
                for (dir in listOf("output-x64", "output-arm64", "bin", "obj")) {
                    val file = File(info.projectDir, dir)
                    if (file.exists()) {
                        indicator.text = "Deleting $dir"
                        file.deleteRecursively()
                        cleaned++
                    }
                }
                VfsUtil.markDirtyAndRefresh(true, true, true, File(info.projectDir))
            }

            override fun onSuccess() {
                CosmosNotifications.info(project, "Cleaned $cleaned directories")
            }
        }.queue()
    }
}
