package com.monoconvert.source

import java.nio.file.Path

/**
 * Clones a repo into [destination]. Code-supported for `source: clone` runs,
 * but never invoked by tests (tests use `source: local`).
 */
fun interface GitCloner {
    fun clone(url: String, branch: String, destination: Path): Path
}

/** Real implementation shelling out to `git`. Used in production clone runs. */
class ProcessGitCloner : GitCloner {
    override fun clone(url: String, branch: String, destination: Path): Path {
        val process = ProcessBuilder(
            "git", "clone", "--depth", "1", "--branch", branch, url, destination.toString(),
        ).inheritIO().start()
        val exit = process.waitFor()
        check(exit == 0) { "git clone failed for $url (exit $exit)" }
        return destination
    }
}
