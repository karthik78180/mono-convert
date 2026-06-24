package com.monoconvert.cli

import com.monoconvert.MigrationException
import com.monoconvert.TestFixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MigrateCommandTest {

    private fun run(): String = MigrateCommand().runMigration(
        manifestPath = TestFixtures.path("repos.yaml"),
        configPath = TestFixtures.path("mono-convert.config.yaml"),
        dryRun = true,
    )

    @Test
    fun `runs preflight and gate against fixtures and reports the shared carId`() {
        val output = run()

        output shouldContain "carId: 200009890"
        output shouldContain "payments-service"
        output shouldContain "billing-service"
        output shouldContain "build files: 1"
    }

    @Test
    fun `reports the computed monorepo version and conflicts`() {
        val output = run()

        output shouldContain "monorepo version: 4.0.0"
        output shouldContain "com.fasterxml.jackson.core:jackson-databind -> 2.17.1"
        output shouldContain "[plugins]"
    }

    @Test
    fun `non dry-run assembles the monorepo into out`(@TempDir tmp: Path) {
        val out = tmp.resolve("out")
        val output = MigrateCommand().runMigration(
            manifestPath = TestFixtures.path("repos.yaml"),
            configPath = TestFixtures.path("mono-convert.config.yaml"),
            dryRun = false,
            outDir = out,
        )

        output shouldContain "assembled: "
        output shouldContain "modules: :payments-service, :billing-service"
        out.resolve("vehicle-platform/settings.gradle").exists() shouldBe true
        out.resolve("vehicle-platform/gradle.properties").readText() shouldBe "version=4.0.0\n"
    }

    @Test
    fun `non dry-run without out fails closed`() {
        val ex = shouldThrow<MigrationException> {
            MigrateCommand().runMigration(
                manifestPath = TestFixtures.path("repos.yaml"),
                configPath = TestFixtures.path("mono-convert.config.yaml"),
                dryRun = false,
                outDir = null,
            )
        }
        ex.message!! shouldContain "--out"
    }

    @Test
    fun `dry-run writes nothing even when out is provided`(@TempDir tmp: Path) {
        val out = tmp.resolve("out")
        MigrateCommand().runMigration(
            manifestPath = TestFixtures.path("repos.yaml"),
            configPath = TestFixtures.path("mono-convert.config.yaml"),
            dryRun = true,
            outDir = out,
        )

        out.shouldNotExist()
    }
}
