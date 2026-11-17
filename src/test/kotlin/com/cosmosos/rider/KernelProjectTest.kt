package com.cosmosos.rider

import com.cosmosos.rider.util.KernelProject
import com.cosmosos.rider.util.ProjectConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KernelProjectTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val csproj = "<Project Sdk=\"Cosmos.Sdk/3.0.92\" />\n"

    private fun write(relative: String, content: String): File {
        val file = File(tmp.root, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun findsAVisualBasicKernel() {
        write("App/App.vbproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />\n")
        val kernel = write("VbKernel/VbKernel.vbproj", VBPROJ)
        write("VbKernel/bin/Hidden/Hidden.vbproj", VBPROJ)

        assertEquals(kernel, KernelProject.find(tmp.root))
        assertEquals("VbKernel", kernel.nameWithoutExtension)
        assertTrue(KernelProject.isCosmosProject(kernel))
        assertFalse(KernelProject.isCosmosProject(File(tmp.root, "App/App.vbproj")))
    }

    @Test
    fun aDirectoryThatHoldsBothYieldsItsCSharpProject() {
        // Name order would put Alpha.vbproj first; cosmos build takes the C# one.
        val vb = write("Alpha.vbproj", VBPROJ)
        val nested = write("Beta/Beta.csproj", csproj)
        val cs = write("Gamma.csproj", csproj)

        assertEquals(listOf(cs, nested, vb), KernelProject.findProjectFiles(tmp.root))
        assertEquals(cs, KernelProject.find(tmp.root))
    }

    @Test
    fun namedFindsEitherProjectFile() {
        val vb = write("K/K.vbproj", VBPROJ)
        val dir = vb.parentFile
        assertEquals(vb, KernelProject.named(dir, "K"))
        assertNull(KernelProject.named(dir, "Other"))

        val cs = write("K/K.csproj", csproj)
        assertEquals(cs, KernelProject.named(dir, "K"))
    }

    @Test
    fun parsesAndSavesAVisualBasicProject() {
        val vbproj = write("VbKernel/VbKernel.vbproj", VBPROJ)

        val props = ProjectConfig.parseProjectProperties(vbproj.path)
        assertEquals("VbKernel", props.name)
        assertEquals("VbKernel.Kernel", props.kernelClass)
        assertTrue(props.enableNetwork)
        assertEquals(listOf("Cosmos.Kernel", "Cosmos.Kernel.System"), props.packages.map { it.name })

        ProjectConfig.saveProjectProperties(vbproj.path, props.copy(enableNetwork = false))
        assertTrue(vbproj.readText().contains("<CosmosEnableNetwork>false</CosmosEnableNetwork>"))
        assertFalse(ProjectConfig.parseProjectProperties(vbproj.path).enableNetwork)
    }

    companion object {
        // What dotnet new cosmos-kernel-vb -n VbKernel writes.
        private val VBPROJ = """
            <Project Sdk="Cosmos.Sdk/3.0.92">

              <PropertyGroup>
                <OutputType>Exe</OutputType>
                <TargetFramework>net10.0</TargetFramework>
              </PropertyGroup>

              <!-- Core kernel packages -->
              <ItemGroup>
                <PackageReference Include="Cosmos.Kernel" Version="3.0.92" />
                <PackageReference Include="Cosmos.Kernel.System" Version="3.0.92" />
              </ItemGroup>

            </Project>
        """.trimIndent() + "\n"
    }
}
