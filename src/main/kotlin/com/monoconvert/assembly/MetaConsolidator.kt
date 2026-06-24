package com.monoconvert.assembly

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** Phase 3: writes the single root `meta/source.yaml` (carId only) and drops per-module meta dirs. */
object MetaConsolidator {

    fun consolidate(monorepoDir: Path, carId: String, targets: List<String>): Path {
        val metaDir = monorepoDir.resolve("meta")
        metaDir.createDirectories()
        val dest = metaDir.resolve("source.yaml")
        dest.writeText("carId: $carId\n")
        for (t in targets) {
            val moduleMeta = monorepoDir.resolve(t).resolve("meta")
            if (moduleMeta.exists()) moduleMeta.toFile().deleteRecursively()
        }
        return dest
    }
}
