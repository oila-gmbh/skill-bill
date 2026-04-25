## [2026-04-25] runtime-mcp-lifecycle-native
Areas: runtime-application lifecycle telemetry service, runtime-infra-sqlite lifecycle telemetry store, runtime-mcp dispatcher, docs/migrations/SKILL-27-cutover-checklist.md
- Ported the remaining MCP telemetry lifecycle tools to Kotlin-native services and SQLite persistence while preserving standalone session/outbox behavior and orchestrated child telemetry payloads.
- Removed the temporary Kotlin-to-Python MCP bridge and `skill_bill.mcp_tool_bridge`; Python remains only as the explicit CLI/MCP fallback path.
- Reusable pattern: once the process boundary is cut over, retire compatibility bridges by adding a narrow application service plus persistence port, then keep MCP argument coercion at the adapter edge.
Feature flag: N/A
Acceptance criteria: native lifecycle MCP handlers, bridge removal, standalone/orchestrated telemetry parity, runtime validation

## [2026-04-25] runtime-mcp-stdio-cutover
Areas: runtime-mcp application entrypoint, skill_bill.launcher, installer MCP registration, docs/migrations/SKILL-27-cutover-checklist.md
- Added a Kotlin stdio MCP server that speaks line-delimited JSON-RPC, exposes the Python-compatible tool inventory, and dispatches ported MCP tools through Kotlin runtime services.
- Switched `skill-bill-mcp` to default to the Kotlin MCP server through the launcher, with `SKILL_BILL_MCP_RUNTIME=python` as the explicit rollback path.
- Kept a narrow Python bridge for telemetry lifecycle tools that are still Python-owned, so MCP callers keep stable tool names and payload shapes during the Kotlin server cutover.
- Reusable pattern: executable cutover can move the process boundary first while preserving unported leaf behavior behind a named compatibility bridge; document the bridge as transitional, not retired architecture.
Feature flag: N/A
Acceptance criteria: Kotlin stdio MCP packaging, launcher default switch, Python fallback, tool inventory compatibility

## [2026-04-25] runtime-cli-final-cutover
Areas: skill_bill.launcher, pyproject.toml, runtime-cli application entrypoint, docs/migrations/SKILL-27-cutover-checklist.md, docs/getting-started.md
- Switched the installed `skill-bill` script to a launcher that defaults to the Kotlin CLI and keeps `SKILL_BILL_RUNTIME=python` as the explicit rollback path.
- Added a Kotlin CLI `main` plus Gradle application run path, including stdin forwarding so payload-backed commands such as `new-skill --payload - --dry-run` work through the Kotlin-default launcher.
- Kept `skill-bill-mcp` Python-backed and made Kotlin MCP selection loud-fail until a real Kotlin stdio MCP server is packaged.
- Reusable pattern: cut over one executable surface only when it has an executable runtime path; leave unsupported sibling surfaces on documented fallback instead of pretending parity exists.
Feature flag: N/A
Acceptance criteria: Kotlin-default CLI launcher, Python fallback, MCP fallback boundary

## [2026-04-25] runtime-cutover-preparation
Areas: docs/migrations/SKILL-27-cutover-checklist.md, runtime-kotlin/ARCHITECTURE.md, runtime surface contracts, runtime smoke tests
- Added a maintained Kotlin runtime cutover checklist that names the current Python default, Kotlin parity gates, Phase 9 switch plan, and rollback path.
- Updated runtime surface contracts so scaffold and install are active Kotlin-owned surfaces after the completed scaffold-loader-install bridge, while launcher remains the reserved default-runtime switch surface.
- Reusable pattern: do not flip entrypoints in the same step that documents cutover; keep Phase 8 as handoff preparation and Phase 9 as the intentional default-runtime switch.
Feature flag: N/A
Acceptance criteria: cutover checklist, active surface metadata, launcher boundary preserved

## [2026-04-25] runtime-scaffold-loader-install-adapter
Areas: skillbill.scaffold, skillbill.install, skillbill.cli, skillbill.mcp, runtime-core, runtime-cli, runtime-mcp, runtime-contracts
- Added Kotlin scaffold-loader/install primitives and wired the CLI/MCP scaffold paths onto the Kotlin core while keeping the Python runtime as the broader oracle for the remaining surface.
- Preserved the governed loader and scaffold payload contract in the adapter layer, including explicit repo-root injection, typed started/finished payloads, and install symlink handling.
- Reusable pattern: boundary adapters should normalize payload quirks at the edge, then let the shared Kotlin core own the actual filesystem and manifest behavior.
- Runtime-kotlin `./gradlew check` passes with the new scaffold-loader-install bridge in place.
Feature flag: N/A
Acceptance criteria: scaffold loader/install bridge, adapter payload parity, full runtime-kotlin check

## [2026-04-25] runtime-model-package-ownership
Areas: runtime-domain model packages, runtime-ports model packages, runtime-application model packages, RuntimeContext, architecture tests
- Moved public data/enum model declarations out of service, runtime, and port interface files into explicit `model` packages.
- `LearningResolution`, `TelemetryOutboxRecord`, `WorkflowStateRecord`, and `HttpResponse` now live under port-owned model packages; learning/review/telemetry/domain DTOs live under area model packages.
- Moved `RuntimeContext` to `skillbill.model` and updated runtime composition/adapters to import it from the shared model package.
- Moved SQLite-owned tests from `runtime-core` to `runtime-infra-sqlite` so internal migration/schema details remain encapsulated in their owning module.
- Reusable guardrail: architecture tests now fail if a public data/enum/sealed model declaration appears in application/domain/port modules outside a `model` package.
Feature flag: N/A
Acceptance criteria: public runtime model types have explicit model package ownership

## [2026-04-25] runtime-deeper-gradle-module-split
Areas: settings.gradle.kts, runtime-contracts, runtime-domain, runtime-ports, runtime-application, runtime-infra-*, runtime-core, architecture tests
- Extracted the cleaned runtime boundaries into physical Gradle modules: contracts, domain, ports, application, SQLite infra, HTTP infra, filesystem infra, core composition, CLI, and MCP.
- Kept package names stable while moving ownership: `RuntimeContext` and ports now live in `runtime-ports`; Kotlin-Inject wiring and reserved surfaces remain in `runtime-core`.
- Reusable pattern: `runtime-core` re-exports shared modules for current CLI/MCP composition, while future cleanup can reduce those API re-exports once adapters declare direct dependencies.
- Architecture docs and guardrails now scan all module source roots and assert the implemented deeper split.
Feature flag: N/A
Acceptance criteria: deeper physical split implemented

## [2026-04-25] runtime-split-blocker-cleanup
Areas: skillbill.contracts, skillbill.application, skillbill.infrastructure.http, skillbill.infrastructure.sqlite.review, skillbill.telemetry, RuntimeContext, architecture tests
- Moved application/domain/port-to-contract mapping out of `skillbill.contracts`; contracts now stay DTO/serializer-only for future `runtime-contracts` extraction.
- Moved telemetry proxy batch mapping beside the HTTP adapter, leaving contract DTOs in `contracts.telemetry`.
- Removed the HTTP infrastructure default from `RuntimeContext`; CLI/MCP contexts own the JDK requester while core defaults to `UnconfiguredHttpRequester`.
- Moved SQL-backed review persistence, stats, feedback, and review telemetry helpers into `skillbill.infrastructure.sqlite.review`; `skillbill.review` is now persistence-free.
- Made telemetry compatibility facades port-backed so they no longer construct filesystem, SQLite, or HTTP adapters.
- Reusable guardrail: architecture tests now ban upward runtime imports from `skillbill.contracts`, infrastructure imports from `RuntimeContext` and telemetry facades, and persistence imports from `skillbill.review`.
Feature flag: N/A
Acceptance criteria: 4/4 deeper split blockers resolved

## [2026-04-25] runtime-gradle-module-split
Areas: settings.gradle.kts, build.gradle.kts, runtime-core, runtime-cli, runtime-mcp, architecture tests, SKILL-28 spec
- Split the runtime into `runtime-core`, `runtime-cli`, and `runtime-mcp`; CLI/MCP adapters now compile as independent Gradle modules over core.
- Kept deeper contract, domain, application, SQLite, and HTTP module extraction deferred in `docs/architecture/gradle-module-split-evaluation.md` until upward dependencies are removed.
- Reusable pattern: adapter modules use `api(project(":runtime-core"))` only where public context types expose core runtime ports; core exposes serialization as `api` because `JsonSupport` returns serialization types.
- Runtime architecture tests now scan all module source roots and assert the split decision plus declared Gradle modules.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-04-25] runtime-placeholder-surface-contracts
Areas: skillbill.install, skillbill.launcher, skillbill.scaffold, skillbill.workflow.*, skillbill.contracts.surface, runtime smoke tests
- Replaced empty marker interfaces with reserved runtime surface objects exposing `RuntimeSurfaceContract` metadata.
- Documented why install, launcher, scaffold, feature-implement workflow, and feature-verify workflow remain placeholder-only.
- Reusable pattern: reserved runtime surfaces must declare owner package, contract version, reserved status, and a concrete placeholder reason before implementation.
- Runtime smoke coverage now asserts reserved contracts and package boundaries instead of only checking class/package presence.
Feature flag: N/A
Acceptance criteria: 2/2 implemented

## [2026-04-25] runtime-contract-dtos-presenters
Areas: skillbill.contracts, skillbill.cli, skillbill.mcp, skillbill.application, architecture tests, golden fixtures
- Added explicit JSON contract DTOs for learning, review, MCP adapter-only payloads, and shared system version/doctor payloads.
- Moved CLI text rendering for learnings and review triage onto typed presenter models instead of rendering from raw map entries.
- Reusable pattern: external payload fields should be assembled in `skillbill.contracts.*`; CLI adapters choose text presenters or JSON payloads at the boundary.
- Golden coverage now pins representative CLI import-review JSON and MCP learning-resolution JSON while existing CLI/MCP behavior remains stable.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-25] runtime-versioned-db-migrations
Areas: skillbill.db, runtime architecture tests, database migration tests
- Added `schema_migrations` as the SQLite migration ledger and made `DatabaseMigrations` run ordered named migration objects only when their version is not recorded.
- Preserved legacy additive/backfill behavior by wrapping the existing review/workflow column and feedback-event normalization helpers as versioned migrations.
- Reusable pattern: future DB changes should append a new `DatabaseMigration` entry, keep names immutable, and cover legacy compatibility plus repeated-open behavior.
- Known limitation: base schema still creates the current full schema first; versioned migrations record compatibility state inside the current single-module bootstrap.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-25] workflow-runtime-phase-5
Areas: skillbill.workflow, skillbill.application, skillbill.ports.persistence, skillbill.infrastructure.sqlite, skillbill.cli, skillbill.mcp, runtime contracts
- Added Kotlin-owned durable workflow runtime behavior for `bill-feature-implement` and `bill-feature-verify`: open, update, get, list, latest, resume, and continue now route through `WorkflowService`.
- Reused the existing `feature_implement_workflows` and `feature_verify_workflows` SQLite tables through `WorkflowStateRepository`; no workflow-local store, schema redesign, Python entrypoint cutover, loader/scaffolder/install changes, launcher changes, or Python deletion were included.
- Preserved stable step ids, step state ordering, artifact patch semantics, resume summaries, session-summary hydration from telemetry session rows, blocked/done/already-running/reopened continuation decisions, and exact step-specific continuation directives in Kotlin domain/contract code.
- CLI and MCP adapters now delegate workflow calls to application services and add adapter-facing `status`/`db_path`/blocked-error payload fields at the boundary.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-24] runtime-telemetry-ported-subsystem
Areas: skillbill.application, skillbill.telemetry, skillbill.ports.telemetry, skillbill.infrastructure.http, skillbill.infrastructure.fs, skillbill.contracts.telemetry, architecture tests
- Added telemetry settings/config/client ports and wired them through Kotlin-Inject; application telemetry/status/sync/mutation paths now use ports plus `TelemetryOutboxRepository`.
- Moved telemetry config file reads/writes/deletes into `FileTelemetryConfigStore` and relay HTTP request/response mechanics into `HttpTelemetryClient`.
- Reusable contract surface: telemetry proxy batch and remote-stats payload mapping now lives in `contracts/telemetry`, outside sync orchestration.
- Reusable sync pattern: `TelemetrySyncRuntime` accepts outbox and client ports directly, with behavior coverage for disabled, noop, synced, failed, and unconfigured paths.
- Known limitation: legacy telemetry runtime objects remain as compatibility facades while review telemetry helpers are ported in later phases.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-24] runtime-domain-persistence-separation
Areas: skillbill.learnings, skillbill.review, skillbill.application, skillbill.ports.persistence, skillbill.infrastructure.sqlite, architecture tests
- Moved `LearningRecord` and learning request/source-validation rules into the learnings domain; `skillbill.learnings` now stays free of JDBC and review runtime imports.
- Added pure review parsing and triage decision parsing surfaces so application use cases no longer import mixed persistence runtimes for parsing/normalization.
- Reusable pattern: application use cases should query repository ports for source facts, then pass those facts to pure domain validation before calling write repositories.
- Reusable adapter: SQLite learning table access now lives in `SQLiteLearningStore`, with source-validation matching enforced before insert.
- Known limitation: review metrics/finished-payload helpers still contain transitional SQL until telemetry and review persistence are ported further.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-24] runtime-repository-unit-of-work
Areas: skillbill.application, skillbill.ports.persistence, skillbill.infrastructure.sqlite, skillbill.db, architecture tests
- Added persistence ports for database sessions, unit-of-work access, review repositories, learning repositories, telemetry outbox, and workflow state.
- Added SQLite adapters that wrap existing JDBC review/learning/store helpers; application services now call `read` or `transaction` on `DatabaseSessionFactory`.
- Reusable pattern: write use cases should own transaction choice in application services, while SQLite adapters call no-transaction repository helpers inside the active unit of work.
- Reusable tests: application use cases can be tested with fake repositories via `ApplicationPersistencePortTest`; SQLite transaction behavior is covered in `SQLiteDatabaseSessionFactoryTest`.
- Known limitation: review/learnings domain objects still contain JDBC-shaped helpers until the next domain-separation phase.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-24] runtime-typed-learning-results
Areas: skillbill.application, skillbill.learnings, skillbill.cli, skillbill.mcp, architecture tests
- Added typed learning result models for list, show, resolve, mutations, and delete; `LearningService` no longer returns map payloads for learning use cases.
- Added `LearningEntry` as the typed learning view over current `LearningRecord` rows while preserving the existing persisted/session JSON shape.
- CLI and MCP now convert typed learning results to their existing wire payloads at adapter boundaries; CLI text rendering consumes typed entries directly.
- Reusable pattern: typed-result phases should add architecture tests that block the targeted service from regressing to `Map<String, Any?>` returns.
- Known limitation: review and telemetry application result maps remain for later SKILL-28 phases.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-24] runtime-mcp-application-routing
Areas: skillbill.mcp, skillbill.application, skillbill.di, architecture tests, MCP tests
- Added `McpComponent` as the MCP Kotlin-Inject composition root over the shared `RuntimeComponent`.
- Routed MCP import, triage, learning resolution, stats, telemetry, version, and doctor calls through application services.
- Kept MCP telemetry-disabled skip payloads and orchestrated payload enrichment in the adapter; application services own shared persistence and payload assembly.
- Reusable pattern: architecture tests now ban MCP from importing DB/review/learnings runtime internals or telemetry runtime implementation APIs for shared workflows.
- Regression coverage compares CLI and MCP system-service payloads for version and doctor while preserving existing MCP payload tests.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-24] runtime-architecture-phase-0
Areas: runtime-kotlin architecture, RuntimeModule, architecture tests, SKILL-28 spec
- Added `ARCHITECTURE.md` as the runtime boundary map and target dependency-direction contract.
- Added architecture guardrail tests for the doc, declared package boundaries, application entrypoint independence, CLI command delegation, and future domain package infrastructure bans.
- Updated `RuntimeModule` so `application` and `di` are first-class declared subsystem packages.
- Reusable pattern: keep boundary tests scoped to rules true today, and document transitional exceptions such as MCP before enforcing them in later phases.
- Known limitation: MCP still bypasses application services until the planned Phase 1 refactor.
Feature flag: N/A
Acceptance criteria: 3/3 implemented
