# mono-convert — Plan 1: Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the foundation of the `mono-convert` CLI — a Kotlin/JVM Gradle project with local test fixtures, manifest/config parsing, source-repo resolution (local mode), the `carId` identity gate, and per-repo file discovery (Phases 0–1 of the spec).

**Architecture:** A Kotlin/JVM application (Gradle Kotlin DSL build, Clikt CLI) that reads an input manifest + tool config, resolves each source repo on disk (local mode against fixtures; clone mode is code-supported but not exercised by tests), enforces that every repo's `meta/source.yaml` shares the same `carId`, and inventories each repo's Gradle build files, `gradle.properties`, settings files, and `config/**/lambda.json`. All file reads use Jackson (YAML + JSON). The pipeline runs as ordered, journaled phases; this plan implements Phases 0 and 1.

**Tech Stack:** Kotlin 1.9, JDK 17 toolchain, Gradle (Kotlin DSL), Clikt (CLI), Jackson (YAML/JSON + Kotlin module), JUnit 5 + Kotest assertions.

Spec: `docs/superpowers/specs/2026-06-24-gradle-monorepo-migration-design.md`

---

## File Structure

Source (`src/main/kotlin/com/monoconvert/`):
- `cli/MigrateCommand.kt` — Clikt entrypoint, wires options → pipeline.
- `cli/Main.kt` — `main()` that runs the command.
- `config/Manifest.kt` — manifest data classes (`Manifest`, `MonorepoSpec`, `SourceMode`).
- `config/ToolConfig.kt` — tool config data classes (`ToolConfig`, `GitConfig`, `TemplateConfig`).
- `config/ConfigLoader.kt` — loads + validates manifest and tool config from YAML.
- `source/SourceRepo.kt` — resolved repo descriptor (`name`, `target`, `root`).
- `source/GitCloner.kt` — interface + real impl for clone mode (not exercised by tests).
- `source/RepoResolver.kt` — manifest → `List<SourceRepo>` (local or clone).
- `gate/SourceYaml.kt` — `meta/source.yaml` reader.
- `gate/CarIdGate.kt` — asserts identical `carId` across all repos.
- `discovery/RepoInventory.kt` — discovered-files descriptor + `Dsl` enum.
- `discovery/RepoScanner.kt` — walks a repo and produces `RepoInventory`.
- `MigrationException.kt` — typed failures (gate/config errors).

Test (`src/test/kotlin/com/monoconvert/`): one test file per source file above, plus:
- `TestFixtures.kt` — helper to locate the `fixtures/` dir from tests.

Fixtures (repo root `fixtures/`):
- `fixtures/source-repos/payments-service/…` — Groovy DSL sample repo.
- `fixtures/source-repos/billing-service/…` — Kotlin DSL sample repo (2 functions).
- `fixtures/template/…` — sample monorepo template.
- `fixtures/output/.gitkeep` — generated-output target dir (kept empty in git).
- `fixtures/repos.yaml` — sample manifest (local mode).
- `fixtures/mono-convert.config.yaml` — sample tool config.

---

## Task 0: Scaffold the Kotlin/JVM Gradle project

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `.gitignore`
- Create: `src/main/kotlin/com/monoconvert/cli/Main.kt`
- Test: `src/test/kotlin/com/monoconvert/SmokeTest.kt`

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "mono-convert"
```

- [ ] **Step 2: Create `gradle.properties`**

```properties
org.gradle.caching=true
org.gradle.parallel=true
kotlin.code.style=official
```

- [ ] **Step 3: Create `build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "1.9.24"
    application
}

group = "com.monoconvert"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.monoconvert.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Create `.gitignore`**

```gitignore
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
.mono-convert/
fixtures/output/*
!fixtures/output/.gitkeep
```

- [ ] **Step 5: Create a minimal `Main.kt`**

```kotlin
package com.monoconvert.cli

fun main(args: Array<String>) {
    println("mono-convert ${args.joinToString(" ")}")
}
```

- [ ] **Step 6: Write a smoke test**

```kotlin
package com.monoconvert

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SmokeTest {
    @Test
    fun `project compiles and a trivial assertion holds`() {
        (1 + 1) shouldBe 2
    }
}
```

- [ ] **Step 7: Generate the Gradle wrapper and run the build**

Run: `gradle wrapper --gradle-version 8.8 && ./gradlew test`
Expected: BUILD SUCCESSFUL, `SmokeTest` passes. (If `gradle` is not on PATH, install via SDKMAN or use an existing local Gradle to bootstrap the wrapper once.)

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties .gitignore gradlew gradlew.bat gradle/ src/main/kotlin/com/monoconvert/cli/Main.kt src/test/kotlin/com/monoconvert/SmokeTest.kt
git commit -m "chore: scaffold Kotlin/JVM Gradle project for mono-convert"
```

---

## Task 1: Create local test fixtures

These fixtures are the ONLY inputs tests use. Tests must never clone real repos.

**Files:**
- Create: `fixtures/source-repos/payments-service/build.gradle`
- Create: `fixtures/source-repos/payments-service/settings.gradle`
- Create: `fixtures/source-repos/payments-service/gradle.properties`
- Create: `fixtures/source-repos/payments-service/meta/source.yaml`
- Create: `fixtures/source-repos/payments-service/config/charge/lambda.json`
- Create: `fixtures/source-repos/billing-service/build.gradle.kts`
- Create: `fixtures/source-repos/billing-service/settings.gradle.kts`
- Create: `fixtures/source-repos/billing-service/gradle.properties`
- Create: `fixtures/source-repos/billing-service/meta/source.yaml`
- Create: `fixtures/source-repos/billing-service/config/invoice/lambda.json`
- Create: `fixtures/source-repos/billing-service/config/refund/lambda.json`
- Create: `fixtures/template/settings.gradle`
- Create: `fixtures/template/gradle.properties`
- Create: `fixtures/template/gradle/libs.versions.toml`
- Create: `fixtures/output/.gitkeep`
- Create: `fixtures/repos.yaml`
- Create: `fixtures/mono-convert.config.yaml`

- [ ] **Step 1: payments-service (Groovy DSL) — `build.gradle`**

```groovy
plugins {
    id 'java'
}

dependencies {
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.16.0'
    implementation "org.apache.commons:commons-lang3:${commonsLangVersion}"
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
}
```

- [ ] **Step 2: payments-service — `settings.gradle`**

```groovy
rootProject.name = 'payments-service'
```

- [ ] **Step 3: payments-service — `gradle.properties`**

```properties
version=3.7.2
commonsLangVersion=3.13.0
```

- [ ] **Step 4: payments-service — `meta/source.yaml`**

```yaml
carId: 200009890
otherKey: payments
```

- [ ] **Step 5: payments-service — `config/charge/lambda.json`**

```json
{
    "schemaversion" : "3.0.0",
    "version" : "1.0.9",
    "artifactId" : "charge",
    "depAddress" : {
        "functionVersion" : "1.0.9",
        "otherKey" : "value"
    }
}
```

- [ ] **Step 6: billing-service (Kotlin DSL) — `build.gradle.kts`**

```kotlin
plugins {
    java
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("org.apache.commons:commons-lang3:3.12.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
```

- [ ] **Step 7: billing-service — `settings.gradle.kts`**

```kotlin
rootProject.name = "billing-service"
```

- [ ] **Step 8: billing-service — `gradle.properties`**

```properties
version=2.5.0-alpha
```

- [ ] **Step 9: billing-service — `meta/source.yaml`**

```yaml
carId: 200009890
otherKey: billing
```

- [ ] **Step 10: billing-service — `config/invoice/lambda.json`**

```json
{
    "schemaversion" : "3.0.0",
    "version" : "2.4.1",
    "artifactId" : "invoice",
    "depAddress" : {
        "functionVersion" : "2.4.1",
        "otherKey" : "value"
    }
}
```

- [ ] **Step 11: billing-service — `config/refund/lambda.json`**

```json
{
    "schemaversion" : "3.0.0",
    "version" : "2.4.1",
    "artifactId" : "refund",
    "depAddress" : {
        "functionVersion" : "2.4.1",
        "otherKey" : "value"
    }
}
```

- [ ] **Step 12: template — `fixtures/template/settings.gradle`**

```groovy
rootProject.name = 'monorepo-template'
```

- [ ] **Step 13: template — `fixtures/template/gradle.properties`**

```properties
version=0.0.0
```

- [ ] **Step 14: template — `fixtures/template/gradle/libs.versions.toml`**

```toml
[versions]

[libraries]

[plugins]
```

- [ ] **Step 15: output placeholder — `fixtures/output/.gitkeep`**

Create an empty file (the directory is the generated-output target; contents are gitignored except this file).

- [ ] **Step 16: sample manifest — `fixtures/repos.yaml`**

```yaml
monorepo:
  name: vehicle-platform
source: local
path: fixtures/source-repos
repos:
  - payments-service
  - billing-service
```

- [ ] **Step 17: sample tool config — `fixtures/mono-convert.config.yaml`**

```yaml
git:
  baseUrl: "https://github.com/myorg"
  defaultBranch: main
template:
  repo: "monorepo-template"
```

- [ ] **Step 18: Commit**

```bash
git add fixtures/
git commit -m "test: add local source-repo, template, and config fixtures"
```

---

## Task 2: Manifest model + loader

**Files:**
- Create: `src/main/kotlin/com/monoconvert/config/Manifest.kt`
- Create: `src/main/kotlin/com/monoconvert/MigrationException.kt`
- Create: `src/main/kotlin/com/monoconvert/config/ConfigLoader.kt`
- Test: `src/test/kotlin/com/monoconvert/config/ManifestLoaderTest.kt`
- Test: `src/test/kotlin/com/monoconvert/TestFixtures.kt`

- [ ] **Step 1: Write the test-fixtures helper**

```kotlin
package com.monoconvert

import java.nio.file.Path
import java.nio.file.Paths

/** Resolves the repo-root `fixtures/` dir regardless of the test working dir. */
object TestFixtures {
    val root: Path = locateFixtures()

    fun path(relative: String): Path = root.resolve(relative)

    private fun locateFixtures(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("fixtures")
            if (candidate.toFile().isDirectory) return candidate
            dir = dir.parent
        }
        error("Could not locate fixtures/ directory from working dir")
    }
}
```

- [ ] **Step 2: Write the failing manifest-loader test**

```kotlin
package com.monoconvert.config

import com.monoconvert.MigrationException
import com.monoconvert.TestFixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.io.path.writeText

class ManifestLoaderTest {

    private val loader = ConfigLoader()

    @Test
    fun `loads a local-mode manifest with a plain list of repo names`() {
        val manifest = loader.loadManifest(TestFixtures.path("repos.yaml"))

        manifest.monorepo.name shouldBe "vehicle-platform"
        manifest.source shouldBe SourceMode.LOCAL
        manifest.path shouldBe "fixtures/source-repos"
        manifest.repos shouldBe listOf("payments-service", "billing-service")
    }

    @Test
    fun `local mode without a path fails fast`(@org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path) {
        val bad = tmp.resolve("bad.yaml")
        bad.writeText(
            """
            monorepo:
              name: x
            source: local
            repos:
              - a
            """.trimIndent()
        )

        val ex = shouldThrow<MigrationException> { loader.loadManifest(bad) }
        ex.message shouldBe "Manifest 'source: local' requires a root-level 'path'"
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests "com.monoconvert.config.ManifestLoaderTest"`
Expected: FAIL — `ConfigLoader` / `Manifest` unresolved.

- [ ] **Step 4: Create `MigrationException.kt`**

```kotlin
package com.monoconvert

/** Thrown for any expected, user-facing migration failure (config, gate, validation). */
class MigrationException(message: String) : RuntimeException(message)
```

- [ ] **Step 5: Create `Manifest.kt`**

```kotlin
package com.monoconvert.config

import com.fasterxml.jackson.annotation.JsonProperty

enum class SourceMode {
    @JsonProperty("clone") CLONE,
    @JsonProperty("local") LOCAL,
}

data class MonorepoSpec(
    val name: String,
)

data class Manifest(
    val monorepo: MonorepoSpec,
    val source: SourceMode,
    val path: String? = null,
    val repos: List<String> = emptyList(),
)
```

- [ ] **Step 6: Create `ConfigLoader.kt` (manifest portion)**

```kotlin
package com.monoconvert.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.monoconvert.MigrationException
import java.nio.file.Path
import kotlin.io.path.readText

class ConfigLoader {

    private val yaml = jacksonObjectMapper(YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun loadManifest(file: Path): Manifest {
        val manifest = try {
            yaml.readValue<Manifest>(file.readText())
        } catch (e: Exception) {
            throw MigrationException("Failed to parse manifest ${file}: ${e.message}")
        }
        if (manifest.repos.isEmpty()) {
            throw MigrationException("Manifest must list at least one repo")
        }
        if (manifest.source == SourceMode.LOCAL && manifest.path.isNullOrBlank()) {
            throw MigrationException("Manifest 'source: local' requires a root-level 'path'")
        }
        return manifest
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew test --tests "com.monoconvert.config.ManifestLoaderTest"`
Expected: PASS (both tests).

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/monoconvert/MigrationException.kt src/main/kotlin/com/monoconvert/config/Manifest.kt src/main/kotlin/com/monoconvert/config/ConfigLoader.kt src/test/kotlin/com/monoconvert/TestFixtures.kt src/test/kotlin/com/monoconvert/config/ManifestLoaderTest.kt
git commit -m "feat: parse input manifest with local-mode validation"
```

---

## Task 3: Tool config model + loader

**Files:**
- Create: `src/main/kotlin/com/monoconvert/config/ToolConfig.kt`
- Modify: `src/main/kotlin/com/monoconvert/config/ConfigLoader.kt`
- Test: `src/test/kotlin/com/monoconvert/config/ToolConfigLoaderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.config

import com.monoconvert.TestFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ToolConfigLoaderTest {

    private val loader = ConfigLoader()

    @Test
    fun `loads tool config with git base url and template`() {
        val config = loader.loadToolConfig(TestFixtures.path("mono-convert.config.yaml"))

        config.git.baseUrl shouldBe "https://github.com/myorg"
        config.git.defaultBranch shouldBe "main"
        config.template.repo shouldBe "monorepo-template"
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.monoconvert.config.ToolConfigLoaderTest"`
Expected: FAIL — `loadToolConfig` / `ToolConfig` unresolved.

- [ ] **Step 3: Create `ToolConfig.kt`**

```kotlin
package com.monoconvert.config

data class GitConfig(
    val baseUrl: String,
    val defaultBranch: String = "main",
)

data class TemplateConfig(
    val repo: String,
)

data class ToolConfig(
    val git: GitConfig,
    val template: TemplateConfig,
)
```

- [ ] **Step 4: Add `loadToolConfig` to `ConfigLoader`**

Add this method inside the `ConfigLoader` class (below `loadManifest`):

```kotlin
    fun loadToolConfig(file: Path): ToolConfig =
        try {
            yaml.readValue<ToolConfig>(file.readText())
        } catch (e: Exception) {
            throw MigrationException("Failed to parse tool config ${file}: ${e.message}")
        }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "com.monoconvert.config.ToolConfigLoaderTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/monoconvert/config/ToolConfig.kt src/main/kotlin/com/monoconvert/config/ConfigLoader.kt src/test/kotlin/com/monoconvert/config/ToolConfigLoaderTest.kt
git commit -m "feat: parse tool config (git base url + template)"
```

---

## Task 4: Source repo resolution (local mode + clone interface)

**Files:**
- Create: `src/main/kotlin/com/monoconvert/source/SourceRepo.kt`
- Create: `src/main/kotlin/com/monoconvert/source/GitCloner.kt`
- Create: `src/main/kotlin/com/monoconvert/source/RepoResolver.kt`
- Test: `src/test/kotlin/com/monoconvert/source/RepoResolverTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.source

import com.monoconvert.MigrationException
import com.monoconvert.config.GitConfig
import com.monoconvert.config.Manifest
import com.monoconvert.config.MonorepoSpec
import com.monoconvert.config.SourceMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory

class RepoResolverTest {

    private val git = GitConfig(baseUrl = "https://github.com/myorg", defaultBranch = "main")

    // Never clones: clone mode is given a cloner that throws if called.
    private val resolver = RepoResolver(cloner = GitCloner { _, _, _ ->
        error("tests must not clone")
    })

    private fun localManifest(repos: List<String>) = Manifest(
        monorepo = MonorepoSpec("vehicle-platform"),
        source = SourceMode.LOCAL,
        path = "fixtures/source-repos",
        repos = repos,
    )

    @Test
    fun `resolves local repos to existing directories with target defaulting to name`() {
        val resolved = resolver.resolve(localManifest(listOf("payments-service", "billing-service")), git)

        resolved.map { it.name } shouldBe listOf("payments-service", "billing-service")
        resolved.map { it.target } shouldBe listOf("payments-service", "billing-service")
        resolved.all { it.root.isDirectory() } shouldBe true
        resolved[0].root.fileName shouldBe Paths.get("payments-service")
    }

    @Test
    fun `missing local repo directory fails fast`() {
        val ex = shouldThrow<MigrationException> {
            resolver.resolve(localManifest(listOf("does-not-exist")), git)
        }
        ex.message!!.contains("does-not-exist") shouldBe true
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.monoconvert.source.RepoResolverTest"`
Expected: FAIL — `SourceRepo` / `GitCloner` / `RepoResolver` unresolved.

- [ ] **Step 3: Create `SourceRepo.kt`**

```kotlin
package com.monoconvert.source

import java.nio.file.Path

/** A source repo resolved onto the local filesystem, ready to inventory. */
data class SourceRepo(
    val name: String,
    val target: String,
    val root: Path,
)
```

- [ ] **Step 4: Create `GitCloner.kt`**

```kotlin
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
```

- [ ] **Step 5: Create `RepoResolver.kt`**

```kotlin
package com.monoconvert.source

import com.monoconvert.MigrationException
import com.monoconvert.config.GitConfig
import com.monoconvert.config.Manifest
import com.monoconvert.config.SourceMode
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory

class RepoResolver(
    private val cloner: GitCloner = ProcessGitCloner(),
    private val workDir: Path = Paths.get(".mono-convert", "work"),
) {
    fun resolve(manifest: Manifest, git: GitConfig): List<SourceRepo> =
        manifest.repos.map { name ->
            val root = when (manifest.source) {
                SourceMode.LOCAL -> resolveLocal(manifest.path!!, name)
                SourceMode.CLONE -> resolveClone(git, name)
            }
            SourceRepo(name = name, target = name, root = root)
        }

    private fun resolveLocal(basePath: String, name: String): Path {
        val root = Paths.get(basePath, name)
        if (!root.isDirectory()) {
            throw MigrationException("Source repo directory not found: $root (repo '$name')")
        }
        return root
    }

    private fun resolveClone(git: GitConfig, name: String): Path {
        val destination = workDir.resolve(name)
        destination.parent.createDirectories()
        val url = "${git.baseUrl.trimEnd('/')}/$name.git"
        return cloner.clone(url, git.defaultBranch, destination)
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests "com.monoconvert.source.RepoResolverTest"`
Expected: PASS. (Run from the repo root so `fixtures/source-repos` resolves; Gradle sets the working dir to the project root.)

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/monoconvert/source/
git add src/test/kotlin/com/monoconvert/source/RepoResolverTest.kt
git commit -m "feat: resolve source repos (local mode; clone code-supported, untested)"
```

---

## Task 5: source.yaml reader + carId gate

**Files:**
- Create: `src/main/kotlin/com/monoconvert/gate/SourceYaml.kt`
- Create: `src/main/kotlin/com/monoconvert/gate/CarIdGate.kt`
- Test: `src/test/kotlin/com/monoconvert/gate/CarIdGateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.gate

import com.monoconvert.MigrationException
import com.monoconvert.source.SourceRepo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class CarIdGateTest {

    private val gate = CarIdGate()

    private fun repoWithCarId(tmp: Path, name: String, carId: String?): SourceRepo {
        val root = tmp.resolve(name)
        val meta = root.resolve("meta")
        meta.createDirectories()
        val body = if (carId == null) "otherKey: x\n" else "carId: $carId\notherKey: x\n"
        meta.resolve("source.yaml").writeText(body)
        return SourceRepo(name = name, target = name, root = root)
    }

    @Test
    fun `passes and returns the shared carId when all repos agree`(@TempDir tmp: Path) {
        val repos = listOf(
            repoWithCarId(tmp, "a", "200009890"),
            repoWithCarId(tmp, "b", "200009890"),
        )

        gate.verify(repos) shouldBe "200009890"
    }

    @Test
    fun `fails when carIds differ`(@TempDir tmp: Path) {
        val repos = listOf(
            repoWithCarId(tmp, "a", "200009890"),
            repoWithCarId(tmp, "b", "999999999"),
        )

        val ex = shouldThrow<MigrationException> { gate.verify(repos) }
        ex.message!!.contains("carId mismatch") shouldBe true
    }

    @Test
    fun `fails when carId is missing in a repo`(@TempDir tmp: Path) {
        val repos = listOf(repoWithCarId(tmp, "a", null))

        val ex = shouldThrow<MigrationException> { gate.verify(repos) }
        ex.message!!.contains("missing 'carId'") shouldBe true
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.monoconvert.gate.CarIdGateTest"`
Expected: FAIL — `SourceYaml` / `CarIdGate` unresolved.

- [ ] **Step 3: Create `SourceYaml.kt`**

```kotlin
package com.monoconvert.gate

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.monoconvert.MigrationException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Reader for a repo's `meta/source.yaml`. Read-only (per spec: edits go through OpenRewrite). */
object SourceYaml {

    private val yaml = jacksonObjectMapper(YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** Returns the `carId` as a String, or throws if the file or key is missing. */
    fun readCarId(repoRoot: Path): String {
        val file = repoRoot.resolve("meta").resolve("source.yaml")
        if (!file.exists()) {
            throw MigrationException("Repo '${repoRoot.fileName}' is missing meta/source.yaml")
        }
        val map: Map<String, Any?> = yaml.readValue(file.readText())
        val carId = map["carId"]
            ?: throw MigrationException("Repo '${repoRoot.fileName}' meta/source.yaml is missing 'carId'")
        return carId.toString()
    }
}
```

- [ ] **Step 4: Create `CarIdGate.kt`**

```kotlin
package com.monoconvert.gate

import com.monoconvert.MigrationException
import com.monoconvert.source.SourceRepo

/**
 * Phase 1 hard gate: every repo's meta/source.yaml must share the same carId.
 * Returns the shared carId on success; throws MigrationException on any mismatch.
 */
class CarIdGate {

    fun verify(repos: List<SourceRepo>): String {
        require(repos.isNotEmpty()) { "carId gate received no repos" }

        val byRepo = repos.associate { it.name to SourceYaml.readCarId(it.root) }
        val distinct = byRepo.values.toSet()

        if (distinct.size > 1) {
            val detail = byRepo.entries.joinToString(", ") { "${it.key}=${it.value}" }
            throw MigrationException("carId mismatch across repos: $detail")
        }
        return distinct.single()
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "com.monoconvert.gate.CarIdGateTest"`
Expected: PASS (all three tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/monoconvert/gate/
git add src/test/kotlin/com/monoconvert/gate/CarIdGateTest.kt
git commit -m "feat: carId identity gate over source.yaml"
```

---

## Task 6: Repo discovery / inventory

**Files:**
- Create: `src/main/kotlin/com/monoconvert/discovery/RepoInventory.kt`
- Create: `src/main/kotlin/com/monoconvert/discovery/RepoScanner.kt`
- Test: `src/test/kotlin/com/monoconvert/discovery/RepoScannerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.discovery

import com.monoconvert.TestFixtures
import com.monoconvert.source.SourceRepo
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RepoScannerTest {

    private val scanner = RepoScanner()

    private fun fixtureRepo(name: String) = SourceRepo(
        name = name,
        target = name,
        root = TestFixtures.path("source-repos/$name"),
    )

    @Test
    fun `detects Groovy DSL build file and lambda configs in payments-service`() {
        val inv = scanner.scan(fixtureRepo("payments-service"))

        inv.buildFiles.single().dsl shouldBe Dsl.GROOVY
        inv.buildFiles.single().path.fileName.toString() shouldBe "build.gradle"
        inv.gradlePropertiesFiles.size shouldBe 1
        inv.lambdaJsonFiles.map { it.fileName.toString() } shouldContainExactlyInAnyOrder listOf("lambda.json")
    }

    @Test
    fun `detects Kotlin DSL build file and both functions in billing-service`() {
        val inv = scanner.scan(fixtureRepo("billing-service"))

        inv.buildFiles.single().dsl shouldBe Dsl.KOTLIN
        inv.buildFiles.single().path.fileName.toString() shouldBe "build.gradle.kts"
        inv.lambdaJsonFiles.size shouldBe 2
        inv.lambdaJsonFiles.map { it.parent.fileName.toString() }
            .shouldContainExactlyInAnyOrder(listOf("invoice", "refund"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.monoconvert.discovery.RepoScannerTest"`
Expected: FAIL — `RepoInventory` / `RepoScanner` / `Dsl` unresolved.

- [ ] **Step 3: Create `RepoInventory.kt`**

```kotlin
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
```

- [ ] **Step 4: Create `RepoScanner.kt`**

```kotlin
package com.monoconvert.discovery

import com.monoconvert.source.SourceRepo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.toList

/**
 * Walks a resolved source repo and inventories Gradle build files, settings files,
 * gradle.properties, and config/**/lambda.json. Handles nested modules by walking
 * the whole tree (skipping build output / VCS dirs).
 */
class RepoScanner {

    private val ignoredDirs = setOf(".git", ".gradle", "build", ".idea")

    fun scan(repo: SourceRepo): RepoInventory {
        val all = Files.walk(repo.root)
            .filter { Files.isRegularFile(it) }
            .filter { path -> path.none { segment -> segment.name in ignoredDirs } }
            .toList()

        val buildFiles = all.mapNotNull { path ->
            when (path.name) {
                "build.gradle" -> BuildFile(path, Dsl.GROOVY)
                "build.gradle.kts" -> BuildFile(path, Dsl.KOTLIN)
                else -> null
            }
        }
        val settingsFiles = all.filter { it.name == "settings.gradle" || it.name == "settings.gradle.kts" }
        val gradleProps = all.filter { it.name == "gradle.properties" }
        val lambdaJson = all.filter { it.name == "lambda.json" && it.parent.parent?.name == "config" }

        return RepoInventory(
            repoName = repo.name,
            buildFiles = buildFiles,
            settingsFiles = settingsFiles,
            gradlePropertiesFiles = gradleProps,
            lambdaJsonFiles = lambdaJson,
        )
    }

    private fun Path.none(predicate: (Path) -> Boolean): Boolean {
        for (segment in this) if (predicate(segment)) return false
        return true
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "com.monoconvert.discovery.RepoScannerTest"`
Expected: PASS (both tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/monoconvert/discovery/
git add src/test/kotlin/com/monoconvert/discovery/RepoScannerTest.kt
git commit -m "feat: discover build files, settings, properties, and lambda configs"
```

---

## Task 7: CLI wiring (Phases 0–1 end to end)

Wire the pieces into a Clikt `migrate` command that runs Phase 0 (resolve) + Phase 1 (gate + inventory) and prints a summary. No file writes yet.

**Files:**
- Create: `src/main/kotlin/com/monoconvert/cli/MigrateCommand.kt`
- Modify: `src/main/kotlin/com/monoconvert/cli/Main.kt`
- Test: `src/test/kotlin/com/monoconvert/cli/MigrateCommandTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.cli

import com.monoconvert.TestFixtures
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class MigrateCommandTest {

    @Test
    fun `runs preflight and gate against fixtures and reports the shared carId`() {
        val output = MigrateCommand().run(
            manifestPath = TestFixtures.path("repos.yaml"),
            configPath = TestFixtures.path("mono-convert.config.yaml"),
        )

        output shouldContain "carId: 200009890"
        output shouldContain "payments-service"
        output shouldContain "billing-service"
        output shouldContain "build files: 1"   // each fixture repo has exactly one build file
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.monoconvert.cli.MigrateCommandTest"`
Expected: FAIL — `MigrateCommand.run(...)` unresolved.

- [ ] **Step 3: Create `MigrateCommand.kt`**

```kotlin
package com.monoconvert.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.monoconvert.config.ConfigLoader
import com.monoconvert.discovery.RepoScanner
import com.monoconvert.gate.CarIdGate
import com.monoconvert.source.RepoResolver
import java.nio.file.Path

class MigrateCommand : CliktCommand(name = "migrate") {

    private val manifestOpt: Path by option("--manifest", help = "Path to repos.yaml")
        .path(mustExist = true).required()
    private val configOpt: Path by option("--config", help = "Path to mono-convert.config.yaml")
        .path(mustExist = true).required()
    private val dryRun: Boolean by option("--dry-run").flag(default = false)

    override fun run() {
        echo(run(manifestOpt, configOpt, dryRun))
    }

    /** Pure entrypoint used by tests; returns the human-readable summary. */
    fun run(manifestPath: Path, configPath: Path, dryRun: Boolean = false): String {
        val loader = ConfigLoader()
        val manifest = loader.loadManifest(manifestPath)
        val toolConfig = loader.loadToolConfig(configPath)

        // Phase 0: resolve sources (local mode against fixtures; clone untested).
        val repos = RepoResolver().resolve(manifest, toolConfig.git)

        // Phase 1: carId gate + discovery.
        val carId = CarIdGate().verify(repos)
        val scanner = RepoScanner()

        val sb = StringBuilder()
        sb.appendLine("monorepo: ${manifest.monorepo.name}")
        sb.appendLine("carId: $carId")
        sb.appendLine("repos: ${repos.size}${if (dryRun) " (dry-run)" else ""}")
        for (repo in repos) {
            val inv = scanner.scan(repo)
            sb.appendLine(
                "  - ${repo.name} -> ${repo.target} | " +
                    "build files: ${inv.buildFiles.size}, " +
                    "lambda.json: ${inv.lambdaJsonFiles.size}"
            )
        }
        return sb.toString().trimEnd()
    }
}
```

- [ ] **Step 4: Update `Main.kt` to launch the command**

```kotlin
package com.monoconvert.cli

fun main(args: Array<String>) = MigrateCommand().main(args)
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "com.monoconvert.cli.MigrateCommandTest"`
Expected: PASS.

- [ ] **Step 6: Run the full test suite + the CLI end to end**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

Run: `./gradlew run --args="--manifest fixtures/repos.yaml --config fixtures/mono-convert.config.yaml --dry-run"`
Expected: prints the monorepo name, `carId: 200009890`, and a line per fixture repo.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/monoconvert/cli/MigrateCommand.kt src/main/kotlin/com/monoconvert/cli/Main.kt src/test/kotlin/com/monoconvert/cli/MigrateCommandTest.kt
git commit -m "feat: wire migrate command for preflight + gate + discovery (phases 0-1)"
```

---

## Self-Review

**Spec coverage (Plan 1 scope = Phases 0–1):**
- Manifest with root-level `source`/`path` + plain repo-name list → Task 2 ✅ (matches the user's manifest edit).
- Tool config (git base URL + template) → Task 3 ✅.
- `source: local` mandatory path; clone code-supported but untested → Tasks 2, 4 ✅ (clone path implemented, test injects a throwing cloner to guarantee no network).
- `carId` hard gate before any writes → Task 5 ✅.
- Discovery of build files (Groovy/Kotlin), settings, gradle.properties, `config/**/lambda.json`, nested-module aware → Task 6 ✅.
- Fixtures: sample source repos (shared carId, both DSLs, multi-function), template folder, output folder → Task 1 ✅.
- No real clones in tests → enforced structurally in Tasks 4 and 7 ✅.

**Deferred to later plans (intentionally NOT in Plan 1):** dependency/version parsing + conflict resolution + version math (Plan 2); template clone, file relocation, catalog/settings generation, OpenRewrite rewrites, lambda.json stripping (Plan 3); Gradle Tooling API validation, dep-graph diff, journal/resume, rollback, reporting, commit-on-success (Plan 4).

**Placeholder scan:** No TBD/TODO; every code step contains complete, runnable code. ✅

**Type consistency:** `Manifest`, `SourceMode`, `MonorepoSpec`, `ToolConfig`/`GitConfig`/`TemplateConfig`, `SourceRepo(name,target,root)`, `GitCloner.clone(url,branch,destination)`, `RepoResolver.resolve(manifest,git)`, `CarIdGate.verify(repos): String`, `RepoScanner.scan(repo): RepoInventory`, `Dsl`, `BuildFile(path,dsl)`, `MigrateCommand.run(manifestPath,configPath,dryRun)` are consistent across tasks and tests. ✅

---

## Execution Handoff

After this plan is executed and green, the next step is to write **Plan 2 (Analysis)** using the concrete types established here (`SourceRepo`, `RepoInventory`, `BuildFile`).
