package com.monoconvert.assembly

import com.monoconvert.MigrationException
import com.monoconvert.analysis.AnalysisReport
import com.monoconvert.config.Manifest
import com.monoconvert.config.ToolConfig
import com.monoconvert.discovery.RepoInventory
import com.monoconvert.source.SourceRepo
import java.nio.file.Path

/** Result of Phase 3 assembly. */
data class AssemblyResult(
    val monorepoDir: Path,
    val modulePaths: List<String>,
    /** Absolute path of each copied subproject dir (`<monorepoDir>/<target>`); for Plan 4 rewrites. */
    val subprojectDirs: List<Path>,
)

/**
 * Phase 3 orchestrator: materializes the template, copies repos in, and writes the
 * catalog, settings, root version, and consolidated meta. Additive filesystem only.
 */
class AssemblyPhase {

    fun assemble(
        manifest: Manifest,
        toolConfig: ToolConfig,
        outDir: Path,
        carId: String,
        report: AnalysisReport,
        inventories: List<Pair<SourceRepo, RepoInventory>>,
    ): AssemblyResult {
        val templatePath = toolConfig.template.path
        if (templatePath.isNullOrBlank()) {
            throw MigrationException("template.path is required for assembly (set template.path in the config)")
        }

        val name = manifest.monorepo.name
        val monorepoDir = TemplateMaterializer.materialize(Path.of(templatePath), outDir, name)

        val subprojectDirs = inventories.map { (repo, _) -> RepoCopier.copy(repo, monorepoDir) }

        CatalogFileWriter.write(report.catalog, monorepoDir)

        val modulePaths = inventories.flatMap { (repo, inv) -> SettingsGenerator.modulePaths(repo, inv) }
        SettingsGenerator.write(monorepoDir, name, modulePaths)

        RootPropertiesWriter.write(monorepoDir, report.monorepoVersion.toString())

        MetaConsolidator.consolidate(monorepoDir, carId, inventories.map { it.first.target })

        return AssemblyResult(monorepoDir, modulePaths, subprojectDirs)
    }
}
