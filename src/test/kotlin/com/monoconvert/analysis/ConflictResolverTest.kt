package com.monoconvert.analysis

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ConflictResolverTest {

    private fun lib(repo: String, key: String, ver: String) = VersionObservation(
        repo = repo, kind = ItemKind.LIBRARY, key = key, versionExpr = ver,
        resolved = VersionResolver.resolve(ver, emptyMap()),
    )

    @Test
    fun `picks the highest version and flags the conflict`() {
        val items = ConflictResolver.resolve(
            listOf(
                lib("payments", "com.fasterxml.jackson.core:jackson-databind", "2.16.0"),
                lib("billing", "com.fasterxml.jackson.core:jackson-databind", "2.17.1"),
            ),
        )

        val jackson = items.single { it.key == "com.fasterxml.jackson.core:jackson-databind" }
        jackson.winner shouldBe Semver(2, 17, 1)
        jackson.hasConflict shouldBe true
    }

    @Test
    fun `no conflict when a single repo declares a coordinate`() {
        val items = ConflictResolver.resolve(listOf(lib("payments", "g:a", "1.0.0")))
        items.single().hasConflict shouldBe false
        items.single().winner shouldBe Semver(1, 0, 0)
    }

    @Test
    fun `dynamic-only coordinate has a null winner`() {
        val items = ConflictResolver.resolve(listOf(lib("payments", "com.google.guava:guava", "32.+")))
        items.single().winner shouldBe null
        items.single().hasConflict shouldBe false
    }
}
