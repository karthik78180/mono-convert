package com.monoconvert.assembly

import com.monoconvert.discovery.BuildFile
import com.monoconvert.discovery.Dsl
import com.monoconvert.discovery.RepoInventory
import com.monoconvert.source.SourceRepo
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

class SettingsGeneratorTest {

    private fun inv(repoRoot: Path, vararg buildFiles: Path) = RepoInventory(
        repoName = repoRoot.fileName.toString(),
        buildFiles = buildFiles.map { BuildFile(it, Dsl.GROOVY) },
        settingsFiles = emptyList(),
        gradlePropertiesFiles = emptyList(),
        lambdaJsonFiles = emptyList(),
    )

    @Test
    fun `root build file yields colon target include path`(@TempDir tmp: Path) {
        val root = tmp.resolve("payments-service").also { it.createDirectories() }
        val repo = SourceRepo("payments-service", "payments-service", root)

        val paths = SettingsGenerator.modulePaths(repo, inv(root, root.resolve("build.gradle")))

        paths shouldContainExactly listOf(":payments-service")
    }

    @Test
    fun `nested module yields colon-separated path`(@TempDir tmp: Path) {
        val root = tmp.resolve("billing").also { it.resolve("core").createDirectories() }
        val repo = SourceRepo("billing", "billing", root)

        val paths = SettingsGenerator.modulePaths(
            repo,
            inv(root, root.resolve("build.gradle"), root.resolve("core/build.gradle")),
        )

        paths shouldContainExactly listOf(":billing", ":billing:core")
    }

    @Test
    fun `render emits rootProject name and include lines`() {
        val body = SettingsGenerator.render("vehicle-platform", listOf(":payments-service", ":billing-service"))

        body shouldContain "rootProject.name = 'vehicle-platform'"
        body shouldContain "include ':payments-service'"
        body shouldContain "include ':billing-service'"
    }

    @Test
    fun `write produces settings gradle at monorepo root`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("vehicle-platform").also { it.createDirectories() }

        val dest = SettingsGenerator.write(monorepo, "vehicle-platform", listOf(":payments-service"))

        dest shouldBe monorepo.resolve("settings.gradle")
        dest.readText() shouldContain "include ':payments-service'"
    }
}
