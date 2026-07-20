package com.cosmosos.rider.toolwindow

import com.cosmosos.rider.services.CosmosProjectService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition

class CosmosToolWindowCondition : Condition<Project> {
    override fun value(project: Project): Boolean {
        return CosmosProjectService.getInstance(project).isCosmosProject()
    }
}
