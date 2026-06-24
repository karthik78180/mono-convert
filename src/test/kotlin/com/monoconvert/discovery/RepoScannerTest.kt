package com.monoconvert.discovery

import com.monoconvert.TestFixtures
import com.monoconvert.source.SourceRepo
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class RepoScannerTest {

    private val scanner = RepoScanner()

    private fun fixtureRepo(name: String) = SourceRepo(
        name = name,
        target = name,
        root = TestFixtures.path("source-repos/$name"),
    )

    @Test
    fun `detects Groovy DSL build file and lambda configs in payments-service`() {
        val inv = scanner.scan(fixtureRepo("payments-service"))

        inv.buildFiles.single().dsl shouldBe Dsl.GROOVY
        inv.buildFiles.single().path.fileName.toString() shouldBe "build.gradle"
        inv.gradlePropertiesFiles.size shouldBe 1
        inv.settingsFiles.single().fileName.toString() shouldBe "settings.gradle"
        inv.lambdaJsonFiles.map { it.fileName.toString() } shouldContainExactlyInAnyOrder listOf("lambda.json")
    }

    @Test
    fun `skips files inside ignored build and git directories`(@TempDir tmp: Path) {
        val root = tmp.resolve("repo")
        root.createDirectories()
        root.resolve("build.gradle").writeText("// real")
        root.resolve("build").createDirectories()
        root.resolve("build/leftover.gradle").writeText("// generated output")
        root.resolve(".git").createDirectories()
        root.resolve(".git/build.gradle").writeText("// vcs noise")

        val inv = scanner.scan(SourceRepo(name = "repo", target = "repo", root = root))

        inv.buildFiles.map { it.path.fileName.toString() } shouldBe listOf("build.gradle")
    }

    @Test
    fun `detects Kotlin DSL build file and both functions in billing-service`() {
        val inv = scanner.scan(fixtureRepo("billing-service"))

        inv.buildFiles.single().dsl shouldBe Dsl.KOTLIN
        inv.buildFiles.single().path.fileName.toString() shouldBe "build.gradle.kts"
        inv.lambdaJsonFiles.size shouldBe 2
        inv.lambdaJsonFiles.map { it.parent.fileName.toString() }
            .shouldContainExactlyInAnyOrder(listOf("invoice", "refund"))
    }
}
