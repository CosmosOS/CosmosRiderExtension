package com.cosmosos.rider.testing

import com.cosmosos.rider.util.LogProcessor
import com.cosmosos.rider.util.PlatformUtil
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.io.OutputStream

class CosmosTestRunState(
    private val environment: ExecutionEnvironment,
    private val configuration: CosmosTestRunConfiguration
) : RunProfileState {

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val suites = configuration.selectedSuites()
        if (suites.isEmpty()) throw ExecutionException("No Cosmos test kernels found under tests/Kernels")

        val handler = CosmosTestProcessHandler(
            root = environment.project.basePath,
            suites = suites,
            arch = configuration.arch,
            mode = configuration.mode,
            timeoutOverride = configuration.timeoutSeconds.takeIf { it > 0 }
        )
        val properties = SMTRunnerConsoleProperties(configuration, FRAMEWORK, executor)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(FRAMEWORK, handler, properties)
        return DefaultExecutionResult(console, handler)
    }

    companion object {
        const val FRAMEWORK = "CosmosKernelTests"
    }
}

/**
 * Runs each suite through Cosmos.TestRunner.Engine, one after another, and
 * reports the engine's JUnit XML to the test tree as TeamCity service
 * messages. Engine output streams in as the suite's output while it runs.
 */
class CosmosTestProcessHandler(
    private val root: String?,
    private val suites: List<TestKernel>,
    private val arch: String,
    private val mode: String,
    private val timeoutOverride: Int?
) : ProcessHandler() {

    @Volatile
    private var cancelled = false

    @Volatile
    private var current: Process? = null

    private val dotnet: String
        get() = PlatformUtil.findCommand("dotnet") ?: "dotnet"

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread {
            var exitCode = 0
            try {
                for (kernel in suites) {
                    if (cancelled) break
                    if (!runSuite(kernel)) exitCode = 1
                }
            } catch (e: Exception) {
                text("error: ${e.message}\n")
                exitCode = 1
            } finally {
                notifyProcessTerminated(if (cancelled) 1 else exitCode)
            }
        }
    }

    private fun runSuite(kernel: TestKernel): Boolean {
        val suite = kernel.suiteName
        message("testSuiteStarted", "name" to suite, "locationHint" to "file://${kernel.csprojPath}")
        text("\n=== $suite ($arch) ===\n")
        try {
            val engine = ensureTestRunnerDll()
            if (engine == null) {
                failSuite(suite, "Test runner engine not available")
                return false
            }
            if (cancelled) return false

            val timeout = timeoutOverride ?: TestTimeouts.defaultTimeoutSeconds(suite, arch)
            val xml = File(
                FileUtil.getTempDirectory(),
                "cosmos-test-$suite-$arch-${ProcessHandle.current().pid()}-${System.currentTimeMillis()}.xml"
            )
            val args = listOf(engine, kernel.projectDir, arch, timeout.toString(), xml.absolutePath, mode)
            text("> dotnet ${args.joinToString(" ")}\n(timeout ${timeout}s, mode $mode)\n\n")

            val exitCode = runChild(listOf(dotnet) + args, root ?: kernel.projectDir)
            if (cancelled) {
                text("\nrun cancelled\n")
                xml.delete()
                return false
            }

            val parsed = JUnitParser.parse(xml)
            xml.delete()
            if (parsed == null) {
                failSuite(suite, "engine exited with code $exitCode and produced no XML")
                return false
            }
            if (parsed.timedOut) text("\nsuite timed out\n")

            for (case in parsed.cases) {
                message("testStarted", "name" to case.name)
                when (case.status) {
                    JUnitCase.Status.FAILED -> message("testFailed", "name" to case.name, "message" to (case.message ?: "failed"))
                    JUnitCase.Status.SKIPPED -> message("testIgnored", "name" to case.name, "message" to case.message.orEmpty())
                    JUnitCase.Status.PASSED -> {}
                }
                message("testFinished", "name" to case.name, "duration" to (case.timeSeconds * 1000).toLong().toString())
            }

            val failed = parsed.cases.count { it.status == JUnitCase.Status.FAILED }
            when {
                // The engine explains a run without results (a failed kernel
                // build, a boot crash) in <system-err>.
                parsed.cases.isEmpty() -> failSuite(
                    suite,
                    if (parsed.timedOut) "suite timed out" else parsed.systemErr?.trim()?.ifEmpty { null } ?: "Test run produced no results"
                )
                parsed.timedOut -> failSuite(suite, "suite timed out")
                exitCode != 0 && failed == 0 -> failSuite(suite, "engine exited with code $exitCode")
            }
            return exitCode == 0 && failed == 0 && !parsed.timedOut && parsed.cases.isNotEmpty()
        } finally {
            message("testSuiteFinished", "name" to suite)
        }
    }

    // A suite-level failure (no results, timeout) shows as a failed entry
    // inside the suite, since the tree only marks tests as failed.
    private fun failSuite(suite: String, reason: String) {
        val name = "$suite (suite)"
        message("testStarted", "name" to name)
        message("testFailed", "name" to name, "message" to reason)
        message("testFinished", "name" to name)
    }

    private fun ensureTestRunnerDll(): String? {
        TestDiscovery.locateTestRunnerDll(root)?.let { return it }
        val csproj = TestDiscovery.locateTestRunnerProject(root)
        if (csproj == null) {
            text("error: could not find Cosmos.TestRunner.Engine.csproj\n")
            return null
        }
        text("Building Cosmos.TestRunner.Engine (one-time)...\n")
        val code = runChild(listOf(dotnet, "build", csproj, "-c", "Debug"), File(csproj).parent)
        if (code != 0) {
            text("dotnet build failed with exit code $code\n")
            return null
        }
        return TestDiscovery.locateTestRunnerDll(root)
    }

    private fun runChild(command: List<String>, cwd: String): Int {
        val process = try {
            ProcessBuilder(command)
                .directory(File(cwd))
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File(if (SystemInfo.isWindows) "NUL" else "/dev/null")))
                .apply { environment().putAll(PlatformUtil.getEnvWithDotnetTools()) }
                .start()
        } catch (e: Exception) {
            text("error: ${e.message}\n")
            return -1
        }
        current = process
        try {
            // Line by line, so engine output never splits a service message.
            process.inputStream.bufferedReader().forEachLine { text(LogProcessor.stripAnsi(it) + "\n") }
            return process.waitFor()
        } catch (_: Exception) {
            return -1
        } finally {
            current = null
        }
    }

    private fun text(s: String) = notifyTextAvailable(s, ProcessOutputTypes.STDOUT)

    private fun message(name: String, vararg attributes: Pair<String, String>) {
        val attrs = attributes.joinToString(" ") { (k, v) -> "$k='${escape(v)}'" }
        notifyTextAvailable("##teamcity[$name $attrs]\n", ProcessOutputTypes.STDOUT)
    }

    private fun escape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '|' -> append("||")
                '\'' -> append("|'")
                '\n' -> append("|n")
                '\r' -> append("|r")
                '[' -> append("|[")
                ']' -> append("|]")
                else -> append(c)
            }
        }
    }

    override fun destroyProcessImpl() {
        cancelled = true
        killCurrent()
    }

    override fun detachProcessImpl() {
        cancelled = true
        killCurrent()
        notifyProcessDetached()
    }

    // The engine spawns QEMU; take the whole tree down.
    private fun killCurrent() {
        val process = current ?: return
        process.descendants().forEach { it.destroy() }
        process.destroy()
    }

    override fun detachIsDefault() = false

    override fun getProcessInput(): OutputStream? = null
}
