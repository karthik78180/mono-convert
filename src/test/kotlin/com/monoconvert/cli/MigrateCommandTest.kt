package com.monoconvert.cli

import com.monoconvert.TestFixtures
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class MigrateCommandTest {

    private fun run(): String = MigrateCommand().runMigration(
        manifestPath = TestFixtures.path("repos.yaml"),
        configPath = TestFixtures.path("mono-convert.config.yaml"),
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
}
