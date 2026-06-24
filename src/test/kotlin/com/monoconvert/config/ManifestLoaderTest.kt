package com.monoconvert.config

import com.monoconvert.MigrationException
import com.monoconvert.TestFixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.io.path.writeText

class ManifestLoaderTest {

    private val loader = ConfigLoader()

    @Test
    fun `loads a local-mode manifest with a plain list of repo names`() {
        val manifest = loader.loadManifest(TestFixtures.path("repos.yaml"))

        manifest.monorepo.name shouldBe "vehicle-platform"
        manifest.source shouldBe SourceMode.LOCAL
        manifest.path shouldBe "fixtures/source-repos"
        manifest.repos shouldBe listOf("payments-service", "billing-service")
    }

    @Test
    fun `local mode without a path fails fast`(@org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path) {
        val bad = tmp.resolve("bad.yaml")
        bad.writeText(
            """
            monorepo:
              name: x
            source: local
            repos:
              - a
            """.trimIndent()
        )

        val ex = shouldThrow<MigrationException> { loader.loadManifest(bad) }
        ex.message shouldBe "Manifest 'source: local' requires a root-level 'path'"
    }
}
