# Plan 3 — Assembly (Design)

**Date:** 2026-06-24
**Status:** Approved design, pre-implementation
**Owner:** karthikbasics@gmail.com
**Parent spec:** [`2026-06-24-gradle-monorepo-migration-design.md`](2026-06-24-gradle-monorepo-migration-design.md) (Phase 3)

## 1. Summary

Plan 3 implements **Phase 3 (Assembly)** of the migration pipeline: it takes the
read-only `AnalysisReport` produced by Plan 2 and materializes a real monorepo on
disk. It clones-by-copy a local template, copies each source repo into a subproject
directory, writes the unified `gradle/libs.versions.toml`, generates the root
`settings` `include(...)` graph, writes the root-owned monorepo version, and
consolidates `meta/source.yaml` to a single root file.

Plan 3 is **additive filesystem work only**. It copies trees and writes new
root-level files. It never mutates an existing source build file or `lambda.json`.

## 2. Scope Boundary (Plan 3 vs Plan 4)

| Concern | Plan | Rationale |
|---|---|---|
| Materialize template, move repos into subdirs | **3** | Pure filesystem copy. |
| Write `gradle/libs.versions.toml` | **3** | New file; reuses `CatalogRenderer`. |
| Generate root `settings` `include(...)` | **3** | New/overwritten root file. |
| Write root `gradle.properties` version | **3** | New/overwritten root file. |
| Consolidate `meta/source.yaml` to root | **3** | New root file + per-module dir deletion. |
| build-file coords → `libs.*` aliases | 4 | In-file mutation → OpenRewrite. |
| `plugins {}` → `alias(libs.plugins.x)` | 4 | In-file mutation → OpenRewrite. |
| Strip now-centralized version props | 4 | In-file mutation → OpenRewrite. |
| **buildscript / pluginManagement relocation** | 4 | Mutates submodule build files → OpenRewrite. |
| `lambda.json` version/functionVersion strip | 4 | In-file mutation → OpenRewrite. |

This preserves the parent spec's rule: *the CLI never does raw string/JSON munging
of source files; every such mutation goes through OpenRewrite (Plan 4).* Writing
brand-new root files (catalog, settings, properties, root `meta/source.yaml`) is
not a source-file mutation and stays in Plan 3.

> **Parent-spec amendment:** the buildscript/pluginManagement relocation listed
> under Phase 3 in the parent spec moves to **Phase 4**, because it mutates existing
> submodule build files and therefore belongs with the OpenRewrite rewrites.

## 3. New Contracts

### 3.1 Tool config — local template path
`ToolConfig.template` gains an optional `path` (local template dir) alongside the
existing `repo` (clone mode). Mirrors the source `local`/`clone` split.

```yaml
template:
  repo: "monorepo-template"        # clone mode (existing)
  path: "fixtures/template"        # local mode (new) — copied as-is
```

In local mode `template.path` is required; missing → `MigrationException`.

### 3.2 CLI — output directory
`migrate` gains `--out <dir>`. The monorepo is assembled at `<out>/<monorepo.name>`.

- **Dry-run is unchanged**: Phases 0–2 only, zero writes. Assembly runs only on a
  real (non-dry-run) invocation; `--out` is required then.
- Copy, never move: source fixtures/clones are left untouched.
- Idempotent: re-running overwrites in place (`copyRecursively(overwrite = true)`).

## 4. Units

Each unit is one new file under `com.monoconvert.assembly` plus its test. Leaves are
independently testable (each test sets up its own temp tree; no dependency on the
materializer).

### Wave 1 — independent leaves (parallel-safe)
1. **`TemplateMaterializer`** — copy `template.path` tree → `<out>/<name>/`
   (idempotent). Throws if the template dir is missing/unreadable.
2. **`RepoCopier`** — copy each `SourceRepo.root` → `<out>/<name>/<target>/` verbatim.
3. **`CatalogFileWriter`** — render `CatalogModel` via existing
   `CatalogRenderer.render` → `<name>/gradle/libs.versions.toml` (overwrites the
   template's empty catalog).
4. **`SettingsGenerator`** — emit `rootProject.name = '<name>'` plus one
   `include('<modulePath>')` per discovered module. Module paths derive from each
   repo's `RepoInventory.buildFiles` relative to the repo root, so a single root
   build file → `:<target>` and a nested module → `:<target>:<sub>`. Writes the root
   `settings.gradle` (template DSL is Groovy).
5. **`RootPropertiesWriter`** — set/replace the `version=` line in
   `<name>/gradle.properties` to the computed `X.0.0`, preserving any other lines.
6. **`MetaConsolidator`** — write root `<name>/meta/source.yaml` containing only
   `carId` (from the gate); delete each copied subdir's `meta/` directory.

### Wave 2 — sequential integration
7. **`AssemblyPhase`** — orchestrator. Runs 1 → 2 → 3 → 4 → 5 → 6 and returns
   `AssemblyResult(monorepoDir: Path, modulePaths: List<String>)`.
8. **Wiring** — `TemplateConfig.path`, `ConfigLoader` validation, `--out` option on
   `MigrateCommand`, and a non-dry-run branch in `runMigration` that invokes
   `AssemblyPhase` and appends an assembly summary to the output.

## 5. Data Flow

```
RepoResolver  -> List<SourceRepo>            -> RepoCopier, MetaConsolidator
CarIdGate     -> carId: String               -> MetaConsolidator
RepoScanner   -> RepoInventory (buildFiles)  -> SettingsGenerator (module paths)
MigrationAnalyzer -> AnalysisReport
                      .catalog (CatalogModel) -> CatalogFileWriter
                      .monorepoVersion        -> RootPropertiesWriter
ToolConfig.template.path -> TemplateMaterializer
--out / monorepo.name    -> <out>/<name>      (assembly root for every writer)
```

## 6. Error Handling

All user-facing failures throw `MigrationException` (no new exception types):
missing `template.path` in local mode, missing/unreadable template dir, `--out`
absent on a non-dry-run, or a target collision with a non-empty unrelated directory.

## 7. Testing

JUnit5 + Kotest against `fixtures/`, assembling into a JUnit `@TempDir`. No clone,
no Gradle execution, no OpenRewrite.

- Per-leaf unit tests against self-built temp trees.
- End-to-end `AssemblyPhase` test asserting the full output tree for the bundled
  fixtures:
  ```
  vehicle-platform/
    settings.gradle              # rootProject.name + include(':payments-service'), include(':billing-service')
    gradle.properties            # version=4.0.0
    gradle/libs.versions.toml    # rendered catalog (matches CatalogRenderer output)
    meta/source.yaml             # carId: 200009890 only
    payments-service/…           # copied verbatim, meta/ removed
    billing-service/…            # copied verbatim, meta/ removed
  ```
- A `runMigration(dryRun = true)` test asserts no output dir is created.

## 8. Execution Strategy

Subagent-driven development with a parallel first wave:

- **Wave 1:** the 6 independent leaf units, each built concurrently in its **own git
  worktree** (`isolation: worktree`) with the standard two-stage spec + code-quality
  review, then merged back to `feat/plan-3-assembly`.
- **Wave 2:** `AssemblyPhase` + config/CLI wiring, built sequentially once all
  leaves are merged.

## 9. Deferred to Plan 4 (OpenRewrite)

build-file coords → `libs.*` aliases; `plugins {}` → `alias(...)`; strip centralized
version props; **buildscript/pluginManagement relocation**; `lambda.json`
`version`/`functionVersion` stripping. Plan 5 remains Validation + Rollback.
