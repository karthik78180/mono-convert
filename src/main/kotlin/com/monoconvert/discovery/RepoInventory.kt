package com.monoconvert.discovery

import java.nio.file.Path

enum class Dsl { GROOVY, KOTLIN }

/** A discovered Gradle build file plus its DSL flavour. */
data class BuildFile(
    val path: Path,
    val dsl: Dsl,
)

/** Everything Phase 1 discovers about one source repo. */
data class RepoInventory(
    val repoName: String,
    val buildFiles: List<BuildFile>,
    val settingsFiles: List<Path>,
    val gradlePropertiesFiles: List<Path>,
    val lambdaJsonFiles: List<Path>,
)
