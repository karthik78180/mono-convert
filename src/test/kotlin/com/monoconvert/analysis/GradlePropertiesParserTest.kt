package com.monoconvert.analysis

import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class GradlePropertiesParserTest {

    @Test
    fun `parses key=value pairs and trims whitespace`(@TempDir tmp: Path) {
        val file = tmp.resolve("gradle.properties")
        file.writeText(
            """
            version=3.7.2
            commonsLangVersion = 3.13.0
            # a comment
            ! also a comment

            """.trimIndent(),
        )

        GradlePropertiesParser.parse(file) shouldContainExactly mapOf(
            "version" to "3.7.2",
            "commonsLangVersion" to "3.13.0",
        )
    }

    @Test
    fun `returns empty map when the file is missing`(@TempDir tmp: Path) {
        GradlePropertiesParser.parse(tmp.resolve("nope.properties")) shouldBe emptyMap()
    }
}
