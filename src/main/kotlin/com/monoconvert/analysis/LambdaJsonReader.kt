package com.monoconvert.analysis

import com.monoconvert.Yaml
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Read-only reader for `config/<function>/lambda.json`. Returns the version candidate strings
 * (top-level `version` + `depAddress.functionVersion`) BEFORE Phase 4 strips them.
 * JSON is valid YAML, so the shared [Yaml.mapper] reads it without a new ObjectMapper.
 */
object LambdaJsonReader {

    fun versionCandidates(lambdaJson: Path): List<String> {
        val node = Yaml.mapper.readTree(lambdaJson.readText())
        return buildList {
            node.get("version")?.asText()?.let { add(it) }
            node.get("depAddress")?.get("functionVersion")?.asText()?.let { add(it) }
        }
    }
}
