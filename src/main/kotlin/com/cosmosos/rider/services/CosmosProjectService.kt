package com.cosmosos.rider.services

import com.cosmosos.rider.util.PlatformUtil
import com.cosmosos.rider.util.ProjectConfig
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File

data class ProjectInfo(
    val name: String,
    val arch: String,
    val csprojPath: String,
    val projectDir: String
)

@Service(Service.Level.PROJECT)
class CosmosProjectService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): CosmosProjectService =
            project.getService(CosmosProjectService::class.java)
    }

    fun isCosmosProject(): Boolean {
        val basePath = project.basePath ?: return false
        return findCsprojFiles(File(basePath)).any { isCosmosProject(it) }
    }

    fun getProjectInfo(): ProjectInfo? {
        val basePath = project.basePath ?: return null

        for (csproj in findCsprojFiles(File(basePath))) {
            if (isCosmosProject(csproj)) {
                val projectDir = csproj.parent
                val config = ProjectConfig.loadCosmosConfig(projectDir)
                return ProjectInfo(
                    name = csproj.nameWithoutExtension,
                    arch = config.targetArch ?: "x64",
                    csprojPath = csproj.absolutePath,
                    projectDir = projectDir
                )
            }
        }
        return null
    }

    fun getCosmosToolsVersion(): String? {
        return try {
            val output = PlatformUtil.execCommand("dotnet tool list -g") ?: return null
            output.lines()
                .firstOrNull { it.lowercase().startsWith("cosmos.tools") }
                ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)
        } catch (_: Exception) {
            null
        }
    }

    data class ToolStatus(
        val displayName: String,
        val installed: Boolean,
        val version: String
    )

    fun checkTools(): List<ToolStatus> {
        val tools = mutableListOf<ToolStatus>()

        if (!PlatformUtil.isCosmosToolsInstalled) {
            tools.add(checkCommand("dotnet", "dotnet --version", ".NET SDK"))
            tools.add(ToolStatus("Cosmos Tools", false, "Not installed - run: dotnet tool install -g Cosmos.Tools"))
            return tools
        }

        // Add cosmos itself
        val cosmosVersion = getCosmosToolsVersion()
        tools.add(ToolStatus("Cosmos Tools", true, cosmosVersion ?: "Installed"))

        // Use cosmos check --json
        try {
            val result = PlatformUtil.execCommand("cosmos check --json", timeoutMs = 10000)
            if (result != null) {
                val json = com.google.gson.JsonParser.parseString(result).asJsonObject
                val toolsArray = json.getAsJsonArray("tools")
                if (toolsArray != null) {
                    for (tool in toolsArray) {
                        val obj = tool.asJsonObject
                        tools.add(ToolStatus(
                            displayName = obj.get("displayName")?.asString ?: "Unknown",
                            installed = obj.get("found")?.asBoolean ?: false,
                            version = if (obj.get("found")?.asBoolean == true)
                                obj.get("version")?.asString ?: "Installed"
                            else "Not installed"
                        ))
                    }
                }
                return tools
            }
        } catch (_: Exception) {
            // Fall through to basic checks
        }

        // Fallback
        tools.add(ToolStatus("Cosmos Tools", true, "Installed (check failed)"))
        tools.add(checkCommand("dotnet", "dotnet --version", ".NET SDK"))
        tools.add(checkCommand("qemu-system-x86_64", "qemu-system-x86_64 --version", "QEMU x64"))
        tools.add(checkCommand("qemu-system-aarch64", "qemu-system-aarch64 --version", "QEMU ARM64"))
        tools.add(checkCommand("gdb", "gdb --version", "GDB Debugger"))

        return tools
    }

    private fun checkCommand(name: String, command: String, displayName: String): ToolStatus {
        return try {
            val output = PlatformUtil.execCommand(command, timeoutMs = 5000)
            if (output != null) {
                ToolStatus(displayName, true, output.lines().firstOrNull()?.trim() ?: "Installed")
            } else {
                ToolStatus(displayName, false, "Not installed")
            }
        } catch (_: Exception) {
            ToolStatus(displayName, false, "Not installed")
        }
    }

    private fun findCsprojFiles(dir: File, depth: Int = 0): List<File> {
        if (depth > 3) return emptyList()
        val results = mutableListOf<File>()
        try {
            val entries = dir.listFiles() ?: return emptyList()
            for (entry in entries) {
                if (entry.isFile && entry.name.endsWith(".csproj")) {
                    results.add(entry)
                } else if (entry.isDirectory
                    && !entry.name.startsWith(".")
                    && entry.name != "node_modules"
                    && entry.name != "bin"
                    && entry.name != "obj"
                ) {
                    results.addAll(findCsprojFiles(entry, depth + 1))
                }
            }
        } catch (_: Exception) {
            // Ignore permission errors
        }
        return results
    }

    private fun isCosmosProject(csproj: File): Boolean {
        return try {
            val content = csproj.readText()
            content.contains("Cosmos.Sdk") || content.contains("Cosmos.Kernel")
        } catch (_: Exception) {
            false
        }
    }
}
