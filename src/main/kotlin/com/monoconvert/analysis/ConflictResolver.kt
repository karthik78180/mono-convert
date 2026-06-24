package com.monoconvert.analysis

/** Aggregates per-repo observations into one resolution per item: highest semver wins (spec §6.1). */
object ConflictResolver {

    fun resolve(observations: List<VersionObservation>): List<ResolvedItem> =
        observations.groupBy { it.kind to it.key }
            .map { (k, obs) ->
                val fixed = obs.mapNotNull { (it.resolved as? ResolvedVersion.Fixed)?.semver }
                ResolvedItem(
                    kind = k.first,
                    key = k.second,
                    winner = fixed.maxOrNull(),
                    observations = obs,
                    hasConflict = fixed.distinct().size > 1,
                )
            }
            .sortedWith(compareBy({ it.kind }, { it.key }))
}
