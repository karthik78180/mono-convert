package com.monoconvert.config

import com.fasterxml.jackson.module.kotlin.readValue
import com.monoconvert.MigrationException
import com.monoconvert.Yaml
import java.nio.file.Path
import kotlin.io.path.readText

class ConfigLoader {

    private val yaml = Yaml.mapper

    fun loadManifest(file: Path): Manifest {
        val manifest = try {
            yaml.readValue<Manifest>(file.readText())
        } catch (e: MigrationException) {
            throw e
        } catch (e: Exception) {
            throw MigrationException("Failed to parse manifest ${file}: ${e.message}", e)
        }
        if (manifest.repos.isEmpty()) {
            throw MigrationException("Manifest must list at least one repo")
        }
        if (manifest.source == SourceMode.LOCAL && manifest.path.isNullOrBlank()) {
            throw MigrationException("Manifest 'source: local' requires a root-level 'path'")
        }
        return manifest
    }

    fun loadToolConfig(file: Path): ToolConfig =
        try {
            yaml.readValue<ToolConfig>(file.readText())
        } catch (e: MigrationException) {
            throw e
        } catch (e: Exception) {
            throw MigrationException("Failed to parse tool config ${file}: ${e.message}", e)
        }
}
