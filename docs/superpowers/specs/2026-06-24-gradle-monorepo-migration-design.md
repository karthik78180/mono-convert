# Gradle Monorepo Migration Tool — Design Spec

**Date:** 2026-06-24
**Status:** Approved design, pre-implementation
**Owner:** karthikbasics@gmail.com

## 1. Summary

`mono-convert` is a reusable, idempotent **Kotlin/JVM CLI** that converts many
standalone Gradle repositories into a single Gradle monorepo (multi-project
build). It clones each source repo, validates a shared identity gate, unifies
all dependency versions into one `gradle/libs.versions.toml` version catalog,
computes and applies a single root-owned monorepo version, rewrites each
module's build files to consume catalog aliases, removes version ownership from
`config/**/lambda.json`, and assembles the result into a cloned template repo —
committing only if full Gradle validation passes.

The tool is **CI-native** (runs unchanged in GitHub Actions), **dry-run
capable**, **idempotent**, and **rollback-friendly** (nothing is committed until
validation passes).

## 2. Goals & Non-Goals

### Goals
- One reusable CLI that ingests N repos and emits one monorepo.
- Single shared version catalog (`gradle/libs.versions.toml`), highest-version-wins.
- Single monorepo version owned by root `gradle.properties` (major bump).
- Strip version ownership from `lambda.json`; root `gradle.properties` becomes
  the single source of truth for the version going forward.
- Safe, repeatable, idempotent, rollback-friendly, dry-run capable.
- Preserve each module's existing DSL (no Groovy↔Kotlin conversion).

### Non-Goals
- Converting build DSLs between Groovy and Kotlin.
- Exploding functions into separate Gradle subprojects.
- Rewriting the downstream `lambda.json` consumer (version source-of-truth moves
  to `gradle.properties`; consumer changes are out of scope for this tool).
- Generating the monorepo root skeleton from scratch (a template repo provides it).

## 3. Automation Approach (decision)

**Chosen: Hybrid**, with a Kotlin/JVM CLI as the orchestrator.

| Approach | Verdict |
|---|---|
| Pure OpenRewrite recipes | ❌ alone — can't orchestrate cross-repo steps (manifest, gate, global version math, file moves). |
| Custom Gradle plugin/task | ⚠️ validation only — chicken-and-egg: monorepo must exist before Gradle runs. |
| Standalone CLI (Kotlin/JVM) | ✅ as orchestrator — full control of ordering, dry-run, rollback, reporting. |
| **Hybrid (CLI + OpenRewrite + Gradle Tooling API)** | ✅ **Recommended.** |

Responsibility split:
- **CLI orchestrator** — manifest parsing, `carId` gate, cross-repo version
  analysis, conflict resolution, file relocation, `settings` generation, version
  math, dry-run, rollback, reporting, git clone/commit.
- **OpenRewrite (embedded)** — **all in-file mutations**, AST-safe and
  format-preserving:
  - `build.gradle(.kts)` — literal dependency/plugin coordinates → `libs.*` aliases (both DSLs).
  - `lambda.json` — `org.openrewrite.json` `DeleteKey` on `$.version` and `$.depAddress.functionVersion`.
- **Gradle Tooling API** — programmatic post-migration validation.

**Rule:** the CLI never does raw string/JSON munging of source files; every
mutation goes through OpenRewrite. Read-only parsing (e.g. reading `carId`,
`gradle.properties`) may use plain parsers.

## 4. Contracts

### 4.1 Input manifest (`repos.yaml`)
`source` and `path` are **root-level** (one mode for the whole run).
```yaml
monorepo:
  name: vehicle-platform          # new repo name; result committed here on success
source: clone                     # clone | local — applies to ALL repos
path: /Users/me/work              # base dir, MANDATORY when source: local (repo = path/<name>)
repos:
  - payments-service
  - billing-service
```
- `source: clone` → CLI builds the clone URL from config `git.baseUrl` + repo
  `name`, clones `git.defaultBranch` into a fresh work dir (always latest).
- `source: local` → root `path` is mandatory; each repo is read from `path/<name>`.

### 4.2 CLI config (`mono-convert.config.yaml`, owned by the tool)
```yaml
git:      { baseUrl: "https://github.com/myorg", defaultBranch: main }
template: { repo: "monorepo-template" }     # cloned, renamed, edited
```
Auth: `GH_TOKEN`/`gh` CLI in CI; SSH/credential helper locally.

### 4.3 `meta/source.yaml`
```yaml
carId: 200009890
otherKey: value
```
- Gate: `carId` must be identical across **all** repos, else **fail before any writes**.
- After migration: a **single root** `meta/source.yaml` keeps only `carId`;
  per-submodule `source.yaml` files are dropped.

### 4.4 `config/<function>/lambda.json`
```json
{
  "schemaversion": "3.0.0",
  "version": "1.0.9",
  "depAddress": { "functionVersion": "1.0.9", "otherKey": "value" }
}
```
- **Remove** top-level `version` and `depAddress.functionVersion`.
- **Keep** `schemaversion` (schema contract, not artifact version) and all other
  fields (artifactId, vertical, authz, decorator name, etc.).

### 4.5 Output
Clone template → rename to `monorepo.name` → apply all edits on a branch → **on
success only, commit everything to the new repo**. On any failure, nothing is
committed.

## 5. Pipeline Architecture

Ordered, resumable, journaled pipeline. Each phase is idempotent and records a
journal entry (`.mono-convert/journal.json`) with content hashes so re-runs
no-op and rollback is precise.

```
mono-convert migrate --manifest repos.yaml [--dry-run] [--rollback] [--phase N]
```

### Phase 0 · Preflight & safety
- Parse manifest + CLI config.
- For `source: clone`: build URL from `git.baseUrl` + name, clone
  `git.defaultBranch` into fresh work dir. For `source: local`: read `path/<name>`.
- Verify each source is a git repo with a clean tree.
- Clone the template repo.
- Create rollback snapshots (git tags/bundles of every source clone + template clone).
- Initialize journal.

### Phase 1 · Discovery & `carId` gate — HARD FAIL POINT
- Read `meta/source.yaml` from every repo; assert `carId` identical → else **ABORT (no writes)**.
- Inventory `build.gradle(.kts)`, `settings.*`, `gradle.properties`,
  `config/**/lambda.json`; detect DSL + nested modules per repo.

### Phase 2 · Static analysis (read-only) — dry-run stops here
- Parse every dependency declaration (build files + settings + `gradle.properties`-resolved).
- Parse every version (deps, plugins, project versions, `lambda.json` versions).
- Normalize → `group:artifact ⇒ {version per repo}`.
- Conflict detection (highest semver wins) → **Conflict Report**.
- Compute monorepo version.
- Capture per-repo dependency-graph **baseline** (`gradle dependencies`).
- Emit **Migration Plan** + catalog preview. Dry-run writes nothing.

### Phase 3 · Assembly (filesystem, additive into cloned template)
- Rename template working copy to `monorepo.name`.
- Move each repo → `./<target>/` (config/ preserved as-is).
- Generate/fill `gradle/libs.versions.toml` from resolved versions.
- Generate/append `settings.gradle(.kts)` `include(...)` for every discovered module (incl. nested).
- Write root `gradle.properties` version = computed bump.
- Consolidate `meta/source.yaml` to root (carId only); delete per-module copies.

### Phase 4 · In-file rewrites (OpenRewrite, AST-safe, idempotent)
- `build.gradle(.kts)`: literal coords → `libs.*` aliases (both DSLs).
- Strip now-centralized dependency version props; **flag** intentional local overrides (keep inline).
- Plugin versions → catalog `[plugins]`; `plugins {}` → `alias(libs.plugins.x)`.
- `lambda.json`: `DeleteKey $.version` and `$.depAddress.functionVersion` (format-preserving).

### Phase 5 · Validation (Gradle Tooling API)
- Run the validation checklist (§7). Any failure → auto-rollback.

### Phase 6 · Finalize or rollback
- Success → commit everything to the new repo; write report; leave journal.
- Failure / `--rollback` → restore from Phase 0 snapshots; discard template branch; clear work dir.

### Properties
- **Dry-run** = Phases 0–2 only; zero writes/commits.
- **Idempotent** = journal + content hashes; OpenRewrite recipes no-op on already-rewritten files.
- **Rollback-friendly** = Phase 0 snapshots; Phase 3 additive; nothing committed until Phase 5 passes.
- **Resumable** = `--phase N` resumes from journal after a fixed failure.

## 6. Algorithms

### 6.1 Conflict resolution (highest-wins)
```
deps = {}          # "group:artifact" -> { winner: Semver, observations: [(repo, rawVersion)] }
for repo in repos:
    props = parseGradleProperties(repo)                 # resolve ${prop} refs
    for decl in parseAllDeclarations(repo):             # build.gradle(.kts) + settings
        coord = decl.group + ":" + decl.artifact
        v     = normalize(resolve(decl.versionExpr, props))   # "3.7.2-alpha" -> 3.7.2
        if v == DYNAMIC: flagDynamic(coord, repo); continue   # "1.+", "latest.release"
        if coord not in deps or semverGt(v, deps[coord].winner):
            deps[coord].winner = v
        deps[coord].observations.append((repo, decl.versionExpr))

conflicts = [c for c in deps if distinctVersions(c.observations) > 1]   # -> CONFLICT REPORT
```
- Strategy: highest semver wins; every conflict listed with each repo's original
  value for human review before merge.
- Dynamic versions (`1.+`, `latest.release`) are **never auto-pinned**; flagged.

### 6.2 Monorepo version (single source of truth)
```
candidates = []
for repo:
    candidates += coreXYZ(repo.gradleProperties.version)     # strip suffix -> x.x.x
    candidates += coreXYZ(everyLambdaJson.version)           # read BEFORE we strip it
base = maxSemver(candidates)                                 # e.g. 3.7.2
monoVersion = Semver(base.major + 1, 0, 0)                   # -> 4.0.0
writeRootGradleProperties(version = monoVersion)
```
All comparison/bump math operates on the numeric `x.x.x` core; any
`-alpha`/`-RC`/build suffix is stripped first.

### 6.3 Catalog emission
Deterministic aliases (artifact id, de-collided by group segment when two
artifacts share a name); shared `version.ref` when several artifacts move together.
```toml
[versions]
jackson = "2.17.1"
[libraries]
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
[plugins]
spring-boot = { id = "org.springframework.boot", version = "3.3.0" }
```

### 6.4 In-file rewrite (both DSLs, idempotent)
```
implementation 'com.fasterxml.jackson.core:jackson-databind:2.16.0'   // Groovy before
implementation libs.jackson.databind                                  // Groovy after
implementation("...jackson-databind:2.16.0")                          // Kotlin before
implementation(libs.jackson.databind)                                 // Kotlin after
```
Project-specific overrides that intentionally differ from the catalog winner are
kept inline + flagged (not silently catalog-ized).

## 7. Validation Checklist (Phase 5 — all must pass or auto-rollback)

| # | Check | Pass criterion |
|---|---|---|
| 1 | `gradle help` | exit 0 |
| 2 | `gradle projects` | every `target` appears as a subproject |
| 3 | `gradle :<m>:dependencies` | resolves per module; no `FAILED` |
| 4 | `dependencyInsight` on catalog libs | each alias resolves to the chosen winner |
| 5 | compile (`assemble`) | all modules compile |
| 6 | test (`test`) | green (or matches per-module baseline tolerance) |
| 7 | artifact generation | expected jars/zips produced per module |
| 8 | `lambda.json` schema | valid for its `schemaversion`; `version`/`functionVersion` absent |
| 9 | before/after dep-graph diff | post-migration graph ⊇ baseline (no dropped/downgraded deps unless in Conflict Report) |
| 10 | catalog integrity | no unused aliases, no duplicates, no dangling `version.ref` |

Baseline for #9 captured per source repo in Phase 2 before any change.

## 8. Dry-run & Rollback

- **Dry-run** (`--dry-run`): Phases 0–2 only. Emits Migration Plan, Conflict
  Report, `libs.versions.toml` preview, computed monorepo version, dep-graph
  baseline. Zero writes/commits/template edits.
- **Rollback**: Phase 0 snapshots are restore points. Assembly is additive into
  the cloned template working tree; nothing is committed until Phase 5 fully
  passes, so a failure leaves no published artifact. `--rollback` discards the
  template branch and clears the work dir. Journal makes a fixed re-run resume cleanly.

## 9. Edge Cases

| Edge case | Handling |
|---|---|
| Groovy vs Kotlin DSL | OpenRewrite LST recipes target both; DSL preserved (no conversion). |
| Dynamic versions (`1.+`, `latest.release`) | Detected, never auto-pinned; flagged for manual decision. |
| Project-specific overrides | Intentional pins kept inline + flagged; not silently catalog-ized. |
| Plugin versions | Lifted into catalog `[plugins]`; `plugins {}` → `alias(libs.plugins.x)`. |
| Internal company deps | Treated like any coord; repository declarations carried over; flagged if unresolvable. |
| Duplicate artifactIds (different groups) | Alias de-collision by group prefix; both survive distinctly. |
| Nested modules | Discovery walks nested build files; `settings` includes full path (`:payments:core`). |
| Config folders w/ multiple functions | All `config/<fn>/lambda.json` walked; each stripped of version/functionVersion. |
| Existing CI/CD assumptions | Old per-repo pipelines noted as "to retire"; monorepo CI comes from template. |
| Version suffixes (`-alpha`, `-RC`) | Stripped to `x.x.x` for all comparison/bump math. |

## 10. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Silent dependency downgrade | Check #9 dep-graph diff; highest-wins; Conflict Report reviewed pre-merge. |
| OpenRewrite misses a non-standard dep notation | Unrewritten literals flagged post-Phase 4; build still resolves; report lists for follow-up. |
| Private/internal deps unresolvable in CI | Phase 5 fails closed → auto-rollback; repository creds surfaced as a prerequisite. |
| Template drift vs generated content | Template owns structure; tool fills only designated insertion points; idempotent. |
| Breaking the lambda.json consumer | Version moves to root `gradle.properties` (single source of truth); schema check #8 guards shape. |
| Partial run / interruption | Journal + resumable `--phase`; nothing committed until full pass. |

## 11. Phased Enterprise Rollout

1. **Pilot (2 repos, dry-run):** Phases 0–2, review Conflict Report + catalog preview. No writes.
2. **Pilot (2 repos, full + throwaway target):** assemble into scratch monorepo, full validation, inspect diff. Discard.
3. **Hardening:** encode pilot learnings as extra OpenRewrite recipes / conflict overrides; lock the template.
4. **Wave migration:** onboard repos in batches of 3–5, each batch dry-run → full → commit, freezing source repos as they land.
5. **Cutover:** retire per-repo CI, archive source repos (kept as rollback snapshots), monorepo CI from template becomes authoritative.
6. **Steady state:** catalog + root version are the single controls; tool remains for idempotent re-syncs.

## 12. Folder Structure — Before → After

```
BEFORE (N standalone repos)                AFTER (one monorepo from template)
payments-service/                          vehicle-platform/
├── build.gradle                           ├── settings.gradle           # includes every module
├── gradle.properties   (version=3.7.2)    ├── gradle.properties         # version=4.0.0 (only)
├── settings.gradle     (dep versions)     ├── gradle/libs.versions.toml # unified catalog
├── meta/source.yaml    (carId)            ├── gradle/wrapper/…          # from template
└── config/                                ├── .github/workflows/…       # from template
    └── fn-a/lambda.json (version,fnVer)   ├── meta/source.yaml          # root, carId only
billing-service/                           ├── payments/                 # ← payments-service
├── build.gradle.kts                       │   ├── build.gradle          # deps → libs.*
├── gradle.properties                      │   └── config/fn-a/lambda.json  # version/fnVer removed
└── config/…                               └── billing/                  # ← billing-service
                                               ├── build.gradle.kts      # deps → libs.*
                                               └── config/…
```

## 13. Open Items for Implementation Plan
- Exact OpenRewrite recipe set for catalog migration (reuse vs custom) per DSL.
- Alias de-collision naming rules (formalize).
- Conflict-override file format (human review input before merge).
- Journal schema + resume semantics.
- GitHub Actions workflow that runs `mono-convert migrate`.
