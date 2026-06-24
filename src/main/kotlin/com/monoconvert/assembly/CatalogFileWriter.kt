package com.monoconvert.assembly

import com.monoconvert.analysis.CatalogModel
import com.monoconvert.analysis.CatalogRenderer
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Phase 3: writes the rendered version catalog to `<monorepoDir>/gradle/libs.versions.toml`. */
object CatalogFileWriter {

    fun write(catalog: CatalogModel, monorepoDir: Path): Path {
        val dest = monorepoDir.resolve("gradle").resolve("libs.versions.toml")
        dest.parent.createDirectories()
        dest.writeText(CatalogRenderer.render(catalog))
        return dest
    }
}
