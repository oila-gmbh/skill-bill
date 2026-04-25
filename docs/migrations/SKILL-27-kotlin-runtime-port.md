# SKILL-27 Kotlin Runtime Port

## Status

- Issue: `SKILL-27`
- Phase: `4 - Surface integration`
- Runtime source of truth: Python
- Kotlin ownership: build foundation, shared scaffolding, persistence core, review-domain services, and in-module CLI/MCP surface adapters
- Last updated: `2026-04-25`

## Purpose

This note is the carryover document for the multi-session Kotlin runtime port.
It exists so future sessions do not need to re-discover the Python runtime
surface, the frozen contracts, or the test boundaries before starting work.

The current Python runtime remains the behavioral oracle until later phases
explicitly move a subsystem to Kotlin with parity coverage.

Recent adapter work has already moved the scaffold loader/install bridge and
the CLI/MCP scaffold envelopes onto the Kotlin runtime modules, while keeping
the broader Python runtime available as the reference oracle for the rest of
the port.

## Current Runtime Inventory

### Entry points

- `pyproject.toml`
  - `skill-bill = "skill_bill.cli:main"`
  - `skill-bill-mcp = "skill_bill.mcp_server:main"`

### Primary runtime modules

| Area | Python modules | Responsibility |
| --- | --- | --- |
| CLI shell | `skill_bill/cli.py` | Stable command tree, argument parsing, output selection, workflow and scaffold entrypoints |
| MCP server | `skill_bill/mcp_server.py` | FastMCP tool registration and payload shaping for local runtime operations |
| Persistence | `skill_bill/db.py` | SQLite schema creation, migrations/backfills, DB path resolution, connection lifecycle |
| Review domain | `skill_bill/review.py`, `skill_bill/triage.py` | Review parsing, finding import, feedback recording |
| Learnings | `skill_bill/learnings.py` | Scope-aware learnings CRUD and resolution |
| Stats and telemetry | `skill_bill/stats.py`, `skill_bill/sync.py`, `skill_bill/config.py` | Local metrics, outbox sync, remote telemetry stats, telemetry config |
| Feature workflows | `skill_bill/feature_implement.py`, `skill_bill/feature_verify.py` | Workflow ids, session ids, validation, payload builders, workflow state persistence |
| Quality and PR telemetry | `skill_bill/quality_check.py`, `skill_bill/pr_description.py` | Session tracking and emitted payload contracts |
| Governed loader | `skill_bill/shell_content_contract.py` | Manifest-driven platform pack loading and loud-fail contract enforcement |
| Scaffolder | `skill_bill/scaffold.py`, `skill_bill/scaffold_manifest.py`, `skill_bill/scaffold_domain.py`, `skill_bill/scaffold_template.py`, `skill_bill/scaffold_exceptions.py` | Atomic scaffold planning, manifest edits, telemetry, rollback, rendered governed content |
| Install and upgrade | `skill_bill/install.py`, `skill_bill/upgrade.py` | Agent installation primitives and wrapper regeneration |
| Shared contracts/output | `skill_bill/constants.py`, `skill_bill/output.py`, `skill_bill/__init__.py` | Shared enums/constants, formatter helpers, package metadata |

### Repo-level contract surfaces outside the Python package

- `orchestration/`
  - Shell/content contract playbooks
  - Workflow contract playbooks
  - Routing and telemetry source material
- `skills/` and `platform-packs/`
  - Governed content consumed by the runtime
- `scripts/validate_agent_configs.py`
  - Validator and repo contract enforcement
- `tests/`
  - Runtime behavior oracle for parity work

## Frozen Contract Inventory

These contracts are frozen for the port unless a separate approved spec changes
them deliberately.

### CLI contract

The Kotlin port must preserve the stable `skill-bill` command surface,
including at minimum these families already defined in `skill_bill/cli.py`:

- `import-review`
- `record-feedback`
- `triage`
- `stats`
- `feature-implement-stats`
- `feature-verify-stats`
- `learnings add|list|show|resolve|edit|disable|enable|delete`
- `telemetry status|sync|capabilities|remote-stats|enable|disable|set-level`
- `version`
- `doctor`
- `workflow list|show|resume|continue`
- `verify-workflow list|show|resume|continue`
- `list`
- `show`
- `explain`
- `validate`
- `upgrade`
- `render`
- `edit`
- `fill`
- `new-skill`
- `new`
- `create-and-fill`
- `new-add-on`
- `install agent-path|detect-agents|link-skill`

Frozen behaviors:

- argument names and shape
- stable subcommand names
- `text` and `json` output modes
- user-visible error semantics where callers may depend on them

### MCP contract

The Kotlin port must preserve the current `skill-bill-mcp` tool inventory and
payload shapes defined in `skill_bill/mcp_server.py`.

Current tool inventory:

- `import_review`
- `triage_findings`
- `resolve_learnings`
- `review_stats`
- `feature_implement_stats`
- `feature_verify_stats`
- `telemetry_remote_stats`
- `telemetry_proxy_capabilities`
- `doctor`
- `feature_implement_started`
- `feature_implement_workflow_open`
- `feature_implement_workflow_update`
- `feature_implement_workflow_get`
- `feature_implement_workflow_list`
- `feature_implement_workflow_latest`
- `feature_implement_workflow_resume`
- `feature_implement_workflow_continue`
- `feature_implement_finished`
- `quality_check_started`
- `quality_check_finished`
- `feature_verify_started`
- `feature_verify_finished`
- `feature_verify_workflow_open`
- `feature_verify_workflow_update`
- `feature_verify_workflow_get`
- `feature_verify_workflow_list`
- `feature_verify_workflow_latest`
- `feature_verify_workflow_resume`
- `feature_verify_workflow_continue`
- `pr_description_generated`
- `new_skill_scaffold`

Frozen behaviors:

- tool names
- request parameter names
- returned JSON field names
- orchestrated child-payload semantics

### SQLite contract

The Kotlin port must preserve the SQLite-backed persistence model rather than
introducing a different local store.

Core persisted surfaces currently originate in `skill_bill/db.py`:

- review runs and findings
- feedback events
- learnings and session learnings
- telemetry outbox
- quality-check sessions
- feature-implement sessions and workflows
- feature-verify sessions and workflows
- review orchestration fields such as session ids and orchestrated runs

Frozen behaviors:

- schema semantics
- backfill behavior that existing runtimes depend on
- workflow/session id persistence and visibility

### Workflow contract

The Kotlin port must preserve the existing workflow runtime contract for:

- `bill-feature-implement`
- `bill-feature-verify`

Frozen behaviors:

- workflow ids and session ids where externally visible
- step ids, workflow statuses, continuation payload semantics
- stored artifact structure used for resume/continue

### Scaffold contract

The Kotlin port must preserve the scaffold payload contract and the atomic
behavior of the scaffolder.

Frozen behaviors:

- payload version checks
- schema validation and typed error behavior
- manifest mutation semantics
- rollback on validation, manifest, or symlink failure

### Governed loader contract

The Kotlin port must preserve the manifest-driven, loud-fail governed loader
behavior in `skill_bill/shell_content_contract.py`.

Frozen behaviors:

- manifest discovery stays dynamic
- shell contract version enforcement stays strict
- missing manifest, wrong version, missing content file, or missing section
  remain named failures
- no silent fallback paths are added

Named failures currently visible in the Python loader include:

- `MissingManifestError`
- `InvalidManifestSchemaError`
- `ContractVersionMismatchError`
- `MissingContentFileError`
- `MissingRequiredSectionError`
- `PyYAMLMissingError`

### Install-path contract

The Kotlin port must preserve install-path resolution across supported agents
for the active local runtime surface, even if helper scripts remain Python for
some time.

## Test Map By Subsystem

This map identifies where the current Python behavior is protected today. It is
the starting point for later parity work.

| Subsystem | Primary tests |
| --- | --- |
| CLI surface | `tests/test_cli.py` |
| MCP surface | `tests/test_mcp_server.py`, `tests/test_mcp_stdio.py` |
| Review domain and local metrics | `tests/test_review_metrics.py`, `tests/test_review_pipeline_smoke.py`, `tests/test_workflow_stats.py` |
| Telemetry and remote stats | `tests/test_remote_telemetry_stats.py`, `tests/test_quality_check_telemetry.py`, `tests/test_pr_description_telemetry.py`, `tests/test_feature_implement_telemetry.py`, `tests/test_feature_verify_telemetry.py`, `tests/test_telemetry_network_isolation.py` |
| Feature-implement workflow runtime | `tests/test_feature_implement_workflow_state.py`, `tests/test_feature_implement_workflow_e2e.py`, `tests/test_feature_implement_agent_resume.py`, `tests/test_feature_implement_routing_contract.py`, `tests/feature_implement_agent_harness.py` |
| Feature-verify workflow runtime | `tests/test_feature_verify_workflow_state.py`, `tests/test_feature_verify_workflow_e2e.py`, `tests/test_feature_verify_agent_resume.py`, `tests/test_feature_verify_workflow_contract.py`, `tests/feature_verify_agent_harness.py` |
| Governed loader | `tests/test_shell_content_contract.py`, `tests/test_shell_pilot_integration.py`, `tests/test_content_md_hygiene.py`, `tests/test_platform_packs.py` |
| Scaffolder and upgrade paths | `tests/test_scaffold.py`, `tests/test_migration.py`, `tests/test_validate_agent_configs_e2e.py` |
| Install/uninstall | `tests/test_install_script.py`, `tests/test_uninstall_script.py` |
| Release and repo validation | `tests/test_validate_release_ref.py` |

## Initial Parity Fixture Backlog

These are the first representative fixtures or captured outputs later phases
should add. Phase 0 only defines the list; it does not require all fixtures to
exist yet.

### CLI fixtures

- `skill-bill version --format json`
- `skill-bill doctor --format json`
- `skill-bill workflow show <id> --format json`
- `skill-bill verify-workflow show <id> --format json`
- representative `learnings resolve` JSON output

### MCP fixtures

- `doctor`
- `telemetry_proxy_capabilities`
- `feature_implement_workflow_open`
- `feature_implement_workflow_update`
- `feature_implement_workflow_continue`
- `feature_verify_workflow_open`
- `new_skill_scaffold` success and rejection payloads

### Persistence fixtures

- representative SQLite database with:
  - a review run and findings
  - learnings rows
  - telemetry outbox rows
  - feature-implement workflow rows
  - feature-verify workflow rows

### Loader and scaffold rejection fixtures

- missing `platform.yaml`
- contract version mismatch
- missing governed content file
- missing required section
- invalid scaffold payload version
- scaffold rollback on post-write validation failure

## Guidance For Kotlin Architecture

Use `KMPLibraryStarter` as guidance for build hygiene and dependency management,
not as a product template.

Reuse where appropriate:

- version catalog driven dependencies
- shared build logic / convention plugins
- JDK 17 toolchain discipline
- simple module boundaries
- Kotlin-first tests and quality gates

Do not copy into this runtime:

- Android support
- iOS support
- Compose UI layers
- mobile-specific storage or UI abstractions
- multiplatform-only complexity that does not help a JVM local runtime

## Source Of Truth During Transition

As of Phase 0:

- Python is the active source of truth for every runtime subsystem.
- Kotlin owns no production subsystem yet.
- New Kotlin code in later phases must remain subordinate to Python parity
  evidence until the relevant phase explicitly flips ownership.

Ownership should move by subsystem, not by assumption.

## Phase 0 Exit Result

Completed in this session:

- phase plan in the feature spec was rewritten for multi-session carryover
- subsystem inventory was captured
- frozen contract inventory was written
- initial subsystem-to-test map was recorded
- initial parity fixture backlog was defined
- source-of-truth status was made explicit

Validation result:

- no runtime validation commands were run in this session because Phase 0 is a
  documentation and mapping checkpoint only

## Next Session Start

Start with `Phase 1 - Runtime foundation`.

The next session should:

1. scaffold `runtime-kotlin/` as a JVM-only Gradle module
2. choose the initial Kotlin dependency set and document why
3. create the shared Kotlin contract/error layer
4. make the module build successfully before porting behavior

Do not start by porting CLI or MCP behavior directly. The foundation needs to
exist first so later subsystem ports do not rework the build shape repeatedly.

## Phase 1 Results

Completed in this session:

- created `runtime-kotlin/` as an isolated JVM-only Gradle module with local
  wrapper scripts, `settings.gradle.kts`, `build.gradle.kts`,
  `gradle/libs.versions.toml`, and JDK 17 toolchain discipline
- split shared Gradle policy into a local `build-logic/` included build with
  convention plugins so the runtime foundation now matches the reference
  project's build-hygiene shape more closely without pulling in KMP/mobile
  complexity
- added module-local quality tooling for the Kotlin runtime foundation:
  `.editorconfig` with 2-space indentation, `spotless` for formatting, and
  `detekt` for static analysis without code suppressions
- added the initial Kotlin package scaffold for future subsystem ports:
  `cli`, `launcher`, `mcp`, `db`, `telemetry`, `review`, `learnings`,
  `workflow.implement`, `workflow.verify`, `scaffold`, `contracts`, `install`,
  and `error`
- added shared contract/error scaffolding so later ports can share a stable
  local success/failure and exception foundation instead of inventing new
  shapes per subsystem
- added an initial Kotlin test harness with smoke coverage for the module
  scaffold and contract-failure behavior

Contracts now covered by Kotlin:

- module build contract for a standalone JVM-only Gradle runtime
- JDK 17 toolchain and version-catalog dependency management for the Kotlin
  runtime foundation
- shared build logic / convention plugin contract inside
  `runtime-kotlin/build-logic/`
- Kotlin quality gate contract for 2-space formatting plus `spotless` and
  `detekt` enforcement inside `runtime-kotlin/`
- local contract/error scaffolding for later subsystem ports
- aggregated runtime verification so root `runtime-kotlin` checks also execute
  the included `build-logic` build's `check`, `spotlessCheck`, and `detekt`
  tasks

Runtime source of truth after Phase 1:

- Python remains the active source of truth for all production runtime
  behavior
- Kotlin currently owns only the build foundation and shared scaffolding in
  `runtime-kotlin/`

Validation run in this session:

- `cd runtime-kotlin && ./gradlew spotlessCheck`
- `cd runtime-kotlin && ./gradlew detekt`
- `cd runtime-kotlin && ./gradlew build spotlessCheck detekt`

## Phase 1 Exit Result

Checkpoint status:

- the new `runtime-kotlin/` module compiles and runs its initial Kotlin tests
- module-local formatting and lint gates are wired and passing with the repo's
  2-space Kotlin style requirement
- no CLI, MCP, persistence, workflow, scaffold, or install behavior has been
  ported yet
- the repo remains in the intended transition state where Python is still the
  behavioral oracle

## Next Session Start

Start with `Phase 2 - Persistence core`.

The next session should:

1. map `skill_bill/db.py` and the persistence-facing telemetry/session models
   into Kotlin packages under `runtime-kotlin/`
2. choose the JDBC or SQLite access approach intentionally and document the
   decision against the existing SQLite contract
3. add parity-oriented Kotlin tests around schema creation and representative
   session persistence primitives before touching higher-level domains
4. keep all new Kotlin source and Gradle files inside the `spotless` and
   `detekt` gates from the start so Phase 2 does not backload quality cleanup

Do not start review, CLI, or MCP porting before the persistence primitives are
in place.

## Phase 2 Results

Completed in this session:

- replaced the `runtime-kotlin` DB marker with a small persistence foundation
  centered on `DatabaseRuntime`, `DatabasePaths`, schema bootstrap, additive
  column migrations, and legacy feedback-event normalization
- chose raw SQLite over Room for this phase by adding `org.xerial:sqlite-jdbc`
  to the JVM runtime module; this keeps the Kotlin port close to
  `skill_bill/db.py` and avoids introducing KSP, DAO/entity modeling, or
  coroutine-first persistence APIs before the schema and migration contract is
  stable
- mirrored the Python DB path contract in Kotlin, including CLI override,
  `SKILL_BILL_REVIEW_DB`, default path under `~/.skill-bill/`, and `~`
  expansion semantics
- carried over the SQLite schema and foundational migrations needed by the
  current runtime contract, including review-run session backfill and legacy
  `feedback_events` migration into the current finding outcome enum set
- added row-level Kotlin stores for representative Phase 2 primitives only:
  telemetry outbox rows and feature workflow persistence rows
- added parity-oriented Kotlin tests for path resolution, fresh schema
  bootstrap, additive migrations/backfills, telemetry outbox lifecycle, and
  feature workflow row round-trips

Contracts now covered by Kotlin:

- SQLite path resolution parity for the runtime DB location
- idempotent database bootstrap and schema creation for the current local
  runtime tables and indexes
- additive persistence migration contract for legacy review-run and
  feedback-event rows
- representative row-level persistence primitives for telemetry outbox storage
  and feature workflow state storage

Runtime source of truth after Phase 2:

- Python remains the active source of truth for production CLI, MCP, telemetry
  sync, review, learning, workflow, scaffold, and install behavior
- Kotlin now owns the local persistence foundation in `runtime-kotlin/`,
  but not the higher-level runtime behaviors that consume it

Validation run in this session:

- `cd runtime-kotlin && ./gradlew check detekt spotlessCheck`

## Phase 2 Exit Result

Checkpoint status:

- the Kotlin runtime now boots and migrates its local SQLite schema under the
  existing quality gates
- the persistence foundation is covered by focused Kotlin tests for path
  resolution, schema creation, migration behavior, telemetry outbox rows, and
  workflow rows
- higher-level consumers of the persistence layer have not been ported yet, so
  Python remains the behavioral oracle above the row-contract layer

## Next Session Start

Start with `Phase 3 - Review domain`.

The next session should:

1. port the review-domain service layer above persistence from
   `skill_bill/review.py`, `triage.py`, `learnings.py`, `stats.py`,
   `config.py`, and `sync.py`
2. preserve representative local contracts for review parsing/import, feedback
   recording, learnings resolution/session caching, review/workflow stats, and
   telemetry proxy/sync behavior
3. add parity-oriented Kotlin tests for representative review-domain flows
   before touching CLI or MCP surfaces
4. update this migration note and the source-of-truth statement when the phase
   exits

Do not start CLI, MCP, workflow-runtime, scaffold, governed loader, or install
porting before the review-domain services are in place.

## Phase 3 Results

Completed in this session:

- ported the review import/parsing layer into `runtime-kotlin/`, including
  review-run and review-session extraction, summary metadata parsing,
  specialist-review normalization, bullet finding parsing, and table finding
  parsing with normalized severity/confidence handling
- ported triage parsing and feedback recording behavior, including structured
  and bulk decision expansion, normalized outcome mapping, review-finished
  telemetry state handling, and persistence of feedback events against the
  Phase 2 database foundation
- ported learnings resolution and session-caching behavior, including scope
  validation, rejected-source validation, precedence ordering across
  `skill`/`repo`/`global`, payload shaping, and cached session-learnings
  reads/writes
- ported representative local review and workflow stats aggregation plus
  review-finished payload shaping so `runtime-kotlin/` now computes the same
  local summary surfaces that later CLI and MCP layers depend on
- ported telemetry config resolution, proxy capability probing, remote-stats
  requests, and outbox sync behavior into Kotlin, then split the subsystem into
  focused config, HTTP, remote-stats, and sync helpers that match the repo's
  Kotlin quality gates
- added parity-style Kotlin tests for review import, triage/feedback,
  learnings/session caching, review stats payloads, workflow stats payloads,
  remote telemetry stats, proxy capability defaults, and telemetry sync failure
  handling

Contracts now covered by Kotlin:

- review import/parsing contract for required ids, summary fields, specialist
  review parsing, and bullet/table finding extraction
- triage decision normalization and feedback recording semantics
- learnings scope/source validation, resolution ordering, payload shaping, and
  session-learnings cache semantics
- representative local review stats, workflow stats, and review-finished
  telemetry payload shaping
- telemetry config precedence, proxy capability defaults, remote stats request
  shaping, and outbox sync result semantics

Runtime source of truth after Phase 3:

- Python remains the active source of truth for production CLI, MCP,
  workflow-runtime orchestration, governed loader/scaffolder, and install
  behavior
- Kotlin now owns the local persistence and review-domain service layer in
  `runtime-kotlin/`, including telemetry config/proxy/sync primitives that sit
  below the CLI and MCP surfaces
- Python remains the behavioral oracle for any user-facing contract that has
  not yet been flipped explicitly, especially command-line shape, MCP payloads,
  and long-running workflow orchestration semantics

Validation run in this session:

- `cd runtime-kotlin && ./gradlew test`
- `cd runtime-kotlin && ./gradlew spotlessApply`
- `cd runtime-kotlin && ./gradlew check detekt spotlessCheck`

## Phase 3 Exit Result

Checkpoint status:

- the Kotlin runtime now owns the local review-domain service layer on top of
  the Phase 2 persistence foundation
- representative Kotlin parity tests cover review parsing/import, triage and
  feedback, learnings resolution/session caching, local stats payloads, remote
  telemetry stats, and outbox sync behavior
- CLI, MCP, workflow-runtime, scaffold/loader, and install behavior are still
  unported, so Python remains the source of truth for those externally visible
  surfaces

## Phase 4 - Surface Integration

What changed in this phase:

- replaced the `runtime-kotlin` CLI and MCP marker interfaces with real Kotlin
  adapter objects for the review/learnings/stats/telemetry slice
- added Kotlin-side output shaping so the new CLI surface can emit the same
  representative `text` and sorted pretty `json` payload forms as the current
  Python surface for imported reviews, triage, learnings resolution, telemetry
  status/stats, doctor, and version
- added missing telemetry config mutation helpers in Kotlin for
  `telemetry enable`, `disable`, and `set-level`
- added Kotlin parity tests for representative CLI and MCP surface contracts,
  including telemetry-disabled skip behavior and orchestrated review child-step
  semantics

Contracts now covered by Kotlin:

- in-module CLI command behavior for the review/learnings/stats/telemetry slice
  under `skillbill.cli.CliRuntime`
- in-module MCP payload behavior for `import_review`, `triage_findings`,
  `resolve_learnings`, `review_stats`, feature stats, telemetry proxy/stats,
  and `doctor` under `skillbill.mcp.McpRuntime`
- representative text/json output shaping for the surfaced Phase 4 command and
  tool family

Runtime source of truth after Phase 4:

- Python remains the active source of truth for production `skill-bill` and
  `skill-bill-mcp` entrypoints, workflow-runtime orchestration, governed
  loader/scaffolder behavior, install/upgrade behavior, and any cutover or
  launcher wiring between runtimes
- Kotlin now owns the review/learnings/stats/telemetry service layer plus the
  real in-module CLI and MCP adapters inside `runtime-kotlin/`
- Python remains the behavioral oracle for any user-visible shell contract that
  is not yet actually cut over to the Kotlin runtime

Validation run in this session:

- `cd runtime-kotlin && ./gradlew test`

## Phase 4 Exit Result

Checkpoint status:

- the Kotlin runtime now contains real CLI and MCP surfaces for the
  review/learnings/stats/telemetry slice instead of markers only
- representative Kotlin parity tests cover both CLI output shape and MCP
  payload/orchestration semantics for the surfaced slice
- production Python entrypoints are still not cut over, so Python remains the
  source of truth for externally invoked CLI/MCP behavior until a later
  launcher/cutover phase intentionally flips that boundary

## Next Session Start

Start with `Phase 5 - Workflow runtime`.

The next session should:

1. port `bill-feature-implement` and `bill-feature-verify` workflow runtime
   state and continuation payload behavior into `runtime-kotlin/`
2. keep production Python entrypoints, loader/scaffolder, install behavior,
   and launcher/cutover wiring out of scope until workflow-runtime parity is
   in place
3. continue treating Python as the oracle for any external contract that is not
   yet explicitly flipped to Kotlin
