package com.cosmosos.rider

import com.cosmosos.rider.debugger.ElfSymbols
import com.cosmosos.rider.debugger.QmpClient
import com.cosmosos.rider.debugger.VarObj
import com.cosmosos.rider.debugger.mi.MiAsyncRecord
import com.cosmosos.rider.debugger.mi.MiParser
import com.cosmosos.rider.debugger.mi.MiSession
import com.cosmosos.rider.debugger.mi.MiStreamRecord
import com.cosmosos.rider.kernelviews.KernelSnapshots
import com.cosmosos.rider.kernelviews.LiveReader
import com.cosmosos.rider.util.KernelArtifacts
import com.cosmosos.rider.util.PortUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Boots the Timer test kernel with `cosmos run --debug` and walks the same gdb/MI
 * and QMP sequence CosmosDebugProcess uses: run to the kernel entry on a
 * hardware breakpoint, set a C# line breakpoint, interrupt while running, hit
 * the breakpoint, inspect, step, and read the live snapshots.
 *
 * Opt-in: COSMOS_SMOKE_ROOT=<nativeaot-patcher checkout with the Timer kernel built> ./gradlew test
 */
class DebuggerSmokeTest {

    @Test
    fun attachBreakInspectAndReadSnapshots() {
        val root = System.getenv("COSMOS_SMOKE_ROOT")
        assumeTrue("COSMOS_SMOKE_ROOT not set", !root.isNullOrBlank())

        val name = "Cosmos.Kernel.Tests.Timer"
        val projectDir = File(root, "tests/Kernels/$name").absolutePath
        val source = File(projectDir, "Kernel.cs")
        val line = source.readLines().indexOfFirst { it.contains("Stopwatch ts1: ") } + 1
        assertTrue("breakpoint line not found in $source", line > 0)
        val iso = KernelArtifacts.findIso(projectDir, "x64")
        val elf = KernelArtifacts.resolveKernelElf(root, projectDir, name, "x64")
        assertNotNull("build $name for x64 first", iso)
        assertNotNull(elf)

        val symbols = ElfSymbols.resolve(elf!!, listOf(LiveReader.THREADS_SYMBOL, LiveReader.GC_SYMBOL, LiveReader.MEMORY_SYMBOL))
        assertEquals(3, symbols.size)
        val entry = ElfSymbols.entryPoint(elf)
        assertNotNull(entry)

        assertFalse("port 1234 is busy", PortUtil.isPortInUse(1234))
        val cosmos = File(System.getProperty("user.home"), ".dotnet/tools/cosmos").absolutePath
        val cosmosProc = ProcessBuilder(
            cosmos, "run", "-a", "x64", "--iso", iso!!.absolutePath, "--debug", "--headless",
            "--", "-qmp", "tcp:127.0.0.1:4444,server,nowait"
        ).directory(File(projectDir))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()

        val events = LinkedBlockingQueue<MiAsyncRecord>()
        var mi: MiSession? = null
        var qmp: QmpClient? = null
        try {
            assertTrue("gdbstub never came up", PortUtil.waitForPort(1234, 10000))
            assertTrue("QMP never came up", PortUtil.waitForPort(4444, 10000))
            qmp = QmpClient("127.0.0.1", 4444).also { it.connect() }

            val gdb = ProcessBuilder("gdb", "-q", "--interpreter=mi2", elf.absolutePath).directory(File(projectDir)).start()
            mi = MiSession(gdb, object : MiSession.Listener {
                override fun onAsync(record: MiAsyncRecord) {
                    events.add(record)
                }

                override fun onStream(record: MiStreamRecord) = print("gdb ${record.kind} ${record.text}")

                override fun onExit(exitCode: Int) {}
            })
            mi.start()

            fun send(command: String) = mi.send(command).get(30, TimeUnit.SECONDS)

            mi.sendQuietly("-gdb-set mi-async on").get(10, TimeUnit.SECONDS)
            send("-gdb-set osabi none")
            send("-enable-pretty-printing")
            send("-interpreter-exec console ${MiParser.quote("source " + File("src/main/resources/gdb/cosmos_prettyprint.py").absolutePath)}")
            send("-target-select remote localhost:1234")

            // Under KVM a software breakpoint can't be written before paging:
            // boot to the entry on a hardware breakpoint first.
            send("-break-insert -t -h *0x${java.lang.Long.toHexString(entry!!)}")
            events.clear()
            send("-exec-continue")
            assertEquals("kmain", waitForStop(events, 120).results.tuple("frame")?.string("func"))

            val bkpt = send("-break-insert -f ${MiParser.quote("${source.absolutePath}:$line")}").results.tuple("bkpt")
            val number = bkpt?.int("number")
            assertNotNull(number)
            assertEquals(line, bkpt!!.int("line"))

            // Interrupting a running guest stops it with SIGINT.
            send("-exec-continue")
            Thread.sleep(300)
            send("-exec-interrupt")
            assertEquals("signal-received", waitForStop(events, 15).results.string("reason"))
            send("-exec-continue")

            val hit = waitForStop(events, 120).results
            assertEquals("breakpoint-hit", hit.string("reason"))
            assertEquals(number, hit.int("bkptno"))
            val thread = hit.int("thread-id") ?: 1
            assertEquals(source.absolutePath, hit.tuple("frame")?.string("fullname"))

            val frames = send("-stack-list-frames --thread $thread 0 255").results.list("stack")!!.tuples()
            assertEquals(line, frames.first().int("line"))
            val variables = send("-stack-list-variables --thread $thread --frame 0 --simple-values").results.list("variables")!!.tuples()
            assertTrue(variables.any { it.string("name") == "ts1" })
            val ts1 = VarObj.from(send("-var-create --thread $thread --frame 0 - * ${MiParser.quote("ts1")}").results)
            assertEquals("long", ts1.type)

            val threadsPtr = KernelSnapshots.readPointer(qmp.readVirtual(symbols.getValue(LiveReader.THREADS_SYMBOL), 8))!!
            val threads = KernelSnapshots.parseThreads(qmp.readVirtual(threadsPtr, KernelSnapshots.THREADS_SIZE))
            assertTrue("threads snapshot: $threads", !threads.isNullOrEmpty())
            val gcPtr = KernelSnapshots.readPointer(qmp.readVirtual(symbols.getValue(LiveReader.GC_SYMBOL), 8))!!
            assertTrue(KernelSnapshots.parseGc(qmp.readVirtual(gcPtr, KernelSnapshots.GC_SIZE))!!.initialized)
            val memPtr = KernelSnapshots.readPointer(qmp.readVirtual(symbols.getValue(LiveReader.MEMORY_SYMBOL), 8))!!
            val memory = KernelSnapshots.parseMemory(qmp.readVirtual(memPtr, KernelSnapshots.MEMORY_SIZE))!!
            val rat = qmp.readVirtual(memory.ratAddress, memory.totalPageCount.toInt())
            assertEquals(memory.totalPageCount, KernelSnapshots.walkExtents(rat).sumOf { it.length.toLong() })

            send("-exec-next --thread $thread")
            val step = waitForStop(events, 30).results
            assertEquals("end-stepping-range", step.string("reason"))
            assertTrue((step.tuple("frame")?.int("line") ?: 0) > line)
        } finally {
            qmp?.close()
            mi?.close()
            cosmosProc.descendants().forEach { it.destroy() }
            cosmosProc.destroy()
            cosmosProc.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun waitForStop(events: LinkedBlockingQueue<MiAsyncRecord>, seconds: Long): MiAsyncRecord {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            val e = events.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (e.kind == '*' && e.asyncClass == "stopped") return e
        }
        throw AssertionError("no *stopped within ${seconds}s")
    }
}
