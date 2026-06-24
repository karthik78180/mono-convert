# mono-convert Plan 2 (Analysis) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Phase 2 (static, read-only analysis) of mono-convert — parse every dependency, plugin, buildscript-classpath and project/lambda version across all source repos, resolve conflicts (highest-semver-wins), compute the single monorepo version (major bump → `x.0.0`), and emit a `libs.versions.toml` catalog preview.

**Architecture:** A new `com.monoconvert.analysis` package of small, single-responsibility units. Reading is done with lightweight Kotlin parsers (regex + property resolution) — **no OpenRewrite and no Gradle execution** in this plan. The analyzer consumes the `RepoInventory` produced by Phase 1 (`RepoScanner`) and produces an in-memory `AnalysisReport` plus a rendered TOML preview string, wired into `MigrateCommand.runMigration`. Writing the TOML file, `settings.gradle` `include(...)` generation, buildscript relocation, and all in-file rewrites are **deferred to Plan 3**.

**Tech Stack:** Kotlin/JVM, Clikt, Jackson (YAML mapper reused to read JSON), JUnit 5 + Kotest assertions. Build/test runs with Java 21 pinned (`JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test`).

---

## Background the implementer needs

- All production code lives under `src/main/kotlin/com/monoconvert/`; tests mirror it under `src/test/kotlin/com/monoconvert/`.
- **User-facing failures throw `com.monoconvert.MigrationException(message, cause?)`** — never `IllegalArgumentException`/`IllegalStateException`.
- Reuse the shared YAML mapper `com.monoconvert.Yaml.mapper` for any JSON/YAML reads (JSON is valid YAML, so `Yaml.mapper.readTree(jsonText)` works). Do **not** construct new `ObjectMapper`s.
- Existing Phase-1 types you build on:
  - `com.monoconvert.source.SourceRepo(name: String, target: String, root: java.nio.file.Path)`
  - `com.monoconvert.discovery.RepoInventory(repoName, buildFiles: List<BuildFile>, settingsFiles, gradlePropertiesFiles: List<Path>, lambdaJsonFiles: List<Path>)`
  - `com.monoconvert.discovery.BuildFile(path: Path, dsl: Dsl)` and `enum Dsl { GROOVY, KOTLIN }`
- Tests load fixtures via `com.monoconvert.TestFixtures.path("relative/under/fixtures")`.
- **Run the whole suite after each task** with the Java-21 pin:
  ```bash
  JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test
  ```
- Run a single test class:
  ```bash
  JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.SemverTest"
  ```

## File structure (created in this plan)

```
src/main/kotlin/com/monoconvert/analysis/
  Semver.kt                  # numeric x.y.z core value type (Task 1)
  GradlePropertiesParser.kt  # gradle.properties -> Map (Task 2)
  VersionResolver.kt         # ResolvedVersion + resolve()/isDynamic() (Task 3)
  BuildFileParser.kt         # RawDependency/RawPlugin/BuildFileContents + parser (Task 5)
  Observations.kt            # ItemKind, VersionObservation, ResolvedItem (Task 6)
  ConflictResolver.kt        # highest-wins aggregation (Task 6)
  MonorepoVersionCalculator.kt  # major-bump version math (Task 7)
  LambdaJsonReader.kt        # read version candidates from lambda.json (Task 8)
  Catalog.kt                 # CatalogModel + CatalogBuilder (alias de-collision) (Task 9)
  CatalogRenderer.kt         # render libs.versions.toml preview (Task 10)
  RepoAnalyzer.kt            # RepoAnalysis + per-repo orchestration (Task 11)
  MigrationAnalyzer.kt       # AnalysisReport + cross-repo aggregation (Task 12)
```

Fixture data changed: `fixtures/source-repos/payments-service/build.gradle` (Task 4).
Wiring + docs: `cli/MigrateCommand.kt` (Task 13), `CLAUDE.md` + `README.md` (Task 14).

---

### Task 1: Semver value type

**Files:**
- Create: `src/main/kotlin/com/monoconvert/analysis/Semver.kt`
- Test: `src/test/kotlin/com/monoconvert/analysis/SemverTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.analysis

import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.Test

class SemverTest {

    @Test
    fun `parses a plain x_y_z core`() {
        Semver.parseOrNull("3.7.2") shouldBe Semver(3, 7, 2)
    }

    @Test
    fun `strips a pre-release suffix to the numeric core`() {
        Semver.parseOrNull("2.5.0-alpha") shouldBe Semver(2, 5, 0)
        Semver.parseOrNull("1.0.0-RC1") shouldBe Semver(1, 0, 0)
    }

    @Test
    fun `returns null when there is no x_y_z core`() {
        Semver.parseOrNull("latest.release").shouldBeNull()
        Semver.parseOrNull("32.+").shouldBeNull()
    }

    @Test
    fun `orders by major then minor then patch`() {
        (Semver(2, 16, 0) < Semver(2, 17, 1)) shouldBe true
        listOf(Semver(2, 16, 0), Semver(2, 17, 1), Semver(2, 16, 5)).max() shouldBe Semver(2, 17, 1)
    }

    @Test
    fun `renders as x_y_z`() {
        Semver(4, 0, 0).toString() shouldBe "4.0.0"
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.SemverTest"`
Expected: FAIL — `Semver` is unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
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
            return Semver(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.SemverTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/analysis/Semver.kt src/test/kotlin/com/monoconvert/analysis/SemverTest.kt
git commit -m "feat(analysis): add Semver numeric-core value type"
```

---

### Task 2: GradlePropertiesParser

**Files:**
- Create: `src/main/kotlin/com/monoconvert/analysis/GradlePropertiesParser.kt`
- Test: `src/test/kotlin/com/monoconvert/analysis/GradlePropertiesParserTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.analysis

import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class GradlePropertiesParserTest {

    @Test
    fun `parses key=value pairs and trims whitespace`(@TempDir tmp: Path) {
        val file = tmp.resolve("gradle.properties")
        file.writeText(
            """
            version=3.7.2
            commonsLangVersion = 3.13.0
            # a comment
            ! also a comment

            """.trimIndent(),
        )

        GradlePropertiesParser.parse(file) shouldContainExactly mapOf(
            "version" to "3.7.2",
            "commonsLangVersion" to "3.13.0",
        )
    }

    @Test
    fun `returns empty map when the file is missing`(@TempDir tmp: Path) {
        GradlePropertiesParser.parse(tmp.resolve("nope.properties")) shouldBe emptyMap()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.GradlePropertiesParserTest"`
Expected: FAIL — `GradlePropertiesParser` is unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.monoconvert.analysis

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/** Reads a repo's `gradle.properties` into a flat key→value map. Read-only. */
object GradlePropertiesParser {

    fun parse(file: Path): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") && it.contains("=") }
            .associate {
                val idx = it.indexOf('=')
                it.substring(0, idx).trim() to it.substring(idx + 1).trim()
            }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.GradlePropertiesParserTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/analysis/GradlePropertiesParser.kt src/test/kotlin/com/monoconvert/analysis/GradlePropertiesParserTest.kt
git commit -m "feat(analysis): add GradlePropertiesParser"
```

---

### Task 3: VersionResolver + ResolvedVersion

**Files:**
- Create: `src/main/kotlin/com/monoconvert/analysis/VersionResolver.kt`
- Test: `src/test/kotlin/com/monoconvert/analysis/VersionResolverTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.analysis

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class VersionResolverTest {

    private val props = mapOf("commonsLangVersion" to "3.13.0")

    @Test
    fun `resolves a literal version to Fixed`() {
        val r = VersionResolver.resolve("2.16.0", props)
        r.shouldBeInstanceOf<ResolvedVersion.Fixed>()
        r.semver shouldBe Semver(2, 16, 0)
    }

    @Test
    fun `resolves a property reference against gradle properties`() {
        val r = VersionResolver.resolve("\${commonsLangVersion}", props)
        r.shouldBeInstanceOf<ResolvedVersion.Fixed>()
        r.semver shouldBe Semver(3, 13, 0)
    }

    @Test
    fun `flags dynamic versions`() {
        VersionResolver.resolve("32.+", props).shouldBeInstanceOf<ResolvedVersion.Dynamic>()
        VersionResolver.resolve("latest.release", props).shouldBeInstanceOf<ResolvedVersion.Dynamic>()
        VersionResolver.resolve("[1.0,2.0)", props).shouldBeInstanceOf<ResolvedVersion.Dynamic>()
    }

    @Test
    fun `reports an unknown property as Unresolved`() {
        VersionResolver.resolve("\${missingVersion}", props)
            .shouldBeInstanceOf<ResolvedVersion.Unresolved>()
    }

    @Test
    fun `reports a missing version as Unresolved`() {
        VersionResolver.resolve(null, props).shouldBeInstanceOf<ResolvedVersion.Unresolved>()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.VersionResolverTest"`
Expected: FAIL — `VersionResolver`/`ResolvedVersion` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
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

    /** Resolve [versionExpr] (possibly a `${'$'}{prop}` reference) against [props]. Never throws. */
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.VersionResolverTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/analysis/VersionResolver.kt src/test/kotlin/com/monoconvert/analysis/VersionResolverTest.kt
git commit -m "feat(analysis): add VersionResolver with dynamic-version flagging"
```

---

### Task 4: Enrich the payments-service fixture (test data)

This adds a versioned plugin, a dynamic dependency, and a `buildscript { classpath ... }` block so later parser/analyzer tasks exercise the spec's named edge cases. No production code changes; the existing suite must stay green.

**Files:**
- Modify: `fixtures/source-repos/payments-service/build.gradle`

- [ ] **Step 1: Replace the fixture build file with the enriched version**

Overwrite `fixtures/source-repos/payments-service/build.gradle` with exactly:

```groovy
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath 'org.springframework.boot:spring-boot-gradle-plugin:3.3.0'
    }
}

plugins {
    id 'java'
    id 'org.springframework.boot' version '3.3.0'
}

dependencies {
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.16.0'
    implementation "org.apache.commons:commons-lang3:${commonsLangVersion}"
    implementation 'com.google.guava:guava:32.+'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
}
```

- [ ] **Step 2: Run the full suite to confirm Phase-1 tests still pass**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test`
Expected: PASS — `RepoScannerTest` still sees exactly one build file (`build.gradle`), one settings file, one `gradle.properties`, one `lambda.json`; `MigrateCommandTest` still passes.

- [ ] **Step 3: Commit**

```bash
git add fixtures/source-repos/payments-service/build.gradle
git commit -m "test(fixtures): enrich payments-service with plugin, dynamic dep, buildscript classpath"
```

---

### Task 5: BuildFileParser (deps, plugins, buildscript classpath)

Block-aware, regex-based extraction that works for both Groovy and Kotlin DSL. The buildscript block is extracted and removed first so its nested `dependencies` block is not mistaken for top-level dependencies.

**Files:**
- Create: `src/main/kotlin/com/monoconvert/analysis/BuildFileParser.kt`
- Test: `src/test/kotlin/com/monoconvert/analysis/BuildFileParserTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.analysis

import com.monoconvert.TestFixtures
import com.monoconvert.discovery.BuildFile
import com.monoconvert.discovery.Dsl
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BuildFileParserTest {

    @Test
    fun `parses Groovy deps, versioned plugin, and buildscript classpath in payments-service`() {
        val bf = BuildFile(TestFixtures.path("source-repos/payments-service/build.gradle"), Dsl.GROOVY)

        val contents = BuildFileParser.parse(bf)

        contents.dependencies.map { "${it.group}:${it.artifact}:${it.versionExpr}" }
            .shouldContainExactlyInAnyOrder(
                listOf(
                    "com.fasterxml.jackson.core:jackson-databind:2.16.0",
                    "org.apache.commons:commons-lang3:\${commonsLangVersion}",
                    "com.google.guava:guava:32.+",
                    "org.junit.jupiter:junit-jupiter:5.10.0",
                ),
            )
        contents.plugins.shouldContainExactlyInAnyOrder(
            listOf(RawPlugin("java", null), RawPlugin("org.springframework.boot", "3.3.0")),
        )
        contents.buildscriptClasspath.single().let {
            it.group shouldBe "org.springframework.boot"
            it.artifact shouldBe "spring-boot-gradle-plugin"
            it.versionExpr shouldBe "3.3.0"
        }
    }

    @Test
    fun `parses Kotlin DSL deps in billing-service`() {
        val bf = BuildFile(TestFixtures.path("source-repos/billing-service/build.gradle.kts"), Dsl.KOTLIN)

        val contents = BuildFileParser.parse(bf)

        contents.dependencies.map { "${it.group}:${it.artifact}:${it.versionExpr}" }
            .shouldContainExactlyInAnyOrder(
                listOf(
                    "com.fasterxml.jackson.core:jackson-databind:2.17.1",
                    "org.apache.commons:commons-lang3:3.12.0",
                    "org.junit.jupiter:junit-jupiter:5.10.2",
                ),
            )
        contents.buildscriptClasspath shouldBe emptyList()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.BuildFileParserTest"`
Expected: FAIL — `BuildFileParser`/`RawPlugin` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.monoconvert.analysis

import com.monoconvert.discovery.BuildFile
import kotlin.io.path.readText

/** A dependency declaration as written in a build file (version not yet resolved). */
data class RawDependency(
    val configuration: String,
    val group: String,
    val artifact: String,
    val versionExpr: String?,
)

/** A plugin declaration from a `plugins {}` block. */
data class RawPlugin(
    val id: String,
    val versionExpr: String?,
)

/** Everything [BuildFileParser] extracts from a single build file. */
data class BuildFileContents(
    val dependencies: List<RawDependency>,
    val plugins: List<RawPlugin>,
    val buildscriptClasspath: List<RawDependency>,
    val unparsedLines: List<String>,
)

/**
 * Lightweight, block-aware parser for Groovy and Kotlin Gradle DSL. Read-only:
 * extracts coordinates/versions for analysis. In-file rewrites happen later via OpenRewrite.
 */
object BuildFileParser {

    private val CONFIGS = setOf(
        "implementation", "api", "testImplementation", "testRuntimeOnly", "runtimeOnly",
        "compileOnly", "annotationProcessor", "kapt", "classpath", "developmentOnly",
    )

    // configuration + quoted "group:artifact:version" (Groovy: `cfg 'x'`, Kotlin: `cfg("x")`).
    private val DEP = Regex("""(\w+)\s*\(?\s*["']([^"']+)["']\s*\)?""")
    // `id 'x' [version 'y']` / `id("x") [version "y"]`.
    private val PLUGIN = Regex(
        """id\s*\(?\s*["']([^"']+)["']\s*\)?(?:\s*version\s*\(?\s*["']([^"']+)["']\s*\)?)?""",
    )

    private data class Block(val body: String, val span: IntRange)

    fun parse(buildFile: BuildFile): BuildFileContents {
        var text = buildFile.path.readText()

        val classpath = mutableListOf<RawDependency>()
        val unparsed = mutableListOf<String>()

        // buildscript { ... dependencies { classpath ... } } — handled and removed first.
        extractBlock(text, "buildscript")?.let { bs ->
            extractBlock(bs.body, "dependencies")?.let { inner ->
                parseDeps(inner.body, classpath, unparsed)
            }
            text = text.removeRange(bs.span)
        }

        val plugins = extractBlock(text, "plugins")?.let { parsePlugins(it.body) } ?: emptyList()

        val deps = mutableListOf<RawDependency>()
        extractBlock(text, "dependencies")?.let { parseDeps(it.body, deps, unparsed) }

        return BuildFileContents(deps, plugins, classpath, unparsed)
    }

    /** Extracts the body and full span of the first top-level `name { ... }` block (brace-balanced). */
    private fun extractBlock(text: String, name: String): Block? {
        val header = Regex("""(?m)^[ \t]*$name[ \t]*\{""").find(text) ?: return null
        val open = text.indexOf('{', header.range.first)
        var depth = 1
        var j = open + 1
        val sb = StringBuilder()
        while (j < text.length && depth > 0) {
            when (text[j]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth > 0) sb.append(text[j])
            j++
        }
        return Block(sb.toString(), header.range.first until j)
    }

    private fun parseDeps(body: String, out: MutableList<RawDependency>, unparsed: MutableList<String>) {
        for (raw in body.lines()) {
            val line = raw.substringBefore("//").trim()
            if (line.isEmpty()) continue
            val m = DEP.find(line)
            if (m != null && m.groupValues[1] in CONFIGS) {
                val parts = m.groupValues[2].split(":")
                if (parts.size >= 2) {
                    out += RawDependency(m.groupValues[1], parts[0], parts[1], parts.getOrNull(2))
                    continue
                }
            }
            if (line.takeWhile { it.isLetterOrDigit() } in CONFIGS) unparsed += line
        }
    }

    private fun parsePlugins(body: String): List<RawPlugin> =
        body.lines().mapNotNull { raw ->
            val line = raw.substringBefore("//").trim()
            val m = PLUGIN.find(line) ?: return@mapNotNull null
            RawPlugin(m.groupValues[1], m.groupValues[2].ifEmpty { null })
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.BuildFileParserTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/analysis/BuildFileParser.kt src/test/kotlin/com/monoconvert/analysis/BuildFileParserTest.kt
git commit -m "feat(analysis): add BuildFileParser for deps, plugins, buildscript classpath"
```

---

### Task 6: Observations model + ConflictResolver (highest-wins)

**Files:**
- Create: `src/main/kotlin/com/monoconvert/analysis/Observations.kt`
- Create: `src/main/kotlin/com/monoconvert/analysis/ConflictResolver.kt`
- Test: `src/test/kotlin/com/monoconvert/analysis/ConflictResolverTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.analysis

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ConflictResolverTest {

    private fun lib(repo: String, key: String, ver: String) = VersionObservation(
        repo = repo, kind = ItemKind.LIBRARY, key = key, versionExpr = ver,
        resolved = VersionResolver.resolve(ver, emptyMap()),
    )

    @Test
    fun `picks the highest version and flags the conflict`() {
        val items = ConflictResolver.resolve(
            listOf(
                lib("payments", "com.fasterxml.jackson.core:jackson-databind", "2.16.0"),
                lib("billing", "com.fasterxml.jackson.core:jackson-databind", "2.17.1"),
            ),
        )

        val jackson = items.single { it.key == "com.fasterxml.jackson.core:jackson-databind" }
        jackson.winner shouldBe Semver(2, 17, 1)
        jackson.hasConflict shouldBe true
    }

    @Test
    fun `no conflict when a single repo declares a coordinate`() {
        val items = ConflictResolver.resolve(listOf(lib("payments", "g:a", "1.0.0")))
        items.single().hasConflict shouldBe false
        items.single().winner shouldBe Semver(1, 0, 0)
    }

    @Test
    fun `dynamic-only coordinate has a null winner`() {
        val items = ConflictResolver.resolve(listOf(lib("payments", "com.google.guava:guava", "32.+")))
        items.single().winner shouldBe null
        items.single().hasConflict shouldBe false
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.ConflictResolverTest"`
Expected: FAIL — `VersionObservation`/`ItemKind`/`ConflictResolver` unresolved.

- [ ] **Step 3: Write minimal implementation**

`Observations.kt`:

```kotlin
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
```

`ConflictResolver.kt`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.ConflictResolverTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/analysis/Observations.kt src/main/kotlin/com/monoconvert/analysis/ConflictResolver.kt src/test/kotlin/com/monoconvert/analysis/ConflictResolverTest.kt
git commit -m "feat(analysis): add observation model and highest-wins ConflictResolver"
```

---

### Task 7: MonorepoVersionCalculator (major bump)

**Files:**
- Create: `src/main/kotlin/com/monoconvert/analysis/MonorepoVersionCalculator.kt`
- Test: `src/test/kotlin/com/monoconvert/analysis/MonorepoVersionCalculatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.analysis

import com.monoconvert.MigrationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MonorepoVersionCalculatorTest {

    @Test
    fun `takes highest core and bumps the major, resetting minor and patch`() {
        // 3.7.2 is the highest core across project + lambda versions -> 4.0.0
        MonorepoVersionCalculator.compute(
            listOf("3.7.2", "2.5.0-alpha", "1.0.9", "1.0.9", "1.0.9"),
        ) shouldBe Semver(4, 0, 0)
    }

    @Test
    fun `throws when no candidate parses to a semver`() {
        shouldThrow<MigrationException> {
            MonorepoVersionCalculator.compute(listOf("latest.release", ""))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.MonorepoVersionCalculatorTest"`
Expected: FAIL — `MonorepoVersionCalculator` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.MonorepoVersionCalculatorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/analysis/MonorepoVersionCalculator.kt src/test/kotlin/com/monoconvert/analysis/MonorepoVersionCalculatorTest.kt
git commit -m "feat(analysis): add MonorepoVersionCalculator (major bump)"
```

---

### Task 8: LambdaJsonReader (version candidates)

**Files:**
- Create: `src/main/kotlin/com/monoconvert/analysis/LambdaJsonReader.kt`
- Test: `src/test/kotlin/com/monoconvert/analysis/LambdaJsonReaderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.analysis

import com.monoconvert.TestFixtures
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test

class LambdaJsonReaderTest {

    @Test
    fun `reads top-level version and depAddress functionVersion`() {
        val file = TestFixtures.path("source-repos/payments-service/config/charge/lambda.json")

        LambdaJsonReader.versionCandidates(file)
            .shouldContainExactlyInAnyOrder(listOf("1.0.9", "1.0.9"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.LambdaJsonReaderTest"`
Expected: FAIL — `LambdaJsonReader` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.monoconvert.analysis

import com.monoconvert.Yaml
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Read-only reader for `config/<function>/lambda.json`. Returns the version candidate strings
 * (top-level `version` + `depAddress.functionVersion`) BEFORE Phase 4 strips them.
 * JSON is valid YAML, so the shared [Yaml.mapper] reads it without a new ObjectMapper.
 */
object LambdaJsonReader {

    fun versionCandidates(lambdaJson: Path): List<String> {
        val node = Yaml.mapper.readTree(lambdaJson.readText())
        return buildList {
            node.get("version")?.asText()?.let { add(it) }
            node.get("depAddress")?.get("functionVersion")?.asText()?.let { add(it) }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.LambdaJsonReaderTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/analysis/LambdaJsonReader.kt src/test/kotlin/com/monoconvert/analysis/LambdaJsonReaderTest.kt
git commit -m "feat(analysis): add LambdaJsonReader for version candidates"
```

---

### Task 9: CatalogModel + CatalogBuilder (alias de-collision)

**Files:**
- Create: `src/main/kotlin/com/monoconvert/analysis/Catalog.kt`
- Test: `src/test/kotlin/com/monoconvert/analysis/CatalogBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.analysis

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CatalogBuilderTest {

    private fun item(kind: ItemKind, key: String, winner: Semver?) =
        ResolvedItem(kind = kind, key = key, winner = winner, observations = emptyList(), hasConflict = false)

    @Test
    fun `builds libraries and plugins, skipping items without a winner`() {
        val model = CatalogBuilder.build(
            listOf(
                item(ItemKind.LIBRARY, "com.fasterxml.jackson.core:jackson-databind", Semver(2, 17, 1)),
                item(ItemKind.LIBRARY, "com.google.guava:guava", null), // dynamic -> skipped
                item(ItemKind.PLUGIN, "org.springframework.boot", Semver(3, 3, 0)),
            ),
        )

        model.libraries.single() shouldBe CatalogLibrary(
            alias = "jackson-databind",
            module = "com.fasterxml.jackson.core:jackson-databind",
            version = "2.17.1",
        )
        model.plugins.single() shouldBe CatalogPlugin(
            alias = "boot",
            id = "org.springframework.boot",
            version = "3.3.0",
        )
    }

    @Test
    fun `de-collides library aliases that share an artifact name by group segment`() {
        val model = CatalogBuilder.build(
            listOf(
                item(ItemKind.LIBRARY, "com.foo:core", Semver(1, 0, 0)),
                item(ItemKind.LIBRARY, "com.bar:core", Semver(2, 0, 0)),
            ),
        )

        model.libraries.map { it.alias }.sorted() shouldBe listOf("bar-core", "foo-core")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.CatalogBuilderTest"`
Expected: FAIL — `CatalogBuilder`/`CatalogLibrary`/`CatalogPlugin`/`CatalogModel` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.monoconvert.analysis

/** A `[libraries]` catalog entry. */
data class CatalogLibrary(val alias: String, val module: String, val version: String)

/** A `[plugins]` catalog entry. */
data class CatalogPlugin(val alias: String, val id: String, val version: String)

/** The unified version catalog model (libraries + plugins). */
data class CatalogModel(val libraries: List<CatalogLibrary>, val plugins: List<CatalogPlugin>)

/**
 * Builds a [CatalogModel] from resolved items. Only items with a fixed winner are catalog-ized
 * (dynamic/unresolved items are reported elsewhere, never auto-pinned). Library aliases default to
 * the artifact id, de-collided by the last group segment when two artifacts share a name.
 * Plugin alias derivation (last id segment) is intentionally simple; formalizing naming is a Plan 3 item.
 */
object CatalogBuilder {

    fun build(items: List<ResolvedItem>): CatalogModel {
        val libItems = items.filter { it.kind == ItemKind.LIBRARY && it.winner != null }
        val libModules = libItems.map { it.key }
        val libraries = libItems
            .map { CatalogLibrary(libraryAlias(it.key, libModules), it.key, it.winner!!.toString()) }
            .sortedBy { it.alias }
        val plugins = items
            .filter { it.kind == ItemKind.PLUGIN && it.winner != null }
            .map { CatalogPlugin(pluginAlias(it.key), it.key, it.winner!!.toString()) }
            .sortedBy { it.alias }
        return CatalogModel(libraries, plugins)
    }

    private fun libraryAlias(module: String, allModules: List<String>): String {
        val parts = module.split(":")
        val group = parts[0]
        val artifact = parts.getOrElse(1) { module }
        val base = artifact.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val collides = allModules.any { it != module && it.split(":").getOrNull(1) == artifact }
        return if (collides) "${group.substringAfterLast('.')}-$base" else base
    }

    private fun pluginAlias(id: String): String = id.substringAfterLast('.')
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.CatalogBuilderTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/analysis/Catalog.kt src/test/kotlin/com/monoconvert/analysis/CatalogBuilderTest.kt
git commit -m "feat(analysis): add CatalogModel and CatalogBuilder with alias de-collision"
```

---

### Task 10: CatalogRenderer (TOML preview)

**Files:**
- Create: `src/main/kotlin/com/monoconvert/analysis/CatalogRenderer.kt`
- Test: `src/test/kotlin/com/monoconvert/analysis/CatalogRendererTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.analysis

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class CatalogRendererTest {

    @Test
    fun `renders libraries and plugins sections as TOML`() {
        val model = CatalogModel(
            libraries = listOf(
                CatalogLibrary("jackson-databind", "com.fasterxml.jackson.core:jackson-databind", "2.17.1"),
            ),
            plugins = listOf(CatalogPlugin("boot", "org.springframework.boot", "3.3.0")),
        )

        val toml = CatalogRenderer.render(model)

        toml shouldContain "[libraries]"
        toml shouldContain
            """jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version = "2.17.1" }"""
        toml shouldContain "[plugins]"
        toml shouldContain """boot = { id = "org.springframework.boot", version = "3.3.0" }"""
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.CatalogRendererTest"`
Expected: FAIL — `CatalogRenderer` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.monoconvert.analysis

/**
 * Renders a [CatalogModel] as a `gradle/libs.versions.toml` preview string. Versions are inlined
 * per entry; sharing `[versions]` `version.ref`s across entries is a later optimization (spec §6.3).
 */
object CatalogRenderer {

    fun render(model: CatalogModel): String {
        val sb = StringBuilder()
        sb.appendLine("[libraries]")
        for (lib in model.libraries) {
            sb.appendLine("""${lib.alias} = { module = "${lib.module}", version = "${lib.version}" }""")
        }
        if (model.plugins.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[plugins]")
            for (p in model.plugins) {
                sb.appendLine("""${p.alias} = { id = "${p.id}", version = "${p.version}" }""")
            }
        }
        return sb.toString().trimEnd() + "\n"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.CatalogRendererTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/analysis/CatalogRenderer.kt src/test/kotlin/com/monoconvert/analysis/CatalogRendererTest.kt
git commit -m "feat(analysis): add CatalogRenderer for libs.versions.toml preview"
```

---

### Task 11: RepoAnalyzer (per-repo orchestration)

**Files:**
- Create: `src/main/kotlin/com/monoconvert/analysis/RepoAnalyzer.kt`
- Test: `src/test/kotlin/com/monoconvert/analysis/RepoAnalyzerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.analysis

import com.monoconvert.TestFixtures
import com.monoconvert.discovery.RepoScanner
import com.monoconvert.source.SourceRepo
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import org.junit.jupiter.api.Test

class RepoAnalyzerTest {

    private fun fixtureRepo(name: String) =
        SourceRepo(name = name, target = name, root = TestFixtures.path("source-repos/$name"))

    @Test
    fun `resolves payments-service deps, plugin, classpath, and version candidates`() {
        val repo = fixtureRepo("payments-service")
        val inventory = RepoScanner().scan(repo)

        val analysis = RepoAnalyzer().analyze(repo, inventory)

        // commons-lang3 resolved from ${commonsLangVersion}=3.13.0
        val commons = analysis.observations.single { it.key == "org.apache.commons:commons-lang3" }
        (commons.resolved as ResolvedVersion.Fixed).semver shouldBe Semver(3, 13, 0)

        // guava is dynamic
        val guava = analysis.observations.single { it.key == "com.google.guava:guava" }
        (guava.resolved is ResolvedVersion.Dynamic) shouldBe true

        // versioned plugin is observed as a PLUGIN
        analysis.observations.map { it.kind to it.key } shouldContain
            (ItemKind.PLUGIN to "org.springframework.boot")

        // buildscript classpath observed as a LIBRARY
        analysis.observations.map { it.key } shouldContain "org.springframework.boot:spring-boot-gradle-plugin"

        // version candidates: project version + the lambda.json versions
        analysis.versionCandidates shouldContainAll listOf("3.7.2", "1.0.9")
    }
}
```

> Note: this test uses `shouldBe` from `io.kotest.matchers.shouldBe` — add the import
> `import io.kotest.matchers.shouldBe` at the top alongside the existing imports.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.RepoAnalyzerTest"`
Expected: FAIL — `RepoAnalyzer`/`RepoAnalysis` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.monoconvert.analysis

import com.monoconvert.discovery.RepoInventory
import com.monoconvert.source.SourceRepo

/** Per-repo analysis output: all versioned observations, unparsed lines, and version candidates. */
data class RepoAnalysis(
    val repo: String,
    val observations: List<VersionObservation>,
    val unparsedLines: List<String>,
    val versionCandidates: List<String>,
)

/** Runs the read-only parsers over one repo's inventory and resolves every version. */
class RepoAnalyzer {

    fun analyze(repo: SourceRepo, inventory: RepoInventory): RepoAnalysis {
        val props = inventory.gradlePropertiesFiles.firstOrNull()
            ?.let { GradlePropertiesParser.parse(it) } ?: emptyMap()

        val observations = mutableListOf<VersionObservation>()
        val unparsed = mutableListOf<String>()

        for (bf in inventory.buildFiles) {
            val contents = BuildFileParser.parse(bf)
            unparsed += contents.unparsedLines
            for (d in contents.dependencies + contents.buildscriptClasspath) {
                observations += VersionObservation(
                    repo = repo.name,
                    kind = ItemKind.LIBRARY,
                    key = "${d.group}:${d.artifact}",
                    versionExpr = d.versionExpr ?: "",
                    resolved = VersionResolver.resolve(d.versionExpr, props),
                )
            }
            for (p in contents.plugins) {
                if (p.versionExpr == null) continue // unversioned core plugins (e.g. java) stay put
                observations += VersionObservation(
                    repo = repo.name,
                    kind = ItemKind.PLUGIN,
                    key = p.id,
                    versionExpr = p.versionExpr,
                    resolved = VersionResolver.resolve(p.versionExpr, props),
                )
            }
        }

        val versionCandidates = buildList {
            props["version"]?.let { add(it) }
            for (lj in inventory.lambdaJsonFiles) addAll(LambdaJsonReader.versionCandidates(lj))
        }

        return RepoAnalysis(repo.name, observations, unparsed, versionCandidates)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.RepoAnalyzerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/analysis/RepoAnalyzer.kt src/test/kotlin/com/monoconvert/analysis/RepoAnalyzerTest.kt
git commit -m "feat(analysis): add RepoAnalyzer per-repo orchestration"
```

---

### Task 12: MigrationAnalyzer + AnalysisReport (cross-repo)

**Files:**
- Create: `src/main/kotlin/com/monoconvert/analysis/MigrationAnalyzer.kt`
- Test: `src/test/kotlin/com/monoconvert/analysis/MigrationAnalyzerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.analysis

import com.monoconvert.TestFixtures
import com.monoconvert.discovery.RepoScanner
import com.monoconvert.source.SourceRepo
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MigrationAnalyzerTest {

    private fun fixtureRepo(name: String) =
        SourceRepo(name = name, target = name, root = TestFixtures.path("source-repos/$name"))

    private fun report(): AnalysisReport {
        val scanner = RepoScanner()
        val inventories = listOf("payments-service", "billing-service")
            .map { fixtureRepo(it) }
            .map { it to scanner.scan(it) }
        return MigrationAnalyzer().analyze(inventories)
    }

    @Test
    fun `computes the monorepo version as a major bump`() {
        report().monorepoVersion shouldBe Semver(4, 0, 0)
    }

    @Test
    fun `reports exactly the three real conflicts with the highest winners`() {
        val conflicts = report().conflicts.associate { it.key to it.winner }

        conflicts shouldBe mapOf(
            "com.fasterxml.jackson.core:jackson-databind" to Semver(2, 17, 1),
            "org.apache.commons:commons-lang3" to Semver(3, 13, 0),
            "org.junit.jupiter:junit-jupiter" to Semver(5, 10, 2),
        )
    }

    @Test
    fun `flags guava as dynamic and keeps it out of the catalog`() {
        val r = report()
        r.dynamicItems.map { it.key } shouldContainExactlyInAnyOrder listOf("com.google.guava:guava")
        r.catalog.libraries.none { it.module == "com.google.guava:guava" } shouldBe true
    }

    @Test
    fun `lifts the versioned plugin into the catalog plugins section`() {
        val r = report()
        r.catalog.plugins.single().id shouldBe "org.springframework.boot"
        r.catalogToml shouldContain "[plugins]"
    }
}
```

> Note: add `import io.kotest.matchers.string.shouldContain` for the last assertion.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.MigrationAnalyzerTest"`
Expected: FAIL — `MigrationAnalyzer`/`AnalysisReport` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.monoconvert.analysis

import com.monoconvert.discovery.RepoInventory
import com.monoconvert.source.SourceRepo

/** The complete read-only analysis output for a migration run (spec Phase 2). */
data class AnalysisReport(
    val monorepoVersion: Semver,
    val items: List<ResolvedItem>,
    val conflicts: List<ResolvedItem>,
    val dynamicItems: List<ResolvedItem>,
    val unparsed: Map<String, List<String>>,
    val catalog: CatalogModel,
    val catalogToml: String,
)

/** Aggregates per-repo analyses into one cross-repo [AnalysisReport]. */
class MigrationAnalyzer {

    private val repoAnalyzer = RepoAnalyzer()

    fun analyze(inventories: List<Pair<SourceRepo, RepoInventory>>): AnalysisReport {
        val analyses = inventories.map { (repo, inv) -> repoAnalyzer.analyze(repo, inv) }

        val items = ConflictResolver.resolve(analyses.flatMap { it.observations })
        val conflicts = items.filter { it.hasConflict }
        val dynamicItems = items.filter { item ->
            item.observations.any { it.resolved is ResolvedVersion.Dynamic }
        }
        val monoVersion = MonorepoVersionCalculator.compute(analyses.flatMap { it.versionCandidates })
        val catalog = CatalogBuilder.build(items)
        val unparsed = analyses
            .filter { it.unparsedLines.isNotEmpty() }
            .associate { it.repo to it.unparsedLines }

        return AnalysisReport(
            monorepoVersion = monoVersion,
            items = items,
            conflicts = conflicts,
            dynamicItems = dynamicItems,
            unparsed = unparsed,
            catalog = catalog,
            catalogToml = CatalogRenderer.render(catalog),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.analysis.MigrationAnalyzerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/analysis/MigrationAnalyzer.kt src/test/kotlin/com/monoconvert/analysis/MigrationAnalyzerTest.kt
git commit -m "feat(analysis): add MigrationAnalyzer and AnalysisReport"
```

---

### Task 13: Wire analysis into MigrateCommand

**Files:**
- Modify: `src/main/kotlin/com/monoconvert/cli/MigrateCommand.kt`
- Test: `src/test/kotlin/com/monoconvert/cli/MigrateCommandTest.kt`

- [ ] **Step 1: Extend the failing test**

Replace the body of `MigrateCommandTest` with:

```kotlin
package com.monoconvert.cli

import com.monoconvert.TestFixtures
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class MigrateCommandTest {

    private fun run(): String = MigrateCommand().runMigration(
        manifestPath = TestFixtures.path("repos.yaml"),
        configPath = TestFixtures.path("mono-convert.config.yaml"),
    )

    @Test
    fun `runs preflight and gate against fixtures and reports the shared carId`() {
        val output = run()

        output shouldContain "carId: 200009890"
        output shouldContain "payments-service"
        output shouldContain "billing-service"
        output shouldContain "build files: 1"
    }

    @Test
    fun `reports the computed monorepo version and conflicts`() {
        val output = run()

        output shouldContain "monorepo version: 4.0.0"
        output shouldContain "com.fasterxml.jackson.core:jackson-databind -> 2.17.1"
        output shouldContain "[plugins]"
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.cli.MigrateCommandTest"`
Expected: FAIL — output lacks the monorepo version / conflict / `[plugins]` lines.

- [ ] **Step 3: Update the implementation**

Replace the body of `runMigration` in `MigrateCommand.kt` with (keep the class, options, `run()`, and `loader` unchanged; add the `MigrationAnalyzer` import):

```kotlin
    fun runMigration(manifestPath: Path, configPath: Path, dryRun: Boolean = false): String {
        val manifest = loader.loadManifest(manifestPath)
        val toolConfig = loader.loadToolConfig(configPath)

        // Phase 0: resolve sources (local mode against fixtures; clone untested).
        val repos = RepoResolver().resolve(manifest, toolConfig.git)

        // Phase 1: carId gate + discovery.
        val carId = CarIdGate().verify(repos)
        val scanner = RepoScanner()
        val inventories = repos.map { it to scanner.scan(it) }

        val sb = StringBuilder()
        sb.appendLine("monorepo: ${manifest.monorepo.name}")
        sb.appendLine("carId: $carId")
        sb.appendLine("repos: ${repos.size}${if (dryRun) " (dry-run)" else ""}")
        for ((repo, inv) in inventories) {
            sb.appendLine(
                "  - ${repo.name} -> ${repo.target} | " +
                    "build files: ${inv.buildFiles.size}, lambda.json: ${inv.lambdaJsonFiles.size}",
            )
        }

        // Phase 2: static analysis (dry-run terminal output).
        val report = MigrationAnalyzer().analyze(inventories)
        sb.appendLine("monorepo version: ${report.monorepoVersion}")
        sb.appendLine("conflicts: ${report.conflicts.size}")
        for (c in report.conflicts) {
            val detail = c.observations.joinToString(", ") { "${it.repo}=${it.versionExpr}" }
            sb.appendLine("  ! ${c.key} -> ${c.winner} (was: $detail)")
        }
        if (report.dynamicItems.isNotEmpty()) {
            sb.appendLine(
                "dynamic (flagged, not pinned): " + report.dynamicItems.joinToString(", ") { it.key },
            )
        }
        sb.appendLine("catalog preview:")
        report.catalogToml.trimEnd().lines().forEach { sb.appendLine("  $it") }

        return sb.toString().trimEnd()
    }
```

Add this import near the other `com.monoconvert` imports at the top of the file:

```kotlin
import com.monoconvert.analysis.MigrationAnalyzer
```

- [ ] **Step 4: Run the full suite to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test`
Expected: PASS — all classes green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/cli/MigrateCommand.kt src/test/kotlin/com/monoconvert/cli/MigrateCommandTest.kt
git commit -m "feat(cli): run Phase 2 analysis and report version, conflicts, catalog preview"
```

---

### Task 14: Update CLAUDE.md and README

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: Add the `analysis/` package to CLAUDE.md's Architecture list**

In `CLAUDE.md`, under "## Architecture", after the `discovery/` bullet, insert:

```markdown
- `analysis/` — Phase 2 (read-only). `RepoAnalyzer`/`MigrationAnalyzer` parse deps, plugins,
  buildscript classpath, and project/`lambda.json` versions (`BuildFileParser`,
  `GradlePropertiesParser`, `LambdaJsonReader`, `Semver`, `VersionResolver`), resolve conflicts
  (`ConflictResolver`, highest-wins), compute the monorepo version (`MonorepoVersionCalculator`),
  and build a catalog preview (`CatalogBuilder`/`CatalogRenderer`). No OpenRewrite, no Gradle execution.
```

Also update the status line near the top of `CLAUDE.md` from "Phases 0–1 are implemented" to:

```markdown
`lambda.json` version stripping). It is built in phases; **Phases 0–2 are implemented** (preflight, the
```

- [ ] **Step 2: Update the README status, pipeline markers, and roadmap**

In `README.md`:
1. Change the Status note from "implements **Phases 0–1**" to "implements **Phases 0–2**".
2. In the "What it does" list, remove the `*(Plan 2)*` marker from the **Analyze** bullet (step 4) so it reads as implemented.
3. In the Roadmap, change the Plan 2 line to mark it done:

```markdown
- **Plan 2 — Analysis** (done): dependency/plugin/buildscript-classpath + version parsing, conflict
  resolution (highest-wins), monorepo version math, and `libs.versions.toml` catalog preview.
```

- [ ] **Step 3: Run the full suite (docs-only, sanity check nothing else changed)**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs: record Phase 2 analysis package in CLAUDE.md and README"
```

---

## Self-Review (completed)

**Spec coverage (Phase 2, §3, §6):**
- Parse every dependency declaration incl. `plugins {}` + `buildscript classpath` → Tasks 5, 11 ✓
- `gradle.properties` `${prop}` resolution → Tasks 2, 3 ✓
- Parse versions (deps, plugins, classpath, project, lambda.json) → Tasks 8, 11 ✓
- Normalize `group:artifact ⇒ {version per repo}` + conflict detection (highest-wins) → Task 6 ✓
- Dynamic versions never auto-pinned, flagged → Tasks 3, 9, 12 ✓
- Compute monorepo version (major bump, suffix-stripped core) → Tasks 1, 7 ✓
- Catalog preview (`libs.versions.toml`) with alias de-collision → Tasks 9, 10 ✓
- Dry-run terminal output (Phase 2 boundary) → Task 13 ✓
- **Deferred (Plan 3, explicitly out of scope here):** dep-graph baseline capture (spec puts it in
  Phase 2 but it needs Gradle execution → moved to Plan 4 per brainstorming), TOML file write,
  `settings.gradle` include() generation, buildscript relocation, OpenRewrite rewrites.

**Type consistency:** `Semver`, `ResolvedVersion(.Fixed/.Dynamic/.Unresolved)`, `VersionObservation`,
`ResolvedItem`, `ItemKind`, `RawDependency`, `RawPlugin`, `BuildFileContents`, `CatalogLibrary`,
`CatalogPlugin`, `CatalogModel`, `RepoAnalysis`, `AnalysisReport` are defined once and referenced
consistently across tasks. `MigrationAnalyzer.analyze` takes `List<Pair<SourceRepo, RepoInventory>>`,
matching the `inventories` value built in Task 13.

**Placeholder scan:** none — every code step contains complete code and exact commands.
