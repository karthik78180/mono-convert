package com.monoconvert.assembly

import com.monoconvert.discovery.RepoInventory
import com.monoconvert.source.SourceRepo
import java.nio.file.Path
import kotlin.io.path.writeText

/** Phase 3: derives module include-paths and writes the root `settings.gradle` (Groovy DSL). */
object SettingsGenerator {

    /** Gradle include paths for one repo. Root build file -> ":<target>"; nested -> ":<target>:<sub>". */
    fun modulePaths(repo: SourceRepo, inventory: RepoInventory): List<String> =
        inventory.buildFiles.map { bf ->
            val rel = repo.root.relativize(bf.path.parent).toString()
            if (rel.isEmpty()) {
                ":${repo.target}"
            } else {
                ":${repo.target}:" + rel.split('/', '\\').filter { it.isNotEmpty() }.joinToString(":")
            }
        }

    /** Settings file body: rootProject.name plus one include per module path. */
    fun render(monorepoName: String, modulePaths: List<String>): String = buildString {
        appendLine("rootProject.name = '$monorepoName'")
        for (p in modulePaths) appendLine("include '$p'")
    }

    /** Writes `<monorepoDir>/settings.gradle`; returns its path. */
    fun write(monorepoDir: Path, monorepoName: String, modulePaths: List<String>): Path {
        val dest = monorepoDir.resolve("settings.gradle")
        dest.writeText(render(monorepoName, modulePaths))
        return dest
    }
}
