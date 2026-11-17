package com.cosmosos.rider

import com.cosmosos.rider.util.KernelLanguage
import com.cosmosos.rider.util.KernelProject
import com.cosmosos.rider.util.KernelTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelLanguageTest {

    @Test
    fun installedReadsTheShortNamesOfTheTemplateTable() {
        assertEquals(setOf("cosmos-kernel-csharp", "cosmos-kernel-vb"), KernelTemplates.installed(SPLIT_TEMPLATES))
        assertEquals(setOf("cosmos-kernel"), KernelTemplates.installed(SINGLE_TEMPLATE))
        // Windows line endings, as a console on Windows writes them.
        assertEquals(
            setOf("cosmos-kernel-csharp", "cosmos-kernel-vb"),
            KernelTemplates.installed(SPLIT_TEMPLATES.replace("\n", "\r\n"))
        )
    }

    @Test
    fun installedIsEmptyWhenNothingIsInstalled() {
        // The output names the filter twice, but has no table.
        assertTrue(KernelTemplates.installed(NO_TEMPLATE).isEmpty())
        assertTrue(KernelTemplates.installed("").isEmpty())
        // What a failed dotnet new list comes back as.
        assertTrue(KernelTemplates.installed(null).isEmpty())
    }

    @Test
    fun eachLanguageFindsItsTemplate() {
        val split = KernelTemplates.installed(SPLIT_TEMPLATES)
        assertEquals("cosmos-kernel-csharp", KernelLanguage.CSHARP.findTemplate(split))
        assertEquals("cosmos-kernel-vb", KernelLanguage.VISUAL_BASIC.findTemplate(split))
    }

    @Test
    fun onlyCSharpFallsBackToTheTemplateFromBeforeTheSplit() {
        val single = KernelTemplates.installed(SINGLE_TEMPLATE)
        assertEquals("cosmos-kernel", KernelLanguage.CSHARP.findTemplate(single))
        assertNull(KernelLanguage.VISUAL_BASIC.findTemplate(single))

        assertNull(KernelLanguage.CSHARP.findTemplate(emptySet()))
    }

    @Test
    fun projectExtensionsFollowTheLanguages() {
        assertEquals(listOf(".csproj", ".vbproj"), KernelProject.projectExtensions)
        assertEquals(listOf("C#", "Visual Basic"), KernelLanguage.entries.map { it.displayName })
    }

    // Real output of dotnet new list cosmos-kernel, trailing spaces trimmed.
    companion object {
        // With the templates of CosmosOS/Cosmos#3311 installed.
        private val SPLIT_TEMPLATES = """
            These templates matched your input: 'cosmos-kernel'

            Template Name                         Short Name            Language  Tags
            ------------------------------------  --------------------  --------  ---------------------------------------
            Cosmos Kernel Project (C#)            cosmos-kernel-csharp  [C#]      Cosmos/Kernel/OS Development/Bare Metal
            Cosmos Kernel Project (Visual Basic)  cosmos-kernel-vb      VB        Cosmos/Kernel/OS Development/Bare Metal
        """.trimIndent()

        // With Cosmos.Build.Templates 3.0.97, from before the split by language.
        private val SINGLE_TEMPLATE = """
            These templates matched your input: 'cosmos-kernel'

            Template Name          Short Name     Language  Tags
            ---------------------  -------------  --------  ---------------------------------------
            Cosmos Kernel Project  cosmos-kernel  [C#]      Cosmos/Kernel/OS Development/Bare Metal
        """.trimIndent()

        // With no Cosmos template installed (exit code 103).
        private val NO_TEMPLATE = """
            No templates found matching: 'cosmos-kernel'.

            To search for the templates on NuGet.org, run:
               dotnet new search cosmos-kernel


            For details on the exit code, refer to https://aka.ms/templating-exit-codes#103
        """.trimIndent()
    }
}
