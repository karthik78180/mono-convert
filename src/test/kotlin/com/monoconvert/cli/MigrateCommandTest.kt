package com.monoconvert.cli

import com.monoconvert.TestFixtures
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class MigrateCommandTest {

    @Test
    fun `runs preflight and gate against fixtures and reports the shared carId`() {
        val output = MigrateCommand().runMigration(
            manifestPath = TestFixtures.path("repos.yaml"),
            configPath = TestFixtures.path("mono-convert.config.yaml"),
        )

        output shouldContain "carId: 200009890"
        output shouldContain "payments-service"
        output shouldContain "billing-service"
        output shouldContain "build files: 1"   // each fixture repo has exactly one build file
    }
}
