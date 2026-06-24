package com.monoconvert.analysis

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CatalogBuilderTest {

    private fun item(kind: ItemKind, key: String, winner: Semver?) =
        ResolvedItem(kind = kind, key = key, winner = winner, observations = emptyList(), hasConflict = false)

    @Test
    fun `builds libraries and plugins, skipping items without a winner`() {
        val model = CatalogBuilder.build(
            listOf(
                item(ItemKind.LIBRARY, "com.fasterxml.jackson.core:jackson-databind", Semver(2, 17, 1)),
                item(ItemKind.LIBRARY, "com.google.guava:guava", null), // dynamic -> skipped
                item(ItemKind.PLUGIN, "org.springframework.boot", Semver(3, 3, 0)),
            ),
        )

        model.libraries.single() shouldBe CatalogLibrary(
            alias = "jackson-databind",
            module = "com.fasterxml.jackson.core:jackson-databind",
            version = "2.17.1",
        )
        model.plugins.single() shouldBe CatalogPlugin(
            alias = "boot",
            id = "org.springframework.boot",
            version = "3.3.0",
        )
    }

    @Test
    fun `de-collides library aliases that share an artifact name by group segment`() {
        val model = CatalogBuilder.build(
            listOf(
                item(ItemKind.LIBRARY, "com.foo:core", Semver(1, 0, 0)),
                item(ItemKind.LIBRARY, "com.bar:core", Semver(2, 0, 0)),
            ),
        )

        model.libraries.map { it.alias }.sorted() shouldBe listOf("bar-core", "foo-core")
    }
}
