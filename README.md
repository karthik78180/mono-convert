# mono-convert

A reusable, idempotent CLI that converts many standalone Gradle repositories into a
single Gradle **monorepo** (multi-project build) — unifying dependency versions into one
[version catalog](https://docs.gradle.org/current/userguide/platforms.html), centralizing
the project version, and moving function version ownership out of `lambda.json` and into
Gradle.

> **Status:** Early development. This repository currently implements **Phases 0–3** of the
> migration pipeline (preflight, identity gate, discovery, static analysis, and filesystem
> assembly). In-file rewrites and validation land in later plans — see [Roadmap](#roadmap).

## What it does

Given an input manifest listing several source repos, the tool will (when complete):

1. **Preflight** — resolve each source repo on disk (clone fresh, or use a local copy).
2. **Gate** — read every repo's `meta/source.yaml` and fail fast unless they all share the
   same `carId`.
3. **Discover** — inventory each repo's build files (Groovy/Kotlin DSL), `settings`,
   `gradle.properties`, and `config/<function>/lambda.json`.
4. **Analyze** — parse all dependency/plugin/buildscript-classpath versions, resolve conflicts
   (highest wins), compute one monorepo version (highest existing version → bump major), and
   render a `libs.versions.toml` catalog preview.
5. **Assemble** — materialize a template monorepo, copy each repo in as a subproject, generate
   one `gradle/libs.versions.toml`, wire `settings`, set the root version, and consolidate
   `meta/source.yaml` to the root.
6. **Rewrite** — rewrite build files to consume catalog aliases, relocate buildscript concerns,
   and strip `version`/`functionVersion` from each `lambda.json` (AST-safe, via OpenRewrite). *(Plan 4)*
7. **Validate** — run Gradle (`projects`, dependency resolution, compile/test) and compare
   the dependency graph before/after; commit only on success. *(Plan 5)*

Full design: [`docs/superpowers/specs/2026-06-24-gradle-monorepo-migration-design.md`](docs/superpowers/specs/2026-06-24-gradle-monorepo-migration-design.md).

## Requirements

- **JDK 21** (the Gradle build targets a Java 21 toolchain).
- No network needed for the test suite — it runs entirely against local fixtures.

## Build & test

```bash
./gradlew test
```

> **Local note:** on a machine whose default JDK is newer than what the Gradle 8.8 wrapper
> supports (e.g. JDK 25), pin Java 21 for the Gradle run:
>
> ```bash
> JAVA_HOME=/path/to/jdk-21 ./gradlew test
> ```
>
> CI uses `actions/setup-java` with JDK 21, so no pin is needed there.

## Running the CLI

The `migrate` command takes an input manifest and a tool config:

```bash
./gradlew run --args="--manifest fixtures/repos.yaml --config fixtures/mono-convert.config.yaml --dry-run"
```

Expected output (against the bundled fixtures):

```
monorepo: vehicle-platform
carId: 200009890
repos: 2 (dry-run)
  - payments-service -> payments-service | build files: 1, lambda.json: 1
  - billing-service -> billing-service | build files: 1, lambda.json: 2
monorepo version: 4.0.0
conflicts: 3
  ! com.fasterxml.jackson.core:jackson-databind -> 2.17.1 (was: payments-service=2.16.0, billing-service=2.17.1)
  ! org.apache.commons:commons-lang3 -> 3.13.0 (was: payments-service=${commonsLangVersion}, billing-service=3.12.0)
  ! org.junit.jupiter:junit-jupiter -> 5.10.2 (was: payments-service=5.10.0, billing-service=5.10.2)
dynamic (flagged, not pinned): com.google.guava:guava
catalog preview:
  [libraries]
  commons-lang3 = { module = "org.apache.commons:commons-lang3", version = "3.13.0" }
  jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version = "2.17.1" }
  junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version = "5.10.2" }
  spring-boot-gradle-plugin = { module = "org.springframework.boot:spring-boot-gradle-plugin", version = "3.3.0" }

  [plugins]
  boot = { id = "org.springframework.boot", version = "3.3.0" }
```

A real (non-dry-run) invocation assembles the monorepo into `--out` (required unless `--dry-run`):

```bash
./gradlew run --args="--manifest fixtures/repos.yaml --config fixtures/mono-convert.config.yaml --out fixtures/output"
```

It appends to the Phase 0–2 summary:

```
assembled: fixtures/output/vehicle-platform
modules: :payments-service, :billing-service
```

…producing `fixtures/output/vehicle-platform/` with a root `settings.gradle` (one `include`
per module), `gradle.properties` (`version=4.0.0`), `gradle/libs.versions.toml`, root
`meta/source.yaml` (carId only), and each source repo copied in as a subproject.

### Input manifest (`repos.yaml`)

`source` and `path` are root-level (one mode for the whole run). `repos` is a plain list of
repo names.

```yaml
monorepo:
  name: vehicle-platform        # new monorepo / repo name
source: local                   # clone | local — applies to ALL repos
path: fixtures/source-repos      # base dir, REQUIRED when source: local (repo = path/<name>)
repos:
  - payments-service
  - billing-service
```

- `source: clone` builds each clone URL from the tool config's `git.baseUrl` + repo name.
- `source: local` reads each repo from `path/<name>` (resolved relative to the working dir).

### Tool config (`mono-convert.config.yaml`)

Owned by the tool (not the input). Holds the git base URL and the template repo.

```yaml
git:
  baseUrl: "https://github.com/myorg"
  defaultBranch: main
template:
  repo: "monorepo-template"   # clone mode
  path: "fixtures/template"   # local mode — the template directory copied as-is
```

## How to validate

| Check | Command | Expectation |
|-------|---------|-------------|
| Unit tests | `./gradlew test` | `BUILD SUCCESSFUL`, all test classes green |
| End-to-end (dry-run) | `./gradlew run --args="--manifest fixtures/repos.yaml --config fixtures/mono-convert.config.yaml --dry-run"` | summary above; shared `carId: 200009890`, one line per repo |
| No network in tests | (review) clone mode is exercised only via injected fakes; tests never `git clone` |

## Project structure

```
src/main/kotlin/com/monoconvert/
  Yaml.kt                 # shared lenient YAML mapper
  MigrationException.kt   # typed, user-facing failure
  cli/                    # MigrateCommand (Clikt) + Main
  config/                 # Manifest, ToolConfig, ConfigLoader
  source/                 # SourceRepo, GitCloner, RepoResolver
  gate/                   # SourceYaml, CarIdGate (the carId hard gate)
  discovery/              # RepoInventory, RepoScanner
  analysis/               # Semver, parsers, ConflictResolver, catalog, MigrationAnalyzer
  assembly/               # AssemblyPhase + writers (template, repo copy, catalog, settings, version, meta)
fixtures/                 # local sample repos, template, manifest, config (test inputs)
docs/superpowers/         # design spec + phased implementation plans
```

## Roadmap

- **Plan 1 — Foundation** (done): scaffold, fixtures, manifest/config parsing, source
  resolution, `carId` gate, discovery.
- **Plan 2 — Analysis** (done): dependency/plugin/buildscript-classpath + version parsing,
  conflict resolution (highest-wins), monorepo version math, and `libs.versions.toml` catalog
  preview.
- **Plan 3 — Assembly** (done): template materialization, repo copy, `gradle/libs.versions.toml`
  + `settings` `include(...)` generation, root version, and `meta/source.yaml` consolidation
  (additive filesystem only).
- **Plan 4 — Rewrites** (OpenRewrite): build-file coords → catalog aliases, plugin aliases,
  version-prop stripping, buildscript/pluginManagement relocation, `lambda.json` stripping.
- **Plan 5 — Validation + Rollback**: Gradle Tooling API checks, dependency-graph diff,
  journal/resume, rollback, reporting.
