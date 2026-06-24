package com.monoconvert.analysis

import com.monoconvert.TestFixtures
import com.monoconvert.discovery.RepoScanner
import com.monoconvert.source.SourceRepo
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RepoAnalyzerTest {

    private fun fixtureRepo(name: String) =
        SourceRepo(name = name, target = name, root = TestFixtures.path("source-repos/$name"))

    @Test
    fun `resolves payments-service deps, plugin, classpath, and version candidates`() {
        val repo = fixtureRepo("payments-service")
        val inventory = RepoScanner().scan(repo)

        val analysis = RepoAnalyzer().analyze(repo, inventory)

        // commons-lang3 resolved from ${commonsLangVersion}=3.13.0
        val commons = analysis.observations.single { it.key == "org.apache.commons:commons-lang3" }
        (commons.resolved as ResolvedVersion.Fixed).semver shouldBe Semver(3, 13, 0)

        // guava is dynamic
        val guava = analysis.observations.single { it.key == "com.google.guava:guava" }
        (guava.resolved is ResolvedVersion.Dynamic) shouldBe true

        // versioned plugin is observed as a PLUGIN
        analysis.observations.map { it.kind to it.key } shouldContain
            (ItemKind.PLUGIN to "org.springframework.boot")

        // buildscript classpath observed as a LIBRARY
        analysis.observations.map { it.key } shouldContain "org.springframework.boot:spring-boot-gradle-plugin"

        // version candidates: project version + the lambda.json versions
        analysis.versionCandidates shouldContainAll listOf("3.7.2", "1.0.9")
    }
}
