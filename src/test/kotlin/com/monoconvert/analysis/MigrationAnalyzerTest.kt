package com.monoconvert.analysis

import com.monoconvert.TestFixtures
import com.monoconvert.discovery.RepoScanner
import com.monoconvert.source.SourceRepo
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class MigrationAnalyzerTest {

    private fun fixtureRepo(name: String) =
        SourceRepo(name = name, target = name, root = TestFixtures.path("source-repos/$name"))

    private fun report(): AnalysisReport {
        val scanner = RepoScanner()
        val inventories = listOf("payments-service", "billing-service")
            .map { fixtureRepo(it) }
            .map { it to scanner.scan(it) }
        return MigrationAnalyzer().analyze(inventories)
    }

    @Test
    fun `computes the monorepo version as a major bump`() {
        report().monorepoVersion shouldBe Semver(4, 0, 0)
    }

    @Test
    fun `reports exactly the three real conflicts with the highest winners`() {
        val conflicts = report().conflicts.associate { it.key to it.winner }

        conflicts shouldBe mapOf(
            "com.fasterxml.jackson.core:jackson-databind" to Semver(2, 17, 1),
            "org.apache.commons:commons-lang3" to Semver(3, 13, 0),
            "org.junit.jupiter:junit-jupiter" to Semver(5, 10, 2),
        )
    }

    @Test
    fun `flags guava as dynamic and keeps it out of the catalog`() {
        val r = report()
        r.dynamicItems.map { it.key } shouldContainExactlyInAnyOrder listOf("com.google.guava:guava")
        r.catalog.libraries.none { it.module == "com.google.guava:guava" } shouldBe true
    }

    @Test
    fun `lifts the versioned plugin into the catalog plugins section`() {
        val r = report()
        r.catalog.plugins.single().id shouldBe "org.springframework.boot"
        r.catalogToml shouldContain "[plugins]"
    }
}
