package com.monoconvert.analysis

/** Whether a versioned item becomes a catalog library or a catalog plugin. */
enum class ItemKind { LIBRARY, PLUGIN }

/** One declaration of a versioned item in one repo, with its resolution outcome. */
data class VersionObservation(
    val repo: String,
    val kind: ItemKind,
    val key: String,            // "group:artifact" for LIBRARY, plugin id for PLUGIN
    val versionExpr: String,
    val resolved: ResolvedVersion,
)

/** All observations of one item across all repos, with the highest-wins winner. */
data class ResolvedItem(
    val kind: ItemKind,
    val key: String,
    val winner: Semver?,        // null when every observation is dynamic/unresolved
    val observations: List<VersionObservation>,
    val hasConflict: Boolean,   // >1 distinct fixed version observed
)
