package com.monoconvert.config

import com.monoconvert.TestFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ToolConfigLoaderTest {

    private val loader = ConfigLoader()

    @Test
    fun `loads tool config with git base url and template`() {
        val config = loader.loadToolConfig(TestFixtures.path("mono-convert.config.yaml"))

        config.git.baseUrl shouldBe "https://github.com/myorg"
        config.git.defaultBranch shouldBe "main"
        config.template.repo shouldBe "monorepo-template"
    }
}
