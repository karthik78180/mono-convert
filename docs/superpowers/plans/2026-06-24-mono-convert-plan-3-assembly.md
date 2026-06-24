# mono-convert Plan 3 (Assembly) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Phase 3 (Assembly): materialize a template, copy each source repo into a subproject directory, and write the root `libs.versions.toml`, `settings.gradle`, `gradle.properties` version, and consolidated `meta/source.yaml` — additive filesystem only, no source-file mutation.

**Architecture:** A new `com.monoconvert.assembly` package of small single-responsibility writers (6 independent leaf units) plus a sequential `AssemblyPhase` orchestrator and CLI/config wiring. Everything consumes Plan 2 outputs (`AnalysisReport`, `CatalogModel`, `monorepoVersion`), `RepoInventory`, `SourceRepo`, and the gate `carId`. Copy-never-move; idempotent overwrite.

**Tech Stack:** Kotlin/JVM, Gradle 8.8 (JDK 21 toolchain), Clikt 4.4, Jackson (shared `Yaml.mapper`), JUnit5 + Kotest, kotlin.io stdlib filesystem (`copyRecursively`).

---

## Build & test command (IMPORTANT)

This machine's default JDK is too new for the Gradle 8.8 wrapper. **Always** pin Java 21:

```bash
JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test
```

`fixtures/` is not a tracked Gradle test input, so when a test reads fixtures and you suspect a stale `:test UP-TO-DATE`, append `--rerun-tasks`. Run a single test class with `--tests "com.monoconvert.assembly.TemplateMaterializerTest"`.

## Execution strategy (waves)

- **Wave 1 (Tasks 1–6)** are independent leaf units — each creates one new file under `src/main/kotlin/com/monoconvert/assembly/` plus one new test file, and references nothing from the other five. They are **parallel-safe** (build each in its own git worktree, then merge to `feat/plan-3-assembly`).
- **Wave 2 (Tasks 7–8)** are sequential: the `AssemblyPhase` orchestrator and the CLI/config wiring depend on all six leaves and edit shared files (`ToolConfig.kt`, `MigrateCommand.kt`).

## File Structure

| File | Responsibility |
|---|---|
| `assembly/TemplateMaterializer.kt` | Copy local template tree → `<out>/<name>/` |
| `assembly/RepoCopier.kt` | Copy one `SourceRepo.root` → `<monorepo>/<target>/` |
| `assembly/CatalogFileWriter.kt` | Render `CatalogModel` → `<monorepo>/gradle/libs.versions.toml` |
| `assembly/SettingsGenerator.kt` | Derive module include-paths; write root `settings.gradle` |
| `assembly/RootPropertiesWriter.kt` | Set `version=` in `<monorepo>/gradle.properties` |
| `assembly/MetaConsolidator.kt` | Write root `meta/source.yaml` (carId only); delete per-module `meta/` |
| `assembly/AssemblyPhase.kt` | Orchestrator → `AssemblyResult` |
| `config/ToolConfig.kt` (modify) | Add `TemplateConfig.path` |
| `cli/MigrateCommand.kt` (modify) | Add `--out`; non-dry-run assembly branch |

---

## Task 1: TemplateMaterializer

**Files:**
- Create: `src/main/kotlin/com/monoconvert/assembly/TemplateMaterializer.kt`
- Test: `src/test/kotlin/com/monoconvert/assembly/TemplateMaterializerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.assembly

import com.monoconvert.MigrationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class TemplateMaterializerTest {

    @Test
    fun `copies template tree into out under monorepo name`(@TempDir tmp: Path) {
        val template = tmp.resolve("template").also { it.resolve("gradle").createDirectories() }
        template.resolve("settings.gradle").writeText("rootProject.name = 'monorepo-template'\n")
        template.resolve("gradle/libs.versions.toml").writeText("[versions]\n")
        val out = tmp.resolve("out")

        val dest = TemplateMaterializer.materialize(template, out, "vehicle-platform")

        dest shouldBe out.resolve("vehicle-platform")
        dest.resolve("settings.gradle").shouldExist()
        dest.resolve("gradle/libs.versions.toml").readText() shouldBe "[versions]\n"
    }

    @Test
    fun `overwrites existing destination idempotently`(@TempDir tmp: Path) {
        val template = tmp.resolve("template").also { it.createDirectories() }
        template.resolve("gradle.properties").writeText("version=0.0.0\n")
        val out = tmp.resolve("out")

        TemplateMaterializer.materialize(template, out, "m")
        val dest = TemplateMaterializer.materialize(template, out, "m") // second run

        dest.resolve("gradle.properties").readText() shouldBe "version=0.0.0\n"
    }

    @Test
    fun `throws MigrationException when template dir is missing`(@TempDir tmp: Path) {
        val ex = shouldThrow<MigrationException> {
            TemplateMaterializer.materialize(tmp.resolve("nope"), tmp.resolve("out"), "m")
        }
        ex.message!! shouldContain "template"
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.TemplateMaterializerTest"`
Expected: FAIL — `TemplateMaterializer` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.monoconvert.assembly

import com.monoconvert.MigrationException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory

/** Phase 3: copies a local template tree into `<outDir>/<monorepoName>` (idempotent). */
object TemplateMaterializer {

    fun materialize(templateDir: Path, outDir: Path, monorepoName: String): Path {
        if (!templateDir.isDirectory()) {
            throw MigrationException("template path '$templateDir' is not a readable directory")
        }
        val dest = outDir.resolve(monorepoName)
        outDir.createDirectories()
        templateDir.toFile().copyRecursively(dest.toFile(), overwrite = true)
        return dest
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.TemplateMaterializerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/assembly/TemplateMaterializer.kt src/test/kotlin/com/monoconvert/assembly/TemplateMaterializerTest.kt
git commit -m "$(printf 'feat(assembly): add TemplateMaterializer\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 2: RepoCopier

**Files:**
- Create: `src/main/kotlin/com/monoconvert/assembly/RepoCopier.kt`
- Test: `src/test/kotlin/com/monoconvert/assembly/RepoCopierTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.assembly

import com.monoconvert.source.SourceRepo
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RepoCopierTest {

    @Test
    fun `copies repo root into monorepo under target name`(@TempDir tmp: Path) {
        val repoRoot = tmp.resolve("src-payments").also { it.resolve("config/charge").createDirectories() }
        repoRoot.resolve("build.gradle").writeText("plugins { id 'java' }\n")
        repoRoot.resolve("config/charge/lambda.json").writeText("{}\n")
        val repo = SourceRepo(name = "payments-service", target = "payments-service", root = repoRoot)
        val monorepo = tmp.resolve("out/vehicle-platform").also { it.createDirectories() }

        val dest = RepoCopier.copy(repo, monorepo)

        dest shouldBe monorepo.resolve("payments-service")
        dest.resolve("build.gradle").readText() shouldBe "plugins { id 'java' }\n"
        dest.resolve("config/charge/lambda.json").shouldExist()
    }

    @Test
    fun `does not mutate the source repo`(@TempDir tmp: Path) {
        val repoRoot = tmp.resolve("src").also { it.createDirectories() }
        repoRoot.resolve("build.gradle").writeText("// original\n")
        val repo = SourceRepo(name = "r", target = "r", root = repoRoot)
        val monorepo = tmp.resolve("out").also { it.createDirectories() }

        RepoCopier.copy(repo, monorepo)

        repoRoot.resolve("build.gradle").readText() shouldBe "// original\n"
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.RepoCopierTest"`
Expected: FAIL — `RepoCopier` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.monoconvert.assembly

import com.monoconvert.source.SourceRepo
import java.nio.file.Path

/** Phase 3: copies a source repo's tree into `<monorepoDir>/<target>/` verbatim (idempotent). */
object RepoCopier {

    fun copy(repo: SourceRepo, monorepoDir: Path): Path {
        val dest = monorepoDir.resolve(repo.target)
        repo.root.toFile().copyRecursively(dest.toFile(), overwrite = true)
        return dest
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.RepoCopierTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/assembly/RepoCopier.kt src/test/kotlin/com/monoconvert/assembly/RepoCopierTest.kt
git commit -m "$(printf 'feat(assembly): add RepoCopier\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 3: CatalogFileWriter

**Files:**
- Create: `src/main/kotlin/com/monoconvert/assembly/CatalogFileWriter.kt`
- Test: `src/test/kotlin/com/monoconvert/assembly/CatalogFileWriterTest.kt`

Reuses the existing `com.monoconvert.analysis.CatalogRenderer.render(CatalogModel): String` and `CatalogModel`/`CatalogLibrary`/`CatalogPlugin` from `analysis/Catalog.kt`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.assembly

import com.monoconvert.analysis.CatalogLibrary
import com.monoconvert.analysis.CatalogModel
import com.monoconvert.analysis.CatalogPlugin
import com.monoconvert.analysis.CatalogRenderer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

class CatalogFileWriterTest {

    @Test
    fun `writes rendered catalog to gradle libs versions toml`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("vehicle-platform").also { it.createDirectories() }
        val model = CatalogModel(
            libraries = listOf(
                CatalogLibrary("jackson-databind", "com.fasterxml.jackson.core:jackson-databind", "2.17.1"),
            ),
            plugins = listOf(CatalogPlugin("boot", "org.springframework.boot", "3.3.0")),
        )

        val dest = CatalogFileWriter.write(model, monorepo)

        dest shouldBe monorepo.resolve("gradle/libs.versions.toml")
        val text = dest.readText()
        text shouldBe CatalogRenderer.render(model)
        text shouldContain
            """jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version = "2.17.1" }"""
    }

    @Test
    fun `creates the gradle directory when absent`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("fresh").also { it.createDirectories() }
        val dest = CatalogFileWriter.write(CatalogModel(emptyList(), emptyList()), monorepo)
        dest.readText() shouldContain "[libraries]"
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.CatalogFileWriterTest"`
Expected: FAIL — `CatalogFileWriter` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.monoconvert.assembly

import com.monoconvert.analysis.CatalogModel
import com.monoconvert.analysis.CatalogRenderer
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Phase 3: writes the rendered version catalog to `<monorepoDir>/gradle/libs.versions.toml`. */
object CatalogFileWriter {

    fun write(catalog: CatalogModel, monorepoDir: Path): Path {
        val dest = monorepoDir.resolve("gradle").resolve("libs.versions.toml")
        dest.parent.createDirectories()
        dest.writeText(CatalogRenderer.render(catalog))
        return dest
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.CatalogFileWriterTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/assembly/CatalogFileWriter.kt src/test/kotlin/com/monoconvert/assembly/CatalogFileWriterTest.kt
git commit -m "$(printf 'feat(assembly): add CatalogFileWriter\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 4: SettingsGenerator

**Files:**
- Create: `src/main/kotlin/com/monoconvert/assembly/SettingsGenerator.kt`
- Test: `src/test/kotlin/com/monoconvert/assembly/SettingsGeneratorTest.kt`

Derives Gradle include-paths from each repo's `RepoInventory.buildFiles` (a single root build file → `:<target>`; a nested module at `sub/build.gradle` → `:<target>:sub`). Writes Groovy DSL root `settings.gradle` (the template is Groovy).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.assembly

import com.monoconvert.discovery.BuildFile
import com.monoconvert.discovery.Dsl
import com.monoconvert.discovery.RepoInventory
import com.monoconvert.source.SourceRepo
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

class SettingsGeneratorTest {

    private fun inv(repoRoot: Path, vararg buildFiles: Path) = RepoInventory(
        repoName = repoRoot.fileName.toString(),
        buildFiles = buildFiles.map { BuildFile(it, Dsl.GROOVY) },
        settingsFiles = emptyList(),
        gradlePropertiesFiles = emptyList(),
        lambdaJsonFiles = emptyList(),
    )

    @Test
    fun `root build file yields colon target include path`(@TempDir tmp: Path) {
        val root = tmp.resolve("payments-service").also { it.createDirectories() }
        val repo = SourceRepo("payments-service", "payments-service", root)

        val paths = SettingsGenerator.modulePaths(repo, inv(root, root.resolve("build.gradle")))

        paths shouldContainExactly listOf(":payments-service")
    }

    @Test
    fun `nested module yields colon-separated path`(@TempDir tmp: Path) {
        val root = tmp.resolve("billing").also { it.resolve("core").createDirectories() }
        val repo = SourceRepo("billing", "billing", root)

        val paths = SettingsGenerator.modulePaths(
            repo,
            inv(root, root.resolve("build.gradle"), root.resolve("core/build.gradle")),
        )

        paths shouldContainExactly listOf(":billing", ":billing:core")
    }

    @Test
    fun `render emits rootProject name and include lines`() {
        val body = SettingsGenerator.render("vehicle-platform", listOf(":payments-service", ":billing-service"))

        body shouldContain "rootProject.name = 'vehicle-platform'"
        body shouldContain "include ':payments-service'"
        body shouldContain "include ':billing-service'"
    }

    @Test
    fun `write produces settings gradle at monorepo root`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("vehicle-platform").also { it.createDirectories() }

        val dest = SettingsGenerator.write(monorepo, "vehicle-platform", listOf(":payments-service"))

        dest shouldBe monorepo.resolve("settings.gradle")
        dest.readText() shouldContain "include ':payments-service'"
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.SettingsGeneratorTest"`
Expected: FAIL — `SettingsGenerator` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.SettingsGeneratorTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/assembly/SettingsGenerator.kt src/test/kotlin/com/monoconvert/assembly/SettingsGeneratorTest.kt
git commit -m "$(printf 'feat(assembly): add SettingsGenerator\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 5: RootPropertiesWriter

**Files:**
- Create: `src/main/kotlin/com/monoconvert/assembly/RootPropertiesWriter.kt`
- Test: `src/test/kotlin/com/monoconvert/assembly/RootPropertiesWriterTest.kt`

Replaces the `version=` line in the monorepo's root `gradle.properties`, preserving all other lines. The template's `gradle.properties` already exists (`version=0.0.0`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.assembly

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RootPropertiesWriterTest {

    @Test
    fun `replaces existing version line`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("m").also { it.createDirectories() }
        monorepo.resolve("gradle.properties").writeText("version=0.0.0\n")

        val dest = RootPropertiesWriter.write(monorepo, "4.0.0")

        dest shouldBe monorepo.resolve("gradle.properties")
        dest.readText() shouldBe "version=4.0.0\n"
    }

    @Test
    fun `preserves other properties and order`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("m").also { it.createDirectories() }
        monorepo.resolve("gradle.properties")
            .writeText("org.gradle.jvmargs=-Xmx2g\nversion=1.2.3\nkotlin.code.style=official\n")

        RootPropertiesWriter.write(monorepo, "4.0.0")

        monorepo.resolve("gradle.properties").readText() shouldBe
            "org.gradle.jvmargs=-Xmx2g\nversion=4.0.0\nkotlin.code.style=official\n"
    }

    @Test
    fun `appends version when file has none`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("m").also { it.createDirectories() }
        monorepo.resolve("gradle.properties").writeText("org.gradle.caching=true\n")

        RootPropertiesWriter.write(monorepo, "4.0.0")

        monorepo.resolve("gradle.properties").readText() shouldBe
            "org.gradle.caching=true\nversion=4.0.0\n"
    }

    @Test
    fun `creates file when absent`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("m").also { it.createDirectories() }

        RootPropertiesWriter.write(monorepo, "4.0.0")

        monorepo.resolve("gradle.properties").readText() shouldBe "version=4.0.0\n"
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.RootPropertiesWriterTest"`
Expected: FAIL — `RootPropertiesWriter` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.monoconvert.assembly

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Phase 3: sets the root-owned monorepo `version=` in `<monorepoDir>/gradle.properties`. */
object RootPropertiesWriter {

    fun write(monorepoDir: Path, version: String): Path {
        val dest = monorepoDir.resolve("gradle.properties")
        val existing = if (dest.exists()) dest.readText() else ""
        val lines = if (existing.isEmpty()) {
            mutableListOf()
        } else {
            existing.removeSuffix("\n").split("\n").toMutableList()
        }
        val idx = lines.indexOfFirst { it.trimStart().startsWith("version=") }
        if (idx >= 0) lines[idx] = "version=$version" else lines.add("version=$version")
        dest.writeText(lines.joinToString("\n") + "\n")
        return dest
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.RootPropertiesWriterTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/assembly/RootPropertiesWriter.kt src/test/kotlin/com/monoconvert/assembly/RootPropertiesWriterTest.kt
git commit -m "$(printf 'feat(assembly): add RootPropertiesWriter\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 6: MetaConsolidator

**Files:**
- Create: `src/main/kotlin/com/monoconvert/assembly/MetaConsolidator.kt`
- Test: `src/test/kotlin/com/monoconvert/assembly/MetaConsolidatorTest.kt`

Writes a single root `meta/source.yaml` containing only `carId`, then deletes each copied module's `meta/` directory.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monoconvert.assembly

import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class MetaConsolidatorTest {

    @Test
    fun `writes root carId and removes per-module meta`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("vehicle-platform").also { it.createDirectories() }
        val paymentsMeta = monorepo.resolve("payments-service/meta").also { it.createDirectories() }
        paymentsMeta.resolve("source.yaml").writeText("carId: 200009890\notherKey: payments\n")
        val billingMeta = monorepo.resolve("billing-service/meta").also { it.createDirectories() }
        billingMeta.resolve("source.yaml").writeText("carId: 200009890\n")

        val dest = MetaConsolidator.consolidate(
            monorepo, "200009890", listOf("payments-service", "billing-service"),
        )

        dest shouldBe monorepo.resolve("meta/source.yaml")
        dest.readText() shouldBe "carId: 200009890\n"
        monorepo.resolve("payments-service/meta").shouldNotExist()
        monorepo.resolve("billing-service/meta").shouldNotExist()
        monorepo.resolve("payments-service").shouldExist() // module dir itself stays
    }

    @Test
    fun `tolerates a module without a meta directory`(@TempDir tmp: Path) {
        val monorepo = tmp.resolve("m").also { it.resolve("svc").createDirectories() }

        val dest = MetaConsolidator.consolidate(monorepo, "42", listOf("svc"))

        dest.readText() shouldBe "carId: 42\n"
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.MetaConsolidatorTest"`
Expected: FAIL — `MetaConsolidator` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.monoconvert.assembly

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** Phase 3: writes the single root `meta/source.yaml` (carId only) and drops per-module meta dirs. */
object MetaConsolidator {

    fun consolidate(monorepoDir: Path, carId: String, targets: List<String>): Path {
        val metaDir = monorepoDir.resolve("meta")
        metaDir.createDirectories()
        val dest = metaDir.resolve("source.yaml")
        dest.writeText("carId: $carId\n")
        for (t in targets) {
            val moduleMeta = monorepoDir.resolve(t).resolve("meta")
            if (moduleMeta.exists()) moduleMeta.toFile().deleteRecursively()
        }
        return dest
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.MetaConsolidatorTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/monoconvert/assembly/MetaConsolidator.kt src/test/kotlin/com/monoconvert/assembly/MetaConsolidatorTest.kt
git commit -m "$(printf 'feat(assembly): add MetaConsolidator\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 7: TemplateConfig.path + AssemblyPhase orchestrator (Wave 2)

**Files:**
- Modify: `src/main/kotlin/com/monoconvert/config/ToolConfig.kt`
- Create: `src/main/kotlin/com/monoconvert/assembly/AssemblyPhase.kt`
- Test: `src/test/kotlin/com/monoconvert/assembly/AssemblyPhaseTest.kt`

Depends on Tasks 1–6 being merged. First add the optional local-template field, then the orchestrator.

- [ ] **Step 1: Add `path` to TemplateConfig**

Modify `src/main/kotlin/com/monoconvert/config/ToolConfig.kt` — change the `TemplateConfig` data class to:

```kotlin
data class TemplateConfig(
    val repo: String,
    val path: String? = null,
)
```

(Leave `GitConfig` and `ToolConfig` unchanged.)

- [ ] **Step 2: Write the failing test**

```kotlin
package com.monoconvert.assembly

import com.monoconvert.MigrationException
import com.monoconvert.analysis.MigrationAnalyzer
import com.monoconvert.config.ConfigLoader
import com.monoconvert.config.GitConfig
import com.monoconvert.config.TemplateConfig
import com.monoconvert.config.ToolConfig
import com.monoconvert.discovery.RepoScanner
import com.monoconvert.gate.CarIdGate
import com.monoconvert.source.RepoResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

class AssemblyPhaseTest {

    private val loader = ConfigLoader()

    private fun assemble(out: Path, template: String = "fixtures/template"): AssemblyResult {
        val manifest = loader.loadManifest(Path.of("fixtures/repos.yaml"))
        val toolConfig = ToolConfig(
            git = GitConfig(baseUrl = "https://example.invalid", defaultBranch = "main"),
            template = TemplateConfig(repo = "monorepo-template", path = template),
        )
        val repos = RepoResolver().resolve(manifest, toolConfig.git)
        val carId = CarIdGate().verify(repos)
        val scanner = RepoScanner()
        val inventories = repos.map { it to scanner.scan(it) }
        val report = MigrationAnalyzer().analyze(inventories)
        return AssemblyPhase().assemble(manifest, toolConfig, out, carId, report, inventories)
    }

    @Test
    fun `assembles the full monorepo tree from fixtures`(@TempDir tmp: Path) {
        val result = assemble(tmp.resolve("out"))

        val mono = tmp.resolve("out/vehicle-platform")
        result.monorepoDir shouldBe mono
        result.modulePaths shouldContainExactlyInAnyOrder listOf(":payments-service", ":billing-service")

        mono.resolve("gradle.properties").readText() shouldBe "version=4.0.0\n"
        mono.resolve("meta/source.yaml").readText() shouldBe "carId: 200009890\n"

        val settings = mono.resolve("settings.gradle").readText()
        settings shouldContain "rootProject.name = 'vehicle-platform'"
        settings shouldContain "include ':payments-service'"
        settings shouldContain "include ':billing-service'"

        val catalog = mono.resolve("gradle/libs.versions.toml").readText()
        catalog shouldContain
            """jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version = "2.17.1" }"""

        // repos copied in, per-module meta removed, config preserved
        mono.resolve("payments-service/build.gradle").shouldExist()
        mono.resolve("payments-service/config/charge/lambda.json").shouldExist()
        mono.resolve("payments-service/meta").shouldNotExist()
        mono.resolve("billing-service/build.gradle.kts").shouldExist()
        mono.resolve("billing-service/meta").shouldNotExist()
    }

    @Test
    fun `is idempotent on a second run`(@TempDir tmp: Path) {
        assemble(tmp.resolve("out"))
        val second = assemble(tmp.resolve("out"))
        second.monorepoDir.resolve("gradle.properties").readText() shouldBe "version=4.0.0\n"
    }

    @Test
    fun `throws when template path is not configured`(@TempDir tmp: Path) {
        val ex = shouldThrow<MigrationException> { assemble(tmp.resolve("out"), template = "") }
        ex.message!! shouldContain "template"
    }
}
```

Note: pass `template = ""` triggers the blank-path branch (treated as missing). The implementation treats null **or blank** `template.path` as unconfigured.

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.AssemblyPhaseTest" --rerun-tasks`
Expected: FAIL — `AssemblyPhase` / `AssemblyResult` unresolved reference.

- [ ] **Step 4: Write minimal implementation**

```kotlin
package com.monoconvert.assembly

import com.monoconvert.MigrationException
import com.monoconvert.analysis.AnalysisReport
import com.monoconvert.config.Manifest
import com.monoconvert.config.ToolConfig
import com.monoconvert.discovery.RepoInventory
import com.monoconvert.source.SourceRepo
import java.nio.file.Path

/** Result of Phase 3 assembly. */
data class AssemblyResult(
    val monorepoDir: Path,
    val modulePaths: List<String>,
)

/**
 * Phase 3 orchestrator: materializes the template, copies repos in, and writes the
 * catalog, settings, root version, and consolidated meta. Additive filesystem only.
 */
class AssemblyPhase {

    fun assemble(
        manifest: Manifest,
        toolConfig: ToolConfig,
        outDir: Path,
        carId: String,
        report: AnalysisReport,
        inventories: List<Pair<SourceRepo, RepoInventory>>,
    ): AssemblyResult {
        val templatePath = toolConfig.template.path
        if (templatePath.isNullOrBlank()) {
            throw MigrationException("template.path is required for assembly (set template.path in the config)")
        }

        val name = manifest.monorepo.name
        val monorepoDir = TemplateMaterializer.materialize(Path.of(templatePath), outDir, name)

        for ((repo, _) in inventories) {
            RepoCopier.copy(repo, monorepoDir)
        }

        CatalogFileWriter.write(report.catalog, monorepoDir)

        val modulePaths = inventories.flatMap { (repo, inv) -> SettingsGenerator.modulePaths(repo, inv) }
        SettingsGenerator.write(monorepoDir, name, modulePaths)

        RootPropertiesWriter.write(monorepoDir, report.monorepoVersion.toString())

        MetaConsolidator.consolidate(monorepoDir, carId, inventories.map { it.first.target })

        return AssemblyResult(monorepoDir, modulePaths)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.assembly.AssemblyPhaseTest" --rerun-tasks`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/monoconvert/config/ToolConfig.kt src/main/kotlin/com/monoconvert/assembly/AssemblyPhase.kt src/test/kotlin/com/monoconvert/assembly/AssemblyPhaseTest.kt
git commit -m "$(printf 'feat(assembly): add AssemblyPhase orchestrator and local template.path\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 8: CLI wiring (`--out` + non-dry-run assembly) (Wave 2)

**Files:**
- Modify: `src/main/kotlin/com/monoconvert/cli/MigrateCommand.kt`
- Modify: `src/test/kotlin/com/monoconvert/cli/MigrateCommandTest.kt`
- Modify: `fixtures/mono-convert.config.yaml`

Adds the `--out` option, threads it into `runMigration`, and runs `AssemblyPhase` on a real (non-dry-run) invocation, appending an assembly summary. Dry-run behavior is unchanged.

- [ ] **Step 1: Add `template.path` to the fixture config**

Modify `fixtures/mono-convert.config.yaml` so the `template` block reads:

```yaml
template:
  repo: "monorepo-template"
  path: "fixtures/template"
```

(Leave the `git:` block unchanged.)

- [ ] **Step 2: Write the failing tests**

Add these two tests to `src/test/kotlin/com/monoconvert/cli/MigrateCommandTest.kt` (keep existing imports; add any missing ones shown here):

```kotlin
    @org.junit.jupiter.api.Test
    fun `non dry-run assembles the monorepo into out`(@org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path) {
        val out = tmp.resolve("out")
        val output = MigrateCommand().runMigration(
            manifestPath = java.nio.file.Path.of("fixtures/repos.yaml"),
            configPath = java.nio.file.Path.of("fixtures/mono-convert.config.yaml"),
            dryRun = false,
            outDir = out,
        )

        output shouldContain "assembled: "
        output shouldContain "modules: :payments-service, :billing-service"
        out.resolve("vehicle-platform/settings.gradle").exists() shouldBe true
        out.resolve("vehicle-platform/gradle.properties").readText() shouldBe "version=4.0.0\n"
    }

    @org.junit.jupiter.api.Test
    fun `non dry-run without out fails closed`() {
        val ex = io.kotest.assertions.throwables.shouldThrow<com.monoconvert.MigrationException> {
            MigrateCommand().runMigration(
                manifestPath = java.nio.file.Path.of("fixtures/repos.yaml"),
                configPath = java.nio.file.Path.of("fixtures/mono-convert.config.yaml"),
                dryRun = false,
                outDir = null,
            )
        }
        ex.message!! shouldContain "--out"
    }
```

If the test file lacks them, also add these top-level imports:

```kotlin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.exists
import kotlin.io.path.readText
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.cli.MigrateCommandTest" --rerun-tasks`
Expected: FAIL — `runMigration` has no `outDir` parameter.

- [ ] **Step 4: Wire the CLI**

In `src/main/kotlin/com/monoconvert/cli/MigrateCommand.kt`:

(a) Add the import alongside the existing ones:

```kotlin
import com.monoconvert.assembly.AssemblyPhase
import com.monoconvert.MigrationException
```

(b) Add the `--out` option after the `dryRun` option declaration:

```kotlin
    private val outOpt: Path? by option("--out", help = "Output dir for the assembled monorepo")
        .path()
```

(c) Change `run()` to pass it:

```kotlin
    override fun run() {
        echo(runMigration(manifestOpt, configOpt, dryRun, outOpt))
    }
```

(d) Change the `runMigration` signature to accept `outDir`:

```kotlin
    fun runMigration(
        manifestPath: Path,
        configPath: Path,
        dryRun: Boolean = false,
        outDir: Path? = null,
    ): String {
```

(e) Immediately before the final `return sb.toString().trimEnd()`, insert the assembly branch:

```kotlin
        if (!dryRun) {
            val out = outDir
                ?: throw MigrationException("--out <dir> is required unless --dry-run")
            val result = AssemblyPhase().assemble(manifest, toolConfig, out, carId, report, inventories)
            sb.appendLine("assembled: ${result.monorepoDir}")
            sb.appendLine("modules: ${result.modulePaths.joinToString(", ")}")
        }
```

(`manifest`, `toolConfig`, `carId`, `report`, and `inventories` are all already in scope from the existing body.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --tests "com.monoconvert.cli.MigrateCommandTest" --rerun-tasks`
Expected: PASS (existing tests + the 2 new ones).

- [ ] **Step 6: Run the full suite**

Run: `JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test --rerun-tasks`
Expected: `BUILD SUCCESSFUL`, all classes green.

- [ ] **Step 7: Manual end-to-end check (real assembly)**

Run:
```bash
JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew run --args="--manifest fixtures/repos.yaml --config fixtures/mono-convert.config.yaml --out fixtures/output"
```
Expected: the Phase 0–2 summary followed by:
```
assembled: fixtures/output/vehicle-platform
modules: :payments-service, :billing-service
```
Then verify and clean up the sandbox:
```bash
ls fixtures/output/vehicle-platform
rm -rf fixtures/output/vehicle-platform
```
Expected listing includes `settings.gradle`, `gradle.properties`, `gradle/`, `meta/`, `payments-service/`, `billing-service/`.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/monoconvert/cli/MigrateCommand.kt src/test/kotlin/com/monoconvert/cli/MigrateCommandTest.kt fixtures/mono-convert.config.yaml
git commit -m "$(printf 'feat(cli): assemble monorepo on non-dry-run via --out\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 9: Docs (CLAUDE.md + README)

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: Update CLAUDE.md**

- Change the status line to "**Phases 0–3 are implemented**".
- Add an `assembly/` bullet to the Architecture list, after the `analysis/` bullet:

```markdown
- `assembly/` — Phase 3 (filesystem, additive). `AssemblyPhase` materializes the template
  (`TemplateMaterializer`), copies each repo in (`RepoCopier`), and writes the root catalog
  (`CatalogFileWriter`), `settings.gradle` includes (`SettingsGenerator`), root version
  (`RootPropertiesWriter`), and consolidated `meta/source.yaml` (`MetaConsolidator`). No
  OpenRewrite and no source-file mutation — those are Plan 4.
```

- In the Workflow section, change "Plans 1–2 are done; Plans 3–4 are pending." to
  "Plans 1–3 are done; Plans 4–5 are pending." and update the closing note to mention the
  Phase 3 outputs (`AssemblyResult`, the assembled monorepo tree).
- Update the example `run` command to include `--out fixtures/output` for a real assembly.

- [ ] **Step 2: Update README.md**

- Change the status note to "**Phases 0–3**".
- In "What it does", drop the *(Plan 3)* marker from the Assemble bullet.
- Add an `--out` example under "Running the CLI":

```markdown
A real (non-dry-run) invocation assembles the monorepo into `--out`:

\`\`\`bash
./gradlew run --args="--manifest fixtures/repos.yaml --config fixtures/mono-convert.config.yaml --out fixtures/output"
\`\`\`

It appends to the Phase 0–2 summary:

\`\`\`
assembled: fixtures/output/vehicle-platform
modules: :payments-service, :billing-service
\`\`\`
```

- Document the new local `template.path` in the Tool config block:

```yaml
template:
  repo: "monorepo-template"   # clone mode
  path: "fixtures/template"   # local mode — copied as-is
```

- In Roadmap, mark "Plan 3 — Assembly" as **(done)** and split out "Plan 4 — Rewrites
  (OpenRewrite): coords → catalog aliases, plugin aliases, version-prop stripping, buildscript
  relocation, lambda.json stripping" and "Plan 5 — Validation + Rollback".
- Add `assembly/` to the project-structure tree.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "$(printf 'docs: record Phase 3 assembly in CLAUDE.md and README\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Done criteria

- All nine tasks committed on `feat/plan-3-assembly`.
- Full suite green under Java 21 (`./gradlew test --rerun-tasks`).
- `--out` assembly produces `vehicle-platform/` with `settings.gradle` (2 includes), `gradle.properties` (`version=4.0.0`), `gradle/libs.versions.toml` (rendered catalog), root `meta/source.yaml` (carId only), and both module dirs with per-module `meta/` removed.
- No OpenRewrite, no source-file mutation, no network/clone in tests.
```
