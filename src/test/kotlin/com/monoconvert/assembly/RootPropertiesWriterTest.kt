package com.monoconvert.assembly

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RootPropertiesWriterTest {

    @Test
    fun `replaces existing version line`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("m").also { it.createDirectories() }
        monorepo.resolve("gradle.properties").writeText("version=0.0.0\n")

        val dest = RootPropertiesWriter.write(monorepo, "4.0.0")

        dest shouldBe monorepo.resolve("gradle.properties")
        dest.readText() shouldBe "version=4.0.0\n"
    }

    @Test
    fun `preserves other properties and order`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("m").also { it.createDirectories() }
        monorepo.resolve("gradle.properties")
            .writeText("org.gradle.jvmargs=-Xmx2g\nversion=1.2.3\nkotlin.code.style=official\n")

        RootPropertiesWriter.write(monorepo, "4.0.0")

        monorepo.resolve("gradle.properties").readText() shouldBe
            "org.gradle.jvmargs=-Xmx2g\nversion=4.0.0\nkotlin.code.style=official\n"
    }

    @Test
    fun `appends version when file has none`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("m").also { it.createDirectories() }
        monorepo.resolve("gradle.properties").writeText("org.gradle.caching=true\n")

        RootPropertiesWriter.write(monorepo, "4.0.0")

        monorepo.resolve("gradle.properties").readText() shouldBe
            "org.gradle.caching=true\nversion=4.0.0\n"
    }

    @Test
    fun `creates file when absent`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("m").also { it.createDirectories() }

        RootPropertiesWriter.write(monorepo, "4.0.0")

        monorepo.resolve("gradle.properties").readText() shouldBe "version=4.0.0\n"
    }
}
