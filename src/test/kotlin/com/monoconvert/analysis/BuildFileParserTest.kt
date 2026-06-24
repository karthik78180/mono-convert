package com.monoconvert.analysis

import com.monoconvert.TestFixtures
import com.monoconvert.discovery.BuildFile
import com.monoconvert.discovery.Dsl
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BuildFileParserTest {

    @Test
    fun `parses Groovy deps, versioned plugin, and buildscript classpath in payments-service`() {
        val bf = BuildFile(TestFixtures.path("source-repos/payments-service/build.gradle"), Dsl.GROOVY)

        val contents = BuildFileParser.parse(bf)

        contents.dependencies.map { "${it.group}:${it.artifact}:${it.versionExpr}" }
            .shouldContainExactlyInAnyOrder(
                listOf(
                    "com.fasterxml.jackson.core:jackson-databind:2.16.0",
                    "org.apache.commons:commons-lang3:\${commonsLangVersion}",
                    "com.google.guava:guava:32.+",
                    "org.junit.jupiter:junit-jupiter:5.10.0",
                ),
            )
        contents.plugins.shouldContainExactlyInAnyOrder(
            listOf(RawPlugin("java", null), RawPlugin("org.springframework.boot", "3.3.0")),
        )
        contents.buildscriptClasspath.single().let {
            it.group shouldBe "org.springframework.boot"
            it.artifact shouldBe "spring-boot-gradle-plugin"
            it.versionExpr shouldBe "3.3.0"
        }
        contents.unparsedLines shouldBe emptyList()
    }

    @Test
    fun `parses Kotlin DSL deps in billing-service`() {
        val bf = BuildFile(TestFixtures.path("source-repos/billing-service/build.gradle.kts"), Dsl.KOTLIN)

        val contents = BuildFileParser.parse(bf)

        contents.dependencies.map { "${it.group}:${it.artifact}:${it.versionExpr}" }
            .shouldContainExactlyInAnyOrder(
                listOf(
                    "com.fasterxml.jackson.core:jackson-databind:2.17.1",
                    "org.apache.commons:commons-lang3:3.12.0",
                    "org.junit.jupiter:junit-jupiter:5.10.2",
                ),
            )
        contents.buildscriptClasspath shouldBe emptyList()
        contents.unparsedLines shouldBe emptyList()
    }
}
