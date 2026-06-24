package com.monoconvert.source

import com.monoconvert.MigrationException
import com.monoconvert.config.GitConfig
import com.monoconvert.config.Manifest
import com.monoconvert.config.MonorepoSpec
import com.monoconvert.config.SourceMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.io.path.isDirectory

class RepoResolverTest {

    private val git = GitConfig(baseUrl = "https://github.com/myorg", defaultBranch = "main")

    // Never clones: clone mode is given a cloner that throws if called.
    private val resolver = RepoResolver(cloner = GitCloner { _, _, _ ->
        error("tests must not clone")
    })

    private fun localManifest(repos: List<String>) = Manifest(
        monorepo = MonorepoSpec("vehicle-platform"),
        source = SourceMode.LOCAL,
        path = "fixtures/source-repos",
        repos = repos,
    )

    @Test
    fun `resolves local repos to existing directories with target defaulting to name`() {
        val resolved = resolver.resolve(localManifest(listOf("payments-service", "billing-service")), git)

        resolved.map { it.name } shouldBe listOf("payments-service", "billing-service")
        resolved.map { it.target } shouldBe listOf("payments-service", "billing-service")
        resolved.all { it.root.isDirectory() } shouldBe true
        resolved[0].root.fileName shouldBe Paths.get("payments-service")
    }

    @Test
    fun `missing local repo directory fails fast`() {
        val ex = shouldThrow<MigrationException> {
            resolver.resolve(localManifest(listOf("does-not-exist")), git)
        }
        ex.message!!.contains("does-not-exist") shouldBe true
    }

    @Test
    fun `clone mode builds baseUrl-name-git and trims a trailing slash, without cloning`() {
        // Capturing cloner records args and returns the destination — never clones.
        val captured = mutableListOf<Triple<String, String, String>>()
        val cloneResolver = RepoResolver(cloner = GitCloner { url, branch, destination ->
            captured.add(Triple(url, branch, destination.fileName.toString()))
            destination
        })
        val cloneManifest = Manifest(
            monorepo = MonorepoSpec("vehicle-platform"),
            source = SourceMode.CLONE,
            path = null,
            repos = listOf("payments-service"),
        )
        val gitWithTrailingSlash = GitConfig(baseUrl = "https://github.com/myorg/", defaultBranch = "main")

        val resolved = cloneResolver.resolve(cloneManifest, gitWithTrailingSlash)

        captured.single() shouldBe Triple(
            "https://github.com/myorg/payments-service.git",
            "main",
            "payments-service",
        )
        resolved.single().name shouldBe "payments-service"
        resolved.single().target shouldBe "payments-service"
    }
}
