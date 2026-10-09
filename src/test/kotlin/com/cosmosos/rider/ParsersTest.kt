package com.cosmosos.rider

import com.cosmosos.rider.debugger.QmpClient
import com.cosmosos.rider.debugger.mi.MiAsyncRecord
import com.cosmosos.rider.debugger.mi.MiParser
import com.cosmosos.rider.debugger.mi.MiResultRecord
import com.cosmosos.rider.debugger.mi.MiStreamRecord
import com.cosmosos.rider.kernelviews.KernelSnapshots
import com.cosmosos.rider.kernelviews.PageExtent
import com.cosmosos.rider.kernelviews.PageTypes
import com.cosmosos.rider.testing.JUnitCase
import com.cosmosos.rider.testing.JUnitParser
import com.cosmosos.rider.util.DiskConfig
import com.cosmosos.rider.util.ProjectConfig
import com.cosmosos.rider.util.QemuOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ParsersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parsesStoppedRecord() {
        val record = MiParser.parse(
            """*stopped,reason="breakpoint-hit",disp="keep",bkptno="2",frame={addr="0xffffffff8007310d",func="Kernel__Test",args=[],file="Kernel.cs",fullname="/k/Kernel.cs",line="184",arch="i386:x86-64"},thread-id="1",stopped-threads="all""""
        ) as MiAsyncRecord
        assertEquals('*', record.kind)
        assertEquals("stopped", record.asyncClass)
        assertEquals(2, record.results.int("bkptno"))
        assertEquals("/k/Kernel.cs", record.results.tuple("frame")?.string("fullname"))
        assertEquals(184, record.results.tuple("frame")?.int("line"))
    }

    @Test
    fun parsesTokenErrorAndLists() {
        val error = MiParser.parse("""12^error,msg="No symbol \"foo\" in current context."""") as MiResultRecord
        assertEquals(12, error.token)
        assertEquals("No symbol \"foo\" in current context.", error.errorMessage)

        val stack = MiParser.parse("""5^done,stack=[frame={level="0",func="a"},frame={level="1",func="b"}]""") as MiResultRecord
        assertEquals(listOf("a", "b"), stack.results.list("stack")!!.tuples().map { it.string("func") })

        val names = MiParser.parse("""6^done,register-names=["rax","rbx",""]""") as MiResultRecord
        assertEquals(listOf("rax", "rbx", ""), names.results.list("register-names")!!.strings())
    }

    @Test
    fun parsesMultiLocationBreakpointAndOctalEscapes() {
        val bp = MiParser.parse("""3^done,bkpt={number="1",addr="<MULTIPLE>"},{number="1.1",addr="0x1"},{number="1.2",addr="0x2"}""") as MiResultRecord
        assertEquals(1, bp.results.tuple("bkpt")?.int("number"))
        assertEquals(2, bp.results.unnamed().size)

        // gdb escapes non-ASCII bytes as octal: "é" is \303\251 in UTF-8.
        val stream = MiParser.parse("""~"caf\303\251 \"ok\"\n"""") as MiStreamRecord
        assertEquals("café \"ok\"\n", stream.text)
        assertEquals("\"a\\\\b \\\"c\\\"\"", MiParser.quote("a\\b \"c\""))
    }

    @Test
    fun qemuOptions() {
        assertEquals(512, QemuOptions.parseMemoryMb("512M"))
        assertEquals(2048, QemuOptions.parseMemoryMb("2G"))
        assertEquals(256L * 1024 * 1024, QemuOptions.parseSizeBytes("256"))
        assertEquals(512L * 1024, QemuOptions.parseSizeBytes("512K"))
        assertNull(QemuOptions.parseSizeBytes("big"))
        assertEquals(listOf("-name", "\"my vm\"", "-drive", "file=\"a b.img\""), QemuOptions.splitExtraArgs("""-name "my vm"  -drive file="a b.img""""))
        assertEquals(emptyList<String>(), QemuOptions.buildAudioArgs("none"))
        assertEquals(listOf("--audio", "intel-hda"), QemuOptions.buildAudioArgs("intel-hda"))
        assertEquals(listOf("--nic", "none"), QemuOptions.buildNicArgs("none"))
        assertEquals(listOf("--hostfwd", "tcp::2323-:23"), QemuOptions.buildHostForwardArgs(listOf(" tcp::2323-:23 ", "")))

        val dir = tmp.newFolder()
        val args = QemuOptions.prepareDiskArgs(dir.path, listOf(DiskConfig("disks/a.img", "nvme", "1M"), DiskConfig("", "ahci", "1M")))
        val image = File(dir, "disks/a.img")
        assertEquals(listOf("--disk", "${image.absolutePath},nvme"), args)
        assertEquals(1024L * 1024, image.length())
    }

    @Test
    fun projectConfigRoundTrip() {
        val dir = tmp.newFolder()
        File(dir, ".cosmos").mkdirs()
        File(dir, ".cosmos/config.json").writeText(
            """{"targetArch":"arm64","custom":42,"qemu":{"machineType":"q35","networkCard":"e1000e","keyboard":"ps2","portForwards":"tcp::1-:2, udp::3-:4","extraArgs":"-device x"}}"""
        )
        // Values the architecture doesn't offer fall back to its defaults.
        val qemu = ProjectConfig.loadQemuConfig(dir.path, "arm64")
        assertEquals("virt", qemu.machineType)
        assertEquals("none", qemu.networkCard)
        assertEquals("virtio-keyboard-device", qemu.keyboard)
        assertEquals(listOf("tcp::1-:2", "udp::3-:4"), qemu.portForwards)

        ProjectConfig.saveQemuConfig(dir.path, qemu.copy(extraArgs = "-drive file=a.img"))
        val saved = File(dir, ".cosmos/config.json").readText()
        assertTrue("unknown keys survive", saved.contains("\"custom\": 42"))
        assertTrue("no HTML escaping", saved.contains("file=a.img"))

        val csproj = File(dir, "K.csproj")
        csproj.writeText("<Project>\n  <PropertyGroup>\n    <TargetFramework>net10.0</TargetFramework>\n    <CosmosEnableTimer>false</CosmosEnableTimer>\n  </PropertyGroup>\n</Project>\n")
        val props = ProjectConfig.parseProjectProperties(csproj.path)
        assertEquals(false, props.enableTimer)
        ProjectConfig.saveProjectProperties(csproj.path, props.copy(enableTimer = true, enableAudio = false, gccFlags = "-O2 \$(Extra)"))
        val content = csproj.readText()
        assertTrue(!content.contains("CosmosEnableTimer"))
        assertTrue(content.contains("<CosmosEnableAudio>false</CosmosEnableAudio>"))
        assertTrue(content.contains("<GCCCompilerFlags>-O2 \$(Extra)</GCCCompilerFlags>"))
        assertEquals("-O2 \$(Extra)", ProjectConfig.parseProjectProperties(csproj.path).gccFlags)
    }

    @Test
    fun junitParser() {
        val suite = JUnitParser.parse(
            """<testsuites><testsuite name="T" tests="3" failures="1" skipped="1" time="1.5">
              <properties><property name="architecture" value="x64" /><property name="timedOut" value="true" /></properties>
              <testcase name="A" classname="T" time="0.5" />
              <testcase name="B" classname="T" time="0.25"><failure message="boom"><![CDATA[expected 1 &lt; 2]]></failure></testcase>
              <testcase name="C" classname="T" time="0"><skipped message="later" /></testcase>
              <system-err><![CDATA[Build failed with exit code 1:
]]></system-err></testsuite></testsuites>"""
        )!!
        assertEquals(listOf("A", "B", "C"), suite.cases.map { it.name })
        assertEquals(listOf(JUnitCase.Status.PASSED, JUnitCase.Status.FAILED, JUnitCase.Status.SKIPPED), suite.cases.map { it.status })
        assertEquals("expected 1 &lt; 2", suite.cases[1].message)
        assertEquals("later", suite.cases[2].message)
        assertEquals("x64", suite.architecture)
        assertTrue(suite.timedOut)
        assertEquals("Build failed with exit code 1:", suite.systemErr?.trim())
    }

    @Test
    fun snapshotsAndHexDump() {
        val rat = byteArrayOf(64, 3, 128.toByte(), 128.toByte(), 0, 0, 1, 128.toByte(), 0)
        assertEquals(
            listOf(PageExtent(0, 1, 64), PageExtent(1, 3, 3), PageExtent(4, 2, PageTypes.EMPTY), PageExtent(6, 2, 1), PageExtent(8, 1, 0)),
            KernelSnapshots.walkExtents(rat)
        )
        val dump = "ffff800000151004: 0x01 0x00 0x5d 0xc0 0x01 0x00 0x00 0x00\nffff80000015100c: 0x02 0x00"
        assertEquals(listOf(1, 0, 0x5d, 0xc0, 1, 0, 0, 0, 2, 0), QmpClient.parseMonitorHexDump(dump, 10).map { it.toInt() and 0xff })
        assertEquals("1.5 KiB", KernelSnapshots.formatBytes(1536))
        assertEquals("0xffffffff80034cb0", KernelSnapshots.formatHex(-0x7ffcb350L))
    }
}
