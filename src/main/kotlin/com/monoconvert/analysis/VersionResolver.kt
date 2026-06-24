package com.monoconvert.analysis

/** Outcome of resolving a raw version expression against `gradle.properties`. */
sealed interface ResolvedVersion {
    /** A concrete numeric version. */
    data class Fixed(val semver: Semver, val raw: String) : ResolvedVersion
    /** A dynamic selector (`1.+`, `latest.release`, ranges) — never auto-pinned. */
    data class Dynamic(val raw: String) : ResolvedVersion
    /** No usable version: missing, unknown property, or unparseable. */
    data class Unresolved(val raw: String, val reason: String) : ResolvedVersion
}

object VersionResolver {

    private val PROP_REF = Regex("""\$\{?([A-Za-z0-9_.]+)}?""")

    /** Gradle dynamic selectors: `1.+`, `latest.release`, or a maven range `[..]`/`(..)`. */
    fun isDynamic(raw: String): Boolean {
        val t = raw.trim()
        return t.endsWith("+") || t == "latest.release" || t.startsWith("[") || t.startsWith("(")
    }

    /** Resolve [versionExpr] (possibly a property reference) against [props]. Never throws. */
    fun resolve(versionExpr: String?, props: Map<String, String>): ResolvedVersion {
        if (versionExpr.isNullOrBlank()) {
            return ResolvedVersion.Unresolved(versionExpr ?: "", "no version declared")
        }
        val expr = versionExpr.trim()
        val match = PROP_REF.matchEntire(expr)
        val raw = if (match != null) {
            val key = match.groupValues[1]
            props[key] ?: return ResolvedVersion.Unresolved(expr, "unknown property '$key'")
        } else {
            expr
        }
        if (isDynamic(raw)) return ResolvedVersion.Dynamic(raw)
        val semver = Semver.parseOrNull(raw)
            ?: return ResolvedVersion.Unresolved(expr, "not a semver: '$raw'")
        return ResolvedVersion.Fixed(semver, raw)
    }
}
