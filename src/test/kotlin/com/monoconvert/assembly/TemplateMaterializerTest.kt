package com.monoconvert.assembly

import com.monoconvert.MigrationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class TemplateMaterializerTest {

    @Test
    fun `copies template tree into out under monorepo name`(@TempDir tmp: Path) {
        val template = tmp.resolve("template").also { it.resolve("gradle").createDirectories() }
        template.resolve("settings.gradle").writeText("rootProject.name = 'monorepo-template'\n")
        template.resolve("gradle/libs.versions.toml").writeText("[versions]\n")
        val out = tmp.resolve("out")

        val dest = TemplateMaterializer.materialize(template, out, "vehicle-platform")

        dest shouldBe out.resolve("vehicle-platform")
        dest.resolve("settings.gradle").shouldExist()
        dest.resolve("gradle/libs.versions.toml").readText() shouldBe "[versions]\n"
    }

    @Test
    fun `overwrites existing destination idempotently`(@TempDir tmp: Path) {
        val template = tmp.resolve("template").also { it.createDirectories() }
        template.resolve("gradle.properties").writeText("version=0.0.0\n")
        val out = tmp.resolve("out")

        TemplateMaterializer.materialize(template, out, "m")
        val dest = TemplateMaterializer.materialize(template, out, "m") // second run

        dest.resolve("gradle.properties").readText() shouldBe "version=0.0.0\n"
    }

    @Test
    fun `throws MigrationException when template dir is missing`(@TempDir tmp: Path) {
        val ex = shouldThrow<MigrationException> {
            TemplateMaterializer.materialize(tmp.resolve("nope"), tmp.resolve("out"), "m")
        }
        ex.message!! shouldContain "template"
    }
}
