package com.monoconvert.assembly

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Phase 3: sets the root-owned monorepo `version=` in `<monorepoDir>/gradle.properties`. */
object RootPropertiesWriter {

    fun write(monorepoDir: Path, version: String): Path {
        val dest = monorepoDir.resolve("gradle.properties")
        val existing = if (dest.exists()) dest.readText() else ""
        val lines = if (existing.isEmpty()) {
            mutableListOf()
        } else {
            existing.removeSuffix("\n").split("\n").toMutableList()
        }
        val idx = lines.indexOfFirst { it.trimStart().startsWith("version=") }
        if (idx >= 0) lines[idx] = "version=$version" else lines.add("version=$version")
        dest.writeText(lines.joinToString("\n") + "\n")
        return dest
    }
}
