package com.monoconvert.gate

import com.monoconvert.MigrationException
import com.monoconvert.source.SourceRepo

/**
 * Phase 1 hard gate: every repo's meta/source.yaml must share the same carId.
 * Returns the shared carId on success; throws MigrationException on any mismatch.
 */
class CarIdGate {

    fun verify(repos: List<SourceRepo>): String {
        if (repos.isEmpty()) {
            throw MigrationException("carId gate received no repos")
        }

        val byRepo = repos.associate { it.name to SourceYaml.readCarId(it.root) }
        val distinct = byRepo.values.toSet()

        if (distinct.size > 1) {
            val detail = byRepo.entries.joinToString(", ") { "${it.key}=${it.value}" }
            throw MigrationException("carId mismatch across repos: $detail")
        }
        return distinct.single()
    }
}
