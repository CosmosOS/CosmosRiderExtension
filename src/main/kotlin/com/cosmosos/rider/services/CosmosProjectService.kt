package com.cosmosos.rider.services

import com.cosmosos.rider.util.PlatformUtil
import com.cosmosos.rider.util.ProjectConfig
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.io.File

data class ProjectInfo(
    val name: String,
    val arch: String,
    val csprojPath: String,
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
    private var cachedCsproj: Pair<Long, String>? = null

    private fun findCosmosCsproj(): String? {
        cachedCsproj?.let { (at, path) ->
            if (System.currentTimeMillis() - at < SCAN_TTL_MS) return path.ifEmpty { null }
        }
        val basePath = project.basePath
        val found = basePath?.let { findCsprojFiles(File(it)).firstOrNull(::isCosmosCsproj)?.absolutePath }
        cachedCsproj = System.currentTimeMillis() to (found ?: "")
        return found
    }

    fun isCosmosProject(): Boolean = findCosmosCsproj() != null

    fun getProjectInfo(): ProjectInfo? {
        val csproj = File(findCosmosCsproj() ?: return null)
        if (!csproj.isFile) {
            cachedCsproj = null
            return null
        }
        val projectDir = csproj.parent
        return ProjectInfo(
            name = csproj.nameWithoutExtension,
            arch = ProjectConfig.loadTargetArch(projectDir),
            csprojPath = csproj.absolutePath,
            projectDir = projectDir
        )
    }

    // Tells the tool window (and anything else listening) that the project
    // or its properties changed.
    fun fireProjectChanged() {
        cachedCsproj = null
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

    private fun findCsprojFiles(dir: File, depth: Int = 0): List<File> {
        if (depth > 3) return emptyList()
        val results = mutableListOf<File>()
        val entries = try {
            dir.listFiles() ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        for (entry in entries.sortedBy { it.name }) {
            if (entry.isFile && entry.name.endsWith(".csproj")) {
                results += entry
            } else if (entry.isDirectory &&
                !entry.name.startsWith(".") &&
                entry.name != "node_modules" &&
                entry.name != "bin" &&
                entry.name != "obj"
            ) {
                results += findCsprojFiles(entry, depth + 1)
            }
        }
        return results
    }

    private fun isCosmosCsproj(csproj: File): Boolean {
        return try {
            val content = csproj.readText()
            content.contains("Cosmos.Sdk") || content.contains("Cosmos.Kernel")
        } catch (_: Exception) {
            false
        }
    }
}
