package com.monoconvert.analysis

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class VersionResolverTest {

    private val props = mapOf("commonsLangVersion" to "3.13.0")

    @Test
    fun `resolves a literal version to Fixed`() {
        val r = VersionResolver.resolve("2.16.0", props)
        r.shouldBeInstanceOf<ResolvedVersion.Fixed>()
        r.semver shouldBe Semver(2, 16, 0)
    }

    @Test
    fun `resolves a property reference against gradle properties`() {
        val r = VersionResolver.resolve("\${commonsLangVersion}", props)
        r.shouldBeInstanceOf<ResolvedVersion.Fixed>()
        r.semver shouldBe Semver(3, 13, 0)
    }

    @Test
    fun `flags dynamic versions`() {
        VersionResolver.resolve("32.+", props).shouldBeInstanceOf<ResolvedVersion.Dynamic>()
        VersionResolver.resolve("latest.release", props).shouldBeInstanceOf<ResolvedVersion.Dynamic>()
        VersionResolver.resolve("[1.0,2.0)", props).shouldBeInstanceOf<ResolvedVersion.Dynamic>()
    }

    @Test
    fun `reports an unknown property as Unresolved`() {
        VersionResolver.resolve("\${missingVersion}", props)
            .shouldBeInstanceOf<ResolvedVersion.Unresolved>()
    }

    @Test
    fun `reports a missing version as Unresolved`() {
        VersionResolver.resolve(null, props).shouldBeInstanceOf<ResolvedVersion.Unresolved>()
    }
}
