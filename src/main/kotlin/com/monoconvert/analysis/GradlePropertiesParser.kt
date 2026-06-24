package com.monoconvert.analysis

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/** Reads a repo's `gradle.properties` into a flat key→value map. Read-only. */
object GradlePropertiesParser {

    fun parse(file: Path): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") && it.contains("=") }
            .associate {
                val idx = it.indexOf('=')
                it.substring(0, idx).trim() to it.substring(idx + 1).trim()
            }
    }
}
