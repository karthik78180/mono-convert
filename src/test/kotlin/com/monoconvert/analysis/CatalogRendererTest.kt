package com.monoconvert.analysis

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class CatalogRendererTest {

    @Test
    fun `renders libraries and plugins sections as TOML`() {
        val model = CatalogModel(
            libraries = listOf(
                CatalogLibrary("jackson-databind", "com.fasterxml.jackson.core:jackson-databind", "2.17.1"),
            ),
            plugins = listOf(CatalogPlugin("boot", "org.springframework.boot", "3.3.0")),
        )

        val toml = CatalogRenderer.render(model)

        toml shouldContain "[libraries]"
        toml shouldContain
            """jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version = "2.17.1" }"""
        toml shouldContain "[plugins]"
        toml shouldContain """boot = { id = "org.springframework.boot", version = "3.3.0" }"""
    }
}
