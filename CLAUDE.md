# CLAUDE.md

Guidance for working in this repository.

## What this is

`mono-convert` — a Kotlin/JVM + Clikt CLI that converts standalone Gradle repos into one
Gradle monorepo (version-catalog unification, single root-owned version, `lambda.json`
version stripping). It is built in phases; **Phases 0–3 are implemented** (preflight, the
`carId` gate, discovery, static analysis, filesystem assembly). See `README.md` for the
user-facing overview and
`docs/superpowers/specs/2026-06-24-gradle-monorepo-migration-design.md` for the full design.

## Build & test commands

```bash
./gradlew test     # full unit suite (offline; runs against fixtures/)
./gradlew run --args="--manifest fixtures/repos.yaml --config fixtures/mono-convert.config.yaml --dry-run"
# real assembly (Phase 3) writes a monorepo under --out:
./gradlew run --args="--manifest fixtures/repos.yaml --config fixtures/mono-convert.config.yaml --out fixtures/output"
```

**Important:** this machine's default JDK is too new for the Gradle 8.8 wrapper, and JDK 17
is not installed. Run Gradle with Java 21 pinned:

```bash
JAVA_HOME=/Users/kxk78180/.sdkman/candidates/java/21.0.9-zulu ./gradlew test
```

The Java toolchain in `build.gradle.kts` is set to 21 (not 17) for the same reason — do not
"fix" it to 17 without installing that JDK. CI (`.github/workflows/ci.yml`) provisions JDK 21
and needs no pin.

## Architecture

The pipeline runs as ordered phases. Package = pipeline stage:

- `config/` — `Manifest`, `ToolConfig`, `ConfigLoader` (YAML parsing of inputs).
- `source/` — `RepoResolver` turns manifest repo names into `SourceRepo(name, target, root)`.
  LOCAL resolves `path/<name>`; CLONE delegates to a `GitCloner`.
- `gate/` — `CarIdGate` reads each repo's `meta/source.yaml` via `SourceYaml` and fails
  closed unless all `carId`s match.
- `discovery/` — `RepoScanner` walks a repo → `RepoInventory` (build files w/ `Dsl`,
  settings, `gradle.properties`, `config/<function>/lambda.json`).
- `analysis/` — Phase 2 (read-only). `RepoAnalyzer`/`MigrationAnalyzer` parse deps, plugins,
  buildscript classpath, and project/`lambda.json` versions (`BuildFileParser`,
  `GradlePropertiesParser`, `LambdaJsonReader`, `Semver`, `VersionResolver`), resolve conflicts
  (`ConflictResolver`, highest-wins), compute the monorepo version (`MonorepoVersionCalculator`),
  and build a catalog preview (`CatalogBuilder`/`CatalogRenderer`) → `AnalysisReport`. No
  OpenRewrite, no Gradle execution.
- `assembly/` — Phase 3 (filesystem, additive). `AssemblyPhase` materializes the template
  (`TemplateMaterializer`), copies each repo in (`RepoCopier`), and writes the root catalog
  (`CatalogFileWriter`), `settings.gradle` includes (`SettingsGenerator`), root version
  (`RootPropertiesWriter`), and consolidated `meta/source.yaml` (`MetaConsolidator`) →
  `AssemblyResult`. No OpenRewrite and no source-file mutation — those are Plan 4.
- `cli/` — `MigrateCommand` wires the phases; the testable logic is in `runMigration(...)`,
  separate from Clikt's `run()`. `--dry-run` stops after Phase 2; a real run assembles into
  `--out` (required unless `--dry-run`).

## Conventions

- **Errors:** all user-facing failures throw `MigrationException` (it carries an optional
  cause). The CLI relies on this so it can surface clean messages. Don't throw
  `IllegalArgumentException`/`IllegalStateException` for user-facing conditions.
- **YAML:** use the shared `com.monoconvert.Yaml.mapper` (lenient, ignores unknown props).
  Don't construct new `ObjectMapper`s for YAML.
- **Tests never clone.** Clone mode (`source: clone`, `ProcessGitCloner`) is code-supported
  but unit tests inject a throwing/capturing `GitCloner` so nothing hits the network. Keep
  it that way; tests use `source: local` against `fixtures/`.
- **In-file mutations** (build files, `lambda.json`) will go through **OpenRewrite** in Plan 4
  — readers here are read-only. Phase 3 only *copies* source files and *writes new* root files
  (catalog, settings, root `gradle.properties`, root `meta/source.yaml`); it never edits an
  existing source build file or `lambda.json`. Don't add ad-hoc string/JSON rewriting.
- **TDD:** write the failing test first, then the implementation. Match existing test style
  (JUnit 5 + Kotest assertions, real fixtures over mocks).

## Workflow

Development follows the superpowers flow: spec (`docs/superpowers/specs/`) → phased plans
(`docs/superpowers/plans/`) → subagent-driven TDD execution with spec + code-quality review
per task. Plans 1–3 are done; Plans 4–5 are pending. When starting the next plan, build on the
concrete types above (`RepoInventory`, `BuildFile`, `SourceRepo`), the analysis outputs
(`AnalysisReport`, `CatalogModel`, `ResolvedItem`, `Semver`), and the Phase 3 assembly outputs
(`AssemblyResult` and the assembled monorepo tree under `--out`). Plan 4 = OpenRewrite rewrites
(coords→aliases, plugin aliases, version-prop stripping, buildscript relocation, `lambda.json`
stripping); Plan 5 = validation + rollback.
