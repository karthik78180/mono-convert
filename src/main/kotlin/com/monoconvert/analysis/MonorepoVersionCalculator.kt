package com.monoconvert.analysis

import com.monoconvert.MigrationException

/**
 * Computes the single monorepo version: highest `x.y.z` core across all candidate version
 * strings (project versions + lambda.json versions), bumped one major and reset to `x.0.0`
 * (spec §6.2). Suffixes are stripped by [Semver.parseOrNull].
 */
object MonorepoVersionCalculator {

    fun compute(candidateVersions: List<String>): Semver {
        val base = candidateVersions.mapNotNull { Semver.parseOrNull(it) }.maxOrNull()
            ?: throw MigrationException("No parseable version found to compute the monorepo version")
        return Semver(base.major + 1, 0, 0)
    }
}
