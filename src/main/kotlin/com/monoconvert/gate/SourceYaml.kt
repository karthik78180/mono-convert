package com.monoconvert.gate

import com.fasterxml.jackson.module.kotlin.readValue
import com.monoconvert.MigrationException
import com.monoconvert.Yaml
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Reader for a repo's `meta/source.yaml`. Read-only (per spec: edits go through OpenRewrite). */
object SourceYaml {

    private val yaml = Yaml.mapper

    /** Returns the `carId` as a String, or throws if the file or key is missing. */
    fun readCarId(repoRoot: Path): String {
        val file = repoRoot.resolve("meta").resolve("source.yaml")
        if (!file.exists()) {
            throw MigrationException("Repo '${repoRoot.fileName}' is missing meta/source.yaml")
        }
        val map: Map<String, Any?> = try {
            yaml.readValue(file.readText())
        } catch (e: Exception) {
            throw MigrationException(
                "Repo '${repoRoot.fileName}' meta/source.yaml is not valid YAML: ${e.message}", e,
            )
        }
        val carId = map["carId"]
            ?: throw MigrationException("Repo '${repoRoot.fileName}' meta/source.yaml is missing 'carId'")
        val carIdText = carId.toString()
        if (carIdText.isBlank()) {
            throw MigrationException("Repo '${repoRoot.fileName}' meta/source.yaml has a blank 'carId'")
        }
        return carIdText
    }
}
