package com.monoconvert.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.monoconvert.analysis.MigrationAnalyzer
import com.monoconvert.config.ConfigLoader
import com.monoconvert.discovery.RepoScanner
import com.monoconvert.gate.CarIdGate
import com.monoconvert.source.RepoResolver
import java.nio.file.Path

class MigrateCommand : CliktCommand(name = "migrate") {

    private val manifestOpt: Path by option("--manifest", help = "Path to repos.yaml")
        .path(mustExist = true).required()
    private val configOpt: Path by option("--config", help = "Path to mono-convert.config.yaml")
        .path(mustExist = true).required()
    private val dryRun: Boolean by option("--dry-run").flag(default = false)

    private val loader = ConfigLoader()

    override fun run() {
        echo(runMigration(manifestOpt, configOpt, dryRun))
    }

    /**
     * Pure entrypoint used by tests; returns the human-readable summary.
     * Named distinctly from Clikt's [run] to avoid an overload collision with it.
     */
    fun runMigration(manifestPath: Path, configPath: Path, dryRun: Boolean = false): String {
        val manifest = loader.loadManifest(manifestPath)
        val toolConfig = loader.loadToolConfig(configPath)

        // Phase 0: resolve sources (local mode against fixtures; clone untested).
        val repos = RepoResolver().resolve(manifest, toolConfig.git)

        // Phase 1: carId gate + discovery.
        val carId = CarIdGate().verify(repos)
        val scanner = RepoScanner()
        val inventories = repos.map { it to scanner.scan(it) }

        val sb = StringBuilder()
        sb.appendLine("monorepo: ${manifest.monorepo.name}")
        sb.appendLine("carId: $carId")
        sb.appendLine("repos: ${repos.size}${if (dryRun) " (dry-run)" else ""}")
        for ((repo, inv) in inventories) {
            sb.appendLine(
                "  - ${repo.name} -> ${repo.target} | " +
                    "build files: ${inv.buildFiles.size}, lambda.json: ${inv.lambdaJsonFiles.size}",
            )
        }

        // Phase 2: static analysis (dry-run terminal output).
        val report = MigrationAnalyzer().analyze(inventories)
        sb.appendLine("monorepo version: ${report.monorepoVersion}")
        sb.appendLine("conflicts: ${report.conflicts.size}")
        for (c in report.conflicts) {
            val detail = c.observations.joinToString(", ") { "${it.repo}=${it.versionExpr}" }
            sb.appendLine("  ! ${c.key} -> ${c.winner} (was: $detail)")
        }
        if (report.dynamicItems.isNotEmpty()) {
            sb.appendLine(
                "dynamic (flagged, not pinned): " + report.dynamicItems.joinToString(", ") { it.key },
            )
        }
        sb.appendLine("catalog preview:")
        report.catalogToml.trimEnd().lines().forEach { sb.appendLine("  $it") }

        return sb.toString().trimEnd()
    }
}
