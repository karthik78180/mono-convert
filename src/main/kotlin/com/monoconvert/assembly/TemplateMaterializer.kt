package com.monoconvert.assembly

import com.monoconvert.MigrationException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory

/** Phase 3: copies a local template tree into `<outDir>/<monorepoName>` (idempotent). */
object TemplateMaterializer {

    fun materialize(templateDir: Path, outDir: Path, monorepoName: String): Path {
        if (!templateDir.isDirectory()) {
            throw MigrationException("template path '$templateDir' is not a readable directory")
        }
        val dest = outDir.resolve(monorepoName)
        outDir.createDirectories()
        templateDir.toFile().copyRecursively(dest.toFile(), overwrite = true)
        return dest
    }
}
