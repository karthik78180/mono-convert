# mono-convert

A reusable, idempotent CLI that converts many standalone Gradle repositories into a
single Gradle **monorepo** (multi-project build) — unifying dependency versions into one
[version catalog](https://docs.gradle.org/current/userguide/platforms.html), centralizing
the project version, and moving function version ownership out of `lambda.json` and into
Gradle.

> **Status:** Early development. This repository currently implements **Phases 0–1** of the
> migration pipeline (preflight, identity gate, and discovery). Assembly, dependency
> analysis, file rewrites, and validation land in later plans — see
> [Roadmap](#roadmap).

## What it does

Given an input manifest listing several source repos, the tool will (when complete):

1. **Preflight** — resolve each source repo on disk (clone fresh, or use a local copy).
2. **Gate** — read every repo's `meta/source.yaml` and fail fast unless they all share the
   same `carId`.
3. **Discover** — inventory each repo's build files (Groovy/Kotlin DSL), `settings`,
   `gradle.properties`, and `config/<function>/lambda.json`.
4. **Analyze** — parse all dependency/plugin versions, resolve conflicts (highest wins),
   and compute one monorepo version (highest existing version → bump major). *(Plan 2)*
5. **Assemble** — clone a template monorepo, move each repo in as a subproject, generate
   one `gradle/libs.versions.toml`, wire `settings`, and set the root version. *(Plan 3)*
6. **Rewrite** — rewrite build files to consume catalog aliases and strip
   `version`/`functionVersion` from each `lambda.json` (AST-safe, via OpenRewrite). *(Plan 3)*
7. **Validate** — run Gradle (`projects`, dependency resolution, compile/test) and compare
   the dependency graph before/after; commit only on success. *(Plan 4)*

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
```

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
  repo: "monorepo-template"
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
fixtures/                 # local sample repos, template, manifest, config (test inputs)
docs/superpowers/         # design spec + phased implementation plans
```

## Roadmap

- **Plan 1 — Foundation** (this PR): scaffold, fixtures, manifest/config parsing, source
  resolution, `carId` gate, discovery.
- **Plan 2 — Analysis**: dependency/version parsing, conflict resolution, version math.
- **Plan 3 — Assembly + Rewrites**: template clone, relocation, catalog/settings generation,
  OpenRewrite rewrites, `lambda.json` stripping.
- **Plan 4 — Validation + Rollback**: Gradle Tooling API checks, dependency-graph diff,
  journal/resume, rollback, reporting.
