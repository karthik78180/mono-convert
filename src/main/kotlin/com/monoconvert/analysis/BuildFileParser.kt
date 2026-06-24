package com.monoconvert.analysis

import com.monoconvert.discovery.BuildFile
import kotlin.io.path.readText

/** A dependency declaration as written in a build file (version not yet resolved). */
data class RawDependency(
    val configuration: String,
    val group: String,
    val artifact: String,
    val versionExpr: String?,
)

/** A plugin declaration from a `plugins {}` block. */
data class RawPlugin(
    val id: String,
    val versionExpr: String?,
)

/** Everything [BuildFileParser] extracts from a single build file. */
data class BuildFileContents(
    val dependencies: List<RawDependency>,
    val plugins: List<RawPlugin>,
    val buildscriptClasspath: List<RawDependency>,
    val unparsedLines: List<String>,
)

/**
 * Lightweight, block-aware parser for Groovy and Kotlin Gradle DSL. Read-only:
 * extracts coordinates/versions for analysis. In-file rewrites happen later via OpenRewrite.
 */
object BuildFileParser {

    private val CONFIGS = setOf(
        "implementation", "api", "testImplementation", "testRuntimeOnly", "runtimeOnly",
        "compileOnly", "annotationProcessor", "kapt", "classpath", "developmentOnly",
    )

    // configuration + quoted "group:artifact:version" (Groovy: `cfg 'x'`, Kotlin: `cfg("x")`).
    private val DEP = Regex("""(\w+)\s*\(?\s*["']([^"']+)["']\s*\)?""")
    // `id 'x' [version 'y']` / `id("x") [version "y"]`.
    private val PLUGIN = Regex(
        """id\s*\(?\s*["']([^"']+)["']\s*\)?(?:\s*version\s*\(?\s*["']([^"']+)["']\s*\)?)?""",
    )

    private data class Block(val body: String, val span: IntRange)

    fun parse(buildFile: BuildFile): BuildFileContents {
        var text = buildFile.path.readText()

        val classpath = mutableListOf<RawDependency>()
        val unparsed = mutableListOf<String>()

        // buildscript { ... dependencies { classpath ... } } — handled and removed first.
        extractBlock(text, "buildscript")?.let { bs ->
            extractBlock(bs.body, "dependencies")?.let { inner ->
                parseDeps(inner.body, classpath, unparsed)
            }
            text = text.removeRange(bs.span)
        }

        val plugins = extractBlock(text, "plugins")?.let { parsePlugins(it.body) } ?: emptyList()

        val deps = mutableListOf<RawDependency>()
        extractBlock(text, "dependencies")?.let { parseDeps(it.body, deps, unparsed) }

        return BuildFileContents(deps, plugins, classpath, unparsed)
    }

    /** Extracts the body and full span of the first top-level `name { ... }` block (brace-balanced). */
    private fun extractBlock(text: String, name: String): Block? {
        val header = Regex("""(?m)^[ \t]*$name[ \t]*\{""").find(text) ?: return null
        val open = text.indexOf('{', header.range.first)
        var depth = 1
        var j = open + 1
        val sb = StringBuilder()
        while (j < text.length && depth > 0) {
            when (text[j]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth > 0) sb.append(text[j])
            j++
        }
        return Block(sb.toString(), header.range.first until j)
    }

    private fun parseDeps(body: String, out: MutableList<RawDependency>, unparsed: MutableList<String>) {
        for (raw in body.lines()) {
            val line = raw.substringBefore("//").trim()
            if (line.isEmpty()) continue
            val m = DEP.find(line)
            if (m != null && m.groupValues[1] in CONFIGS) {
                val parts = m.groupValues[2].split(":")
                if (parts.size >= 2) {
                    out += RawDependency(m.groupValues[1], parts[0], parts[1], parts.getOrNull(2))
                    continue
                }
            }
            if (line.takeWhile { it.isLetterOrDigit() } in CONFIGS) unparsed += line
        }
    }

    private fun parsePlugins(body: String): List<RawPlugin> =
        body.lines().mapNotNull { raw ->
            val line = raw.substringBefore("//").trim()
            val m = PLUGIN.find(line) ?: return@mapNotNull null
            RawPlugin(m.groupValues[1], m.groupValues[2].ifEmpty { null })
        }
}
