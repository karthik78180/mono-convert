package com.monoconvert.analysis

import com.monoconvert.discovery.RepoInventory
import com.monoconvert.source.SourceRepo

/** The complete read-only analysis output for a migration run (spec Phase 2). */
data class AnalysisReport(
    val monorepoVersion: Semver,
    val items: List<ResolvedItem>,
    val conflicts: List<ResolvedItem>,
    val dynamicItems: List<ResolvedItem>,
    val unparsed: Map<String, List<String>>,
    val catalog: CatalogModel,
    val catalogToml: String,
)

/** Aggregates per-repo analyses into one cross-repo [AnalysisReport]. */
class MigrationAnalyzer {

    private val repoAnalyzer = RepoAnalyzer()

    fun analyze(inventories: List<Pair<SourceRepo, RepoInventory>>): AnalysisReport {
        val analyses = inventories.map { (repo, inv) -> repoAnalyzer.analyze(repo, inv) }

        val items = ConflictResolver.resolve(analyses.flatMap { it.observations })
        val conflicts = items.filter { it.hasConflict }
        val dynamicItems = items.filter { item ->
            item.observations.any { it.resolved is ResolvedVersion.Dynamic }
        }
        val monoVersion = MonorepoVersionCalculator.compute(analyses.flatMap { it.versionCandidates })
        val catalog = CatalogBuilder.build(items)
        val unparsed = analyses
            .filter { it.unparsedLines.isNotEmpty() }
            .associate { it.repo to it.unparsedLines }

        return AnalysisReport(
            monorepoVersion = monoVersion,
            items = items,
            conflicts = conflicts,
            dynamicItems = dynamicItems,
            unparsed = unparsed,
            catalog = catalog,
            catalogToml = CatalogRenderer.render(catalog),
        )
    }
}
