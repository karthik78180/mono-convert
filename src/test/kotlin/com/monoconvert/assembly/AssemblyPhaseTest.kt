package com.monoconvert.assembly

import com.monoconvert.MigrationException
import com.monoconvert.TestFixtures
import com.monoconvert.analysis.MigrationAnalyzer
import com.monoconvert.config.ConfigLoader
import com.monoconvert.config.GitConfig
import com.monoconvert.config.TemplateConfig
import com.monoconvert.config.ToolConfig
import com.monoconvert.discovery.RepoScanner
import com.monoconvert.gate.CarIdGate
import com.monoconvert.source.RepoResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

class AssemblyPhaseTest {

    private val loader = ConfigLoader()

    private fun assemble(out: Path, template: String = TestFixtures.path("template").toString()): AssemblyResult {
        val manifest = loader.loadManifest(TestFixtures.path("repos.yaml"))
        val toolConfig = ToolConfig(
            git = GitConfig(baseUrl = "https://example.invalid", defaultBranch = "main"),
            template = TemplateConfig(repo = "monorepo-template", path = template),
        )
        val repos = RepoResolver().resolve(manifest, toolConfig.git)
        val carId = CarIdGate().verify(repos)
        val scanner = RepoScanner()
        val inventories = repos.map { it to scanner.scan(it) }
        val report = MigrationAnalyzer().analyze(inventories)
        return AssemblyPhase().assemble(manifest, toolConfig, out, carId, report, inventories)
    }

    @Test
    fun `assembles the full monorepo tree from fixtures`(@TempDir tmp: Path) {
        val result = assemble(tmp.resolve("out"))

        val mono = tmp.resolve("out/vehicle-platform")
        result.monorepoDir shouldBe mono
        result.modulePaths shouldContainExactlyInAnyOrder listOf(":payments-service", ":billing-service")
        result.subprojectDirs shouldContainExactlyInAnyOrder
            listOf(mono.resolve("payments-service"), mono.resolve("billing-service"))

        mono.resolve("gradle.properties").readText() shouldBe "version=4.0.0\n"
        mono.resolve("meta/source.yaml").readText() shouldBe "carId: 200009890\n"

        val settings = mono.resolve("settings.gradle").readText()
        settings shouldContain "rootProject.name = 'vehicle-platform'"
        settings shouldContain "include ':payments-service'"
        settings shouldContain "include ':billing-service'"

        val catalog = mono.resolve("gradle/libs.versions.toml").readText()
        catalog shouldContain
            """jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version = "2.17.1" }"""

        mono.resolve("payments-service/build.gradle").shouldExist()
        mono.resolve("payments-service/config/charge/lambda.json").shouldExist()
        mono.resolve("payments-service/meta").shouldNotExist()
        mono.resolve("billing-service/build.gradle.kts").shouldExist()
        mono.resolve("billing-service/meta").shouldNotExist()
    }

    @Test
    fun `is idempotent on a second run`(@TempDir tmp: Path) {
        assemble(tmp.resolve("out"))
        val second = assemble(tmp.resolve("out"))
        second.monorepoDir.resolve("gradle.properties").readText() shouldBe "version=4.0.0\n"
    }

    @Test
    fun `throws when template path is not configured`(@TempDir tmp: Path) {
        val ex = shouldThrow<MigrationException> { assemble(tmp.resolve("out"), template = "") }
        ex.message!! shouldContain "template"
    }
}
