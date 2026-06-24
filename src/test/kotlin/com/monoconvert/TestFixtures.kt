package com.monoconvert

import java.nio.file.Path
import java.nio.file.Paths

/** Resolves the repo-root `fixtures/` dir regardless of the test working dir. */
object TestFixtures {
    val root: Path = locateFixtures()

    fun path(relative: String): Path = root.resolve(relative)

    private fun locateFixtures(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("fixtures")
            if (candidate.toFile().isDirectory) return candidate
            dir = dir.parent
        }
        error("Could not locate fixtures/ directory from working dir")
    }
}
