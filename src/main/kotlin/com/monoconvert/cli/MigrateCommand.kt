package com.monoconvert.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
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

        val sb = StringBuilder()
        sb.appendLine("monorepo: ${manifest.monorepo.name}")
        sb.appendLine("carId: $carId")
        sb.appendLine("repos: ${repos.size}${if (dryRun) " (dry-run)" else ""}")
        for (repo in repos) {
            val inv = scanner.scan(repo)
            sb.appendLine(
                "  - ${repo.name} -> ${repo.target} | " +
                    "build files: ${inv.buildFiles.size}, " +
                    "lambda.json: ${inv.lambdaJsonFiles.size}"
            )
        }
        return sb.toString().trimEnd()
    }
}
