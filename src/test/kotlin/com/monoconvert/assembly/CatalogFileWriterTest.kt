package com.monoconvert.assembly

import com.monoconvert.analysis.CatalogLibrary
import com.monoconvert.analysis.CatalogModel
import com.monoconvert.analysis.CatalogPlugin
import com.monoconvert.analysis.CatalogRenderer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

class CatalogFileWriterTest {

    @Test
    fun `writes rendered catalog to gradle libs versions toml`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("vehicle-platform").also { it.createDirectories() }
        val model = CatalogModel(
            libraries = listOf(
                CatalogLibrary("jackson-databind", "com.fasterxml.jackson.core:jackson-databind", "2.17.1"),
            ),
            plugins = listOf(CatalogPlugin("boot", "org.springframework.boot", "3.3.0")),
        )

        val dest = CatalogFileWriter.write(model, monorepo)

        dest shouldBe monorepo.resolve("gradle/libs.versions.toml")
        val text = dest.readText()
        text shouldBe CatalogRenderer.render(model)
        text shouldContain
            """jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version = "2.17.1" }"""
    }

    @Test
    fun `creates the gradle directory when absent`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("fresh").also { it.createDirectories() }
        val dest = CatalogFileWriter.write(CatalogModel(emptyList(), emptyList()), monorepo)
        dest.readText() shouldContain "[libraries]"
    }
}
