package com.monoconvert.source

import com.monoconvert.MigrationException
import com.monoconvert.config.GitConfig
import com.monoconvert.config.Manifest
import com.monoconvert.config.SourceMode
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory

class RepoResolver(
    private val cloner: GitCloner = ProcessGitCloner(),
    private val workDir: Path = Paths.get(".mono-convert", "work"),
) {
    fun resolve(manifest: Manifest, git: GitConfig): List<SourceRepo> =
        manifest.repos.map { name ->
            val root = when (manifest.source) {
                SourceMode.LOCAL -> resolveLocal(manifest.path!!, name)
                SourceMode.CLONE -> resolveClone(git, name)
            }
            SourceRepo(name = name, target = name, root = root)
        }

    // Contract: a LOCAL manifest's `path` is resolved relative to the process
    // working directory (the monorepo/CI checkout root), not to the manifest
    // file's location. Run the CLI from that root, or use an absolute `path`.
    private fun resolveLocal(basePath: String, name: String): Path {
        val root = Paths.get(basePath, name)
        if (!root.isDirectory()) {
            throw MigrationException("Source repo directory not found: $root (repo '$name')")
        }
        return root
    }

    private fun resolveClone(git: GitConfig, name: String): Path {
        val destination = workDir.resolve(name)
        destination.parent.createDirectories()
        val url = "${git.baseUrl.trimEnd('/')}/$name.git"
        return cloner.clone(url, git.defaultBranch, destination)
    }
}
