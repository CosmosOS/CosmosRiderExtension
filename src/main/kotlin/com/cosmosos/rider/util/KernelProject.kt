package com.cosmosos.rider.util

import java.io.File

/** Finds Cosmos kernel projects on disk. */
object KernelProject {
    private const val MAX_SEARCH_DEPTH = 3

    // The project files a kernel can have, in the order `cosmos build`
    // searches a directory: C#, then Visual Basic.
    val projectExtensions = KernelLanguage.entries.map { it.projectExtension }

    fun isProjectFile(file: File): Boolean = file.isFile && projectExtensions.any { file.name.endsWith(it) }

    /** The project file called [name] in [dir], the C# one before the Visual Basic one, or null. */
    fun named(dir: File, name: String): File? =
        projectExtensions.map { File(dir, name + it) }.firstOrNull { it.isFile }

    /** The project files under [dir], in the order the tree is walked: a directory's entries by name. */
    fun findProjectFiles(dir: File, depth: Int = 0): List<File> {
        if (depth > MAX_SEARCH_DEPTH) return emptyList()
        val results = mutableListOf<File>()
        val entries = try {
            dir.listFiles() ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        val sorted = entries.sortedBy { it.name }
        // Where a directory holds both, its C# projects come ahead of its
        // Visual Basic ones: `cosmos build` takes the C# one.
        val projects = sorted.filter(::isProjectFile)
            .sortedBy { file -> projectExtensions.indexOfFirst { file.name.endsWith(it) } }
            .iterator()
        for (entry in sorted) {
            if (isProjectFile(entry)) {
                results += projects.next()
            } else if (entry.isDirectory &&
                !entry.name.startsWith(".") &&
                entry.name != "node_modules" &&
                entry.name != "bin" &&
                entry.name != "obj"
            ) {
                results += findProjectFiles(entry, depth + 1)
            }
        }
        return results
    }

    /** True when the project file references the Cosmos SDK or kernel packages. */
    fun isCosmosProject(projectFile: File): Boolean {
        return try {
            val content = projectFile.readText()
            content.contains("Cosmos.Sdk") || content.contains("Cosmos.Kernel")
        } catch (_: Exception) {
            false
        }
    }

    /** The first Cosmos kernel project under [root], or null. */
    fun find(root: File): File? = findProjectFiles(root).firstOrNull(::isCosmosProject)
}
