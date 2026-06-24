package com.monoconvert.analysis

/**
 * The numeric `x.y.z` core of a version. Any pre-release/build suffix
 * (`-alpha`, `-RC1`, `+build`) is stripped before parsing, per spec §6.2 —
 * all comparison/bump math operates on the numeric core only.
 */
data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {

    override fun compareTo(other: Semver): Int =
        compareValuesBy(this, other, Semver::major, Semver::minor, Semver::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val CORE = Regex("""^(\d+)\.(\d+)\.(\d+)""")

        /** Parse the leading numeric core, ignoring any suffix; null if no `x.y.z` core is present. */
        fun parseOrNull(raw: String): Semver? {
            val m = CORE.find(raw.trim()) ?: return null
            return Semver(
                m.groupValues[1].toIntOrNull() ?: return null,
                m.groupValues[2].toIntOrNull() ?: return null,
                m.groupValues[3].toIntOrNull() ?: return null,
            )
        }
    }
}
