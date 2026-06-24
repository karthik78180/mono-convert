package com.monoconvert.discovery

import com.monoconvert.source.SourceRepo
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.name

/**
 * Walks a resolved source repo and inventories Gradle build files, settings files,
 * gradle.properties, and lambda configs. A lambda config is a `lambda.json` that sits
 * one level under a `config` directory (i.e. `.../config/<function>/lambda.json`); this
 * is robust to the `config` dir living inside a nested module. Walks the whole tree
 * (nested-module aware), skipping build output / VCS dirs.
 */
class RepoScanner {

    private val ignoredDirs = setOf(".git", ".gradle", "build", ".idea")

    fun scan(repo: SourceRepo): RepoInventory {
        val all: List<Path> = Files.walk(repo.root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                // Only inspect segments inside the repo root, so an ignored name
                // (e.g. "build") in the absolute path above the root can't skip files.
                .filter { path -> repo.root.relativize(path).noneSegmentIn(ignoredDirs) }
                .collect(Collectors.toList())
        }

        val buildFiles = all.mapNotNull { path ->
            when (path.name) {
                "build.gradle"     -> BuildFile(path, Dsl.GROOVY)
                "build.gradle.kts" -> BuildFile(path, Dsl.KOTLIN)
                else               -> null
            }
        }
        val settingsFiles = all.filter { it.name == "settings.gradle" || it.name == "settings.gradle.kts" }
        val gradleProps   = all.filter { it.name == "gradle.properties" }
        val lambdaJson    = all.filter { it.name == "lambda.json" && it.parent.parent?.name == "config" }

        return RepoInventory(
            repoName             = repo.name,
            buildFiles           = buildFiles,
            settingsFiles        = settingsFiles,
            gradlePropertiesFiles = gradleProps,
            lambdaJsonFiles      = lambdaJson,
        )
    }

    /** Returns true when no path segment name is in [blocked]. */
    private fun Path.noneSegmentIn(blocked: Set<String>): Boolean {
        for (segment in this) if (segment.name in blocked) return false
        return true
    }
}
