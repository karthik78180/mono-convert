package com.monoconvert.analysis

import com.monoconvert.discovery.RepoInventory
import com.monoconvert.source.SourceRepo

/** Per-repo analysis output: all versioned observations, unparsed lines, and version candidates. */
data class RepoAnalysis(
    val repo: String,
    val observations: List<VersionObservation>,
    val unparsedLines: List<String>,
    val versionCandidates: List<String>,
)

/** Runs the read-only parsers over one repo's inventory and resolves every version. */
class RepoAnalyzer {

    fun analyze(repo: SourceRepo, inventory: RepoInventory): RepoAnalysis {
        // Phase 2 assumes a single root gradle.properties per repo; nested-module
        // properties are out of scope here (handled when Plan 3 walks nested modules).
        val props = inventory.gradlePropertiesFiles.firstOrNull()
            ?.let { GradlePropertiesParser.parse(it) } ?: emptyMap()

        val observations = mutableListOf<VersionObservation>()
        val unparsed = mutableListOf<String>()

        for (bf in inventory.buildFiles) {
            val contents = BuildFileParser.parse(bf)
            unparsed += contents.unparsedLines
            for (d in contents.dependencies + contents.buildscriptClasspath) {
                observations += VersionObservation(
                    repo = repo.name,
                    kind = ItemKind.LIBRARY,
                    key = "${d.group}:${d.artifact}",
                    versionExpr = d.versionExpr ?: "",
                    resolved = VersionResolver.resolve(d.versionExpr, props),
                )
            }
            for (p in contents.plugins) {
                if (p.versionExpr == null) continue // unversioned core plugins (e.g. java) stay put
                observations += VersionObservation(
                    repo = repo.name,
                    kind = ItemKind.PLUGIN,
                    key = p.id,
                    versionExpr = p.versionExpr,
                    resolved = VersionResolver.resolve(p.versionExpr, props),
                )
            }
        }

        val versionCandidates = buildList {
            props["version"]?.let { add(it) }
            for (lj in inventory.lambdaJsonFiles) addAll(LambdaJsonReader.versionCandidates(lj))
        }

        return RepoAnalysis(repo.name, observations, unparsed, versionCandidates)
    }
}
