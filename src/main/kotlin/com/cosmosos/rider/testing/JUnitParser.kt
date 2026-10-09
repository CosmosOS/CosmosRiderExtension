package com.cosmosos.rider.testing

import java.io.File

data class JUnitCase(
    val name: String,
    val classname: String,
    val timeSeconds: Double,
    val status: Status,
    val message: String?
) {
    enum class Status { PASSED, FAILED, SKIPPED }
}

data class JUnitSuite(
    val name: String,
    val tests: Int,
    val failures: Int,
    val skipped: Int,
    val timeSeconds: Double,
    val cases: List<JUnitCase>,
    val architecture: String?,
    val timedOut: Boolean,
    val systemErr: String?,
    val systemOut: String?
)

/**
 * Parses the JUnit-style XML written by Cosmos.TestRunner.Engine
 * (OutputHandlerXml.cs). There is one <testsuite> per file in practice.
 */
object JUnitParser {

    private fun decodeXmlEntities(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { code -> String(Character.toChars(code)) } ?: it.value }
        .replace("&amp;", "&")

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)?.let(::decodeXmlEntities)

    private fun readCData(blockContent: String): String =
        Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>").find(blockContent)?.groupValues?.get(1)
            ?: decodeXmlEntities(blockContent.trim())

    fun parse(xmlFile: File): JUnitSuite? {
        val xml = try {
            xmlFile.readText()
        } catch (_: Exception) {
            return null
        }
        return parse(xml)
    }

    fun parse(xml: String): JUnitSuite? {
        val suiteMatch = Regex("<testsuite\\b([^>]*)>([\\s\\S]*?)</testsuite>").find(xml) ?: return null
        val suiteAttrs = suiteMatch.groupValues[1]
        val suiteBody = suiteMatch.groupValues[2]

        var architecture: String? = null
        var timedOut = false
        Regex("<properties>([\\s\\S]*?)</properties>").find(suiteBody)?.let { props ->
            for (pm in Regex("<property\\s+name=\"([^\"]+)\"\\s+value=\"([^\"]*)\"").findAll(props.groupValues[1])) {
                when (pm.groupValues[1]) {
                    "architecture" -> architecture = pm.groupValues[2]
                    "timedOut" -> if (pm.groupValues[2] == "true") timedOut = true
                }
            }
        }

        // Self-closing or with a body.
        val cases = Regex("<testcase\\b([^>]*?)(?:/>|>([\\s\\S]*?)</testcase>)").findAll(suiteBody).map { cm ->
            val caseAttrs = cm.groupValues[1]
            val caseBody = cm.groupValues[2]
            val failure = Regex("<failure\\b([^>]*?)(?:/>|>([\\s\\S]*?)</failure>)").find(caseBody)
            val skipped = Regex("<skipped\\b([^>]*?)(?:/>|>([\\s\\S]*?)</skipped>)").find(caseBody)
            val (status, message) = when {
                failure != null -> {
                    val inner = failure.groupValues[2].takeIf { it.isNotEmpty() }?.let(::readCData).orEmpty()
                    JUnitCase.Status.FAILED to (inner.ifEmpty { null } ?: attr(failure.groupValues[1], "message") ?: "Test failed")
                }
                skipped != null -> {
                    val inner = skipped.groupValues[2].takeIf { it.isNotEmpty() }?.let(::readCData).orEmpty()
                    JUnitCase.Status.SKIPPED to (inner.ifEmpty { null } ?: attr(skipped.groupValues[1], "message"))
                }
                else -> JUnitCase.Status.PASSED to null
            }
            JUnitCase(
                name = attr(caseAttrs, "name").orEmpty(),
                classname = attr(caseAttrs, "classname").orEmpty(),
                timeSeconds = attr(caseAttrs, "time")?.toDoubleOrNull() ?: 0.0,
                status = status,
                message = message
            )
        }.toList()

        return JUnitSuite(
            name = attr(suiteAttrs, "name").orEmpty(),
            tests = attr(suiteAttrs, "tests")?.toIntOrNull() ?: 0,
            failures = attr(suiteAttrs, "failures")?.toIntOrNull() ?: 0,
            skipped = attr(suiteAttrs, "skipped")?.toIntOrNull() ?: 0,
            timeSeconds = attr(suiteAttrs, "time")?.toDoubleOrNull() ?: 0.0,
            cases = cases,
            architecture = architecture,
            timedOut = timedOut,
            systemErr = Regex("<system-err>([\\s\\S]*?)</system-err>").find(suiteBody)?.groupValues?.get(1)?.let(::readCData),
            systemOut = Regex("<system-out>([\\s\\S]*?)</system-out>").find(suiteBody)?.groupValues?.get(1)?.let(::readCData)
        )
    }
}
