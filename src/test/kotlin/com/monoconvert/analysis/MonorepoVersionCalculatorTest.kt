package com.monoconvert.analysis

import com.monoconvert.MigrationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MonorepoVersionCalculatorTest {

    @Test
    fun `takes highest core and bumps the major, resetting minor and patch`() {
        // 3.7.2 is the highest core across project + lambda versions -> 4.0.0
        MonorepoVersionCalculator.compute(
            listOf("3.7.2", "2.5.0-alpha", "1.0.9", "1.0.9", "1.0.9"),
        ) shouldBe Semver(4, 0, 0)
    }

    @Test
    fun `throws when no candidate parses to a semver`() {
        shouldThrow<MigrationException> {
            MonorepoVersionCalculator.compute(listOf("latest.release", ""))
        }
    }
}
