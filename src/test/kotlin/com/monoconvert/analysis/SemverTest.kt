package com.monoconvert.analysis

import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.Test

class SemverTest {

    @Test
    fun `parses a plain x_y_z core`() {
        Semver.parseOrNull("3.7.2") shouldBe Semver(3, 7, 2)
    }

    @Test
    fun `strips a pre-release suffix to the numeric core`() {
        Semver.parseOrNull("2.5.0-alpha") shouldBe Semver(2, 5, 0)
        Semver.parseOrNull("1.0.0-RC1") shouldBe Semver(1, 0, 0)
    }

    @Test
    fun `returns null when there is no x_y_z core`() {
        Semver.parseOrNull("latest.release").shouldBeNull()
        Semver.parseOrNull("32.+").shouldBeNull()
        Semver.parseOrNull("32.0.+").shouldBeNull()
        Semver.parseOrNull("").shouldBeNull()
        Semver.parseOrNull("   ").shouldBeNull()
    }

    @Test
    fun `strips a build-metadata suffix to the numeric core`() {
        Semver.parseOrNull("1.0.0+build.42") shouldBe Semver(1, 0, 0)
    }

    @Test
    fun `orders by major then minor then patch`() {
        (Semver(2, 16, 0) < Semver(2, 17, 1)) shouldBe true
        listOf(Semver(2, 16, 0), Semver(2, 17, 1), Semver(2, 16, 5)).max() shouldBe Semver(2, 17, 1)
    }

    @Test
    fun `renders as x_y_z`() {
        Semver(4, 0, 0).toString() shouldBe "4.0.0"
    }
}
