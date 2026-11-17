package com.cosmosos.rider.services

import com.cosmosos.rider.util.KernelProject
import com.cosmosos.rider.util.PlatformUtil
import com.cosmosos.rider.util.ProjectConfig
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.io.File

data class ProjectInfo(
    val name: String,
    val arch: String,
    val projectFile: String,
    val projectDir: String
)

fun interface CosmosProjectListener {
    fun projectChanged()
}

@Service(Service.Level.PROJECT)
class CosmosProjectService(private val project: Project) {

    companion object {
        val TOPIC: Topic<CosmosProjectListener> =
            Topic.create("Cosmos project changed", CosmosProjectListener::class.java)

        // Fired when the installed toolchain may have changed.
        val TOOLS_TOPIC: Topic<CosmosProjectListener> =
            Topic.create("Cosmos tools changed", CosmosProjectListener::class.java)

        private const val SCAN_TTL_MS = 10_000L

        fun getInstance(project: Project): CosmosProjectService =
            project.getService(CosmosProjectService::class.java)
    }

    // Action updates ask on every repaint; the directory walk is cached so
    // that stays cheap. An empty string means "scanned, nothing found".
    @Volatile
    private var cachedProjectFile: Pair<Long, String>? = null

    private fun findKernelProject(): String? {
        cachedProjectFile?.let { (at, path) ->
            if (System.currentTimeMillis() - at < SCAN_TTL_MS) return path.ifEmpty { null }
        }
        val basePath = project.basePath
        val found = basePath?.let { KernelProject.find(File(it))?.absolutePath }
        cachedProjectFile = System.currentTimeMillis() to (found ?: "")
        return found
    }

    fun isCosmosProject(): Boolean = findKernelProject() != null

    fun getProjectInfo(): ProjectInfo? {
        val projectFile = File(findKernelProject() ?: return null)
        if (!projectFile.isFile) {
            cachedProjectFile = null
            return null
        }
        val projectDir = projectFile.parent
        return ProjectInfo(
            name = projectFile.nameWithoutExtension,
            arch = ProjectConfig.loadTargetArch(projectDir),
            projectFile = projectFile.absolutePath,
            projectDir = projectDir
        )
    }

    // Tells the tool window (and anything else listening) that the project
    // or its properties changed.
    fun fireProjectChanged() {
        cachedProjectFile = null
        project.messageBus.syncPublisher(TOPIC).projectChanged()
    }

    fun fireToolsChanged() {
        project.messageBus.syncPublisher(TOOLS_TOPIC).projectChanged()
    }

    fun getCosmosToolsVersion(): String? {
        val output = PlatformUtil.execCommand("dotnet tool list -g") ?: return null
        // Format: "cosmos.tools   3.0.37   cosmos"
        return output.lines()
            .firstOrNull { it.lowercase().startsWith("cosmos.tools") }
            ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)
    }

    data class ToolStatus(
        val displayName: String,
        val installed: Boolean,
        val version: String
    )

    fun checkTools(): List<ToolStatus> {
        if (!PlatformUtil.isCosmosToolsInstalled) {
            return listOf(ToolStatus("Cosmos Tools", false, "Not installed - run: dotnet tool install -g Cosmos.Tools"))
        }

        // Refresh the shared cache (the debugger reads gdb's path from it).
        val data = PlatformUtil.refreshToolsCheck()
            ?: return listOf(ToolStatus("Cosmos Tools", false, "Check failed - reinstall Cosmos.Tools"))

        val tools = mutableListOf(ToolStatus("Cosmos Tools", true, getCosmosToolsVersion() ?: "Installed"))
        data.getAsJsonArray("tools")?.forEach { element ->
            val tool = element.asJsonObject
            val found = tool.get("found")?.asBoolean ?: false
            tools += ToolStatus(
                displayName = tool.get("displayName")?.asString ?: "Unknown",
                installed = found,
                version = if (found) tool.get("version")?.takeIf { !it.isJsonNull }?.asString ?: "Installed" else "Not installed"
            )
        }
        return tools
    }
}
