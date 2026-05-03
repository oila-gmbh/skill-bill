## [2026-05-03] repo-script-validation-migration
Areas: runtime-core repo validation, runtime-cli validation commands, scripts/, workflows, docs, feature specs
- Moved repo validation and release-ref checks off standalone Python scripts into Kotlin CLI/runtime commands, with shell wrappers kept at `scripts/validate_agent_configs` and `scripts/validate_release_ref`. reusable
- Retired one-off `migrate_to_content_md.py` and `skill_repo_contracts.py` as markdown notes; current docs/workflows now call Kotlin-backed validation and no maintainer workflow requires Python scripts.
- Strengthened Kotlin repo validation for manifest-loaded platform packs, pack-owned add-ons, sibling supporting sidecar symlinks, README/catalog references, workflow markers, telemetry contract drift, plugin metadata, and SemVer release refs.
- Regression coverage now lives in Kotlin CLI/core tests plus the remaining self-contained Python contract test; no current test imports the retired script modules.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-03] installer-runtime-cutover
Areas: install.sh, uninstall.sh, runtime-cli install commands, runtime-core install primitives, runtime-core launcher MCP config mutation, pyproject.toml, installer tests
- Moved installer/uninstaller runtime ownership off Python: shell scripts now build/use packaged Kotlin `installDist` bin shims and call `skill-bill install ...` commands for agent paths, link/unlink, cleanup, MCP registration/removal, and telemetry level mutation. reusable
- Added Kotlin install parity for Codex/OpenCode native agent path/link/unlink, selected-platform discovery (base skills plus selected pack roots), user-file-preserving target handling, symlinked-source rejection, and cleanup operations that propagate filesystem delete failures. reusable
- Added Kotlin MCP config mutation for Claude/Copilot JSON, Codex TOML, and OpenCode JSONC using packaged `runtime-mcp` commands and atomic writes; invalid OpenCode JSONC now loud-fails without overwriting user config. reusable
- Pitfall: Bash `${array[@]:-}` under `set -u` can inject an empty platform slug; use explicit length guards before iterating selected/required platform arrays.
- `pyproject.toml` remains for remaining Python maintainer tooling, but Python console scripts are gone; Python package deletion remains a later SKILL-36 subtask.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-03] kotlin-contract-parity
Areas: runtime-core scaffold/shell-content loader, runtime-cli scaffold commands, runtime-contracts errors, governed skill wrappers
- Ported remaining scaffold, shell-content-contract loading, render/fill/upgrade, and authoring validation behavior onto Kotlin-owned APIs/CLI while keeping `skill_bill/` as reference-only for later subtasks.
- Added canonical SKILL.md shape validation, render-drift validation, regular-file sidecar validation, feature-verify `audit-rubrics.md` ceremony support, and shared display-name derivation from manifest/fallback sources. reusable
- Implemented Kotlin scaffold parity for `subagent_specialists` / `no_subagents`, Codex/OpenCode stub emission, create-and-fill multi-artifact rejection, quality-check manifest registration, and byte-identical rollback tests. reusable
- Guardrail: feature-verify sidecars need both `requiredSupportingFilesForSkill` and `supportingFileTargets` entries; missing the target breaks newly scaffolded platform verify overrides even if existing wrappers render correctly.
- Validation gate passed: focused scaffold/CLI tests, `runtime-kotlin ./gradlew check`, Python unittest suite, `agnix --strict`, and agent-config validation.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-05-01] adoption-docs-and-external-author-dry-run
Areas: runtime-cli external-author tests, runtime-core scaffold manifest paths, docs adoption guides
- Rewrote adoption docs around packaged Kotlin-only CLI/MCP behavior, fail-closed vs degraded boundaries, strict contract guarantees, and model-mediated review/planning limits.
- Added a Kotlin CLI external-author dry run that scaffolds a temporary platform pack through `new --payload`, validates it, links a generated skill into a temp agent path, removes it, and validates cleanup. reusable
- Fixed scaffold manifest writes to use pack-root-relative declared file paths for new platform packs, code-review-area edits, and quality-check overrides. reusable
- Reusable guardrail: external-author validation should exercise CLI payload parsing plus manifest-loader resolution, not call the scaffold core directly.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-01] python-runtime-retirement
Areas: skill_bill.launcher, runtime-core launcher contract, runtime-mcp launch path, runtime docs
- Removed the TODO(3c) launcher surface entries for `python-fallback` and `mcp-python-fallback`; the contract now locks only packaged Kotlin CLI/MCP selection. reusable
- The 3b bridge teardown is now complete at the outer launcher boundary: retired runtime env vars no longer select Python and the Python MCP bootstrap script is gone.
- Future runtime rollback guidance should install the previous release instead of preserving Python runtime ownership.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-05-01] port-or-retire-python-backed-cli-closeout
Areas: runtime-cli authoring tests, SKILL-32 3b specs, runtime validation gate
- Closed the broad 3b parent spec after 3b_1 through 3b_4 landed by marking stale 3b/3b_2/3b_3 specs complete and adding parent close-out evidence.
- Added missing Kotlin CLI coverage for `upgrade`, `render`, `edit --body-file`, and `fill` using isolated temp authoring repos so mutating commands never touch the real workspace. reusable
- Reusable guardrail: broad close-out should audit every ported command for a concrete Kotlin CLI test, not only source-level Python-bridge removal.
- Validation gate passed after extracting authoring test helpers to satisfy Detekt `LongMethod` without suppressions.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-01] bridge-teardown-and-arch-ban
Areas: runtime-cli bridge teardown, runtime architecture tests, runtime scaffold authoring helpers
- Deleted the remaining Kotlin CLI Python bridge helpers after 3b_1/3b_2/3b_3 ported their callers; native payload reads now avoid `java.nio.file.Files` in runtime-cli main sources.
- Promoted the deferred runtime-cli FS/HTTP/SQL architecture bans and removed the temporary Python bridge allowlist. reusable
- Split `AuthoringOperations` helper groups into focused sibling files to satisfy the validation gate without Detekt suppressions. reusable
- Python CLI/scaffold tests remain intentionally for 3c/fallback while `skill_bill/cli.py` and `skill_bill/scaffold` still exist.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-30] scaffold-payload-ports
Areas: runtime-cli scaffold commands, runtime scaffold adapter, CLI golden fixtures
- Ported payload-mode `new-skill`, `new`, `new-addon`, and `create-and-fill` CLI paths to the native scaffold runtime while leaving interactive prompt modes on the Python bridge.
- Reusable pattern: keep bridge helpers present during staged retirement, but source-test ported command blocks and their native helper so payload paths cannot silently shell back to Python.
- Locked `new-skill --dry-run --format json` with a golden fixture using dynamic session/path normalization, and split scaffold CLI tests out of the broad runtime test class.
- Known limitation: editor-backed `create-and-fill` and broader inspection/authoring command ports remain deferred to later 3b subtasks.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-30] install-and-doctor-ports
Areas: runtime-cli install commands, runtime-core install facade, runtime-cli doctor subject routing, CLI source-reference tests
- Ported `install agent-path`, `install detect-agents`, and `install link-skill` from Python bridge calls to a public `InstallOperations` facade over internal install primitives. reusable
- Retired `doctor skill` on the Kotlin CLI with a stable replacement message while leaving subjectless `doctor` on the native `SystemService` path.
- Reusable guardrail: when bridge helpers must remain for deferred commands, add targeted source-reference tests around the newly ported command blocks instead of promoting broad architecture bans early.
- Known limitation: scaffold/authoring commands and launcher Python fallback remain owned by later 3b/3c subtasks.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-30] kotlin-native-contract-tests
Areas: runtime-cli golden fixtures, runtime-mcp golden fixtures, runtime architecture tests, runtime surface contracts
- Added golden JSON contract coverage for Kotlin-native CLI/MCP surfaces while explicitly deferring Python-backed scaffold/authoring/install and `doctor <subject>` work to 3b.
- Reusable pattern: normalize dynamic workflow ids/timestamps and scaffold paths only after asserting shape, prefixes, timestamp format, and path suffixes.
- Strengthened architecture guardrails with source-reference bans for Python bridge markers and runtime-mcp FS/HTTP/SQL dependencies, with only the current `McpScaffoldRuntime` repo-root lookup exception.
- Added active runtime surface locks for launcher, install, scaffold, feature-implement workflow, and feature-verify workflow; launcher Python fallback entries remain a TODO for 3c.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-04-30] packaging-schema-integration-validation
Areas: skill_bill.launcher, install.sh, runtime-mcp schemas, repository validation gate
- Confirmed the packaged Kotlin CLI/MCP installDist launch path and strict MCP schema boundary coexist through the full repository gate plus installer/stdio smoke checks. reusable
- Reusable validation pattern: run the four-command repo gate, force `McpStdioServerTest` when schema behavior matters, then smoke `skill-bill doctor` and MCP `initialize`/`tools/list` with Kotlin runtime overrides unset.
- Known limitation: `install.sh` is interactive and resets `~/.skill-bill` during reinstall, so workflow telemetry/state opened before an installer rehearsal is intentionally discarded.
Feature flag: N/A
Acceptance criteria: 5/5 implemented (Python rollback criterion superseded by Kotlin-only runtime direction)

## [2026-04-30] runtime-mcp-strict-priority-schemas
Areas: runtime-mcp tool registry, MCP stdio argument boundary, MCP schema tests
- Published strict root input schemas for priority telemetry, review, learning, scaffold, and workflow MCP tools; zero-argument workflow tools now advertise empty strict object schemas instead of open objects. reusable
- Added stdio boundary validation that rejects undeclared top-level arguments for tools whose published schema sets `additionalProperties=false`, before handler dispatch.
- Expanded `McpStdioServerTest` to lock strictness, required argument publication, enum publication, unknown argument rejection, and zero-argument strict schemas.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-04-30] packaged-runtime-installer
Areas: skill_bill.launcher, install.sh, runtime-cli installDist, runtime-mcp installDist, launcher/installer tests
- Switched default Kotlin CLI/MCP launch commands from Gradle `run` tasks to packaged application `installDist` bin scripts; missing distributions now loud-fail with installDist guidance. reusable
- `install.sh` now builds runtime-cli/runtime-mcp distributions and verifies both packaged bin scripts before registering MCP shims.
- Kept Kotlin development override env vars for local commands; the Python launcher shim is transitional and should not shape new runtime acceptance gates.
- Regression coverage locks packaged CLI/MCP path resolution, missing-distribution messaging, installer build/location behavior, and no-Gradle MCP registration.
Feature flag: N/A
Acceptance criteria: 5/5 implemented (Python rollback criteria superseded by Kotlin-only runtime direction)

## [2026-04-30] runtime-green-gate-and-python-ownership
Areas: runtime-application lifecycle validation, telemetry sync, SKILL-27 cutover checklist
- Split lifecycle telemetry validation into feature-implement, feature-verify, quality-check, and shared validator files so each lifecycle family owns its validation rules and the runtime-application Detekt `TooManyFunctions` gate stays green. reusable
- Collapsed `TelemetryService.autoSync` into a single guard predicate to satisfy the return-count rule without changing sync behavior.
- Added the Python retirement ownership inventory to the SKILL-27 cutover checklist, classifying CLI commands, MCP tools, scaffold/authoring commands, install primitives, validation scripts, and release/support scripts before any Python deletion starts.
Feature flag: N/A
Acceptance criteria: runtime green gate restored, Python ownership inventory published

## [2026-04-30] runtime-mcp-test-telemetry-loopback
Areas: runtime-mcp tests, lifecycle telemetry fixtures, MCP stdio tests
- PostHog inspection found `test-install-id` lifecycle/review events from Kotlin MCP tests because enabled test configs left `proxy_url` blank, which correctly resolves to the hosted relay in production settings.
- Added a loopback telemetry proxy override to enabled MCP test environments, including the user-home config coverage path and stdio tool-call fixture, so auto-sync cannot emit test events to production telemetry. reusable
- Reusable pattern: tests that enable telemetry should either inject a fake requester or set an explicit test-only proxy; do not rely on blank proxy config when the runtime treats blank as hosted relay.
Feature flag: N/A
Acceptance criteria: test telemetry cannot target hosted relay, runtime-mcp check passes

## [2026-04-30] runtime-mcp-feature-implement-lifecycle-schemas
Areas: runtime-mcp tool registry, MCP stdio tools/list contract, lifecycle telemetry tools
- Added explicit MCP input schemas for `feature_implement_started` and `feature_implement_finished`; the previous open schema made Codex expose these mandatory lifecycle tools as no-argument tools even though handlers required fields. reusable
- Locked the regression with an MCP stdio tools/list test that asserts required lifecycle fields such as `feature_size`, `issue_key`, `session_id`, and `completion_status` are advertised.
- Tightened feature-implement lifecycle validation so missing/defaulted started fields (`spec_input_types=[]`, zero criteria, zero text spec word count, `issue_key_type=none` with an issue key) and impossible completed metrics (`review_iterations=0`, skipped audit/validation) fail instead of emitting low-value events. reusable
- Operational symptom: a completed SKILL-32 feature-implement run committed successfully but initially missed `skillbill_feature_implement_started` and `skillbill_feature_implement_finished`; the missing pair was backfilled through the Kotlin MCP stdio server as session `fis-20260430-074408-b27m`.
- Known limit: the backfilled session has `duration_seconds=0` because started/finished were emitted together after the fact; future valid runs must call started at assessment time and finished at finalization time.
Feature flag: N/A
Acceptance criteria: MCP lifecycle schemas exposed, incomplete telemetry rejected, focused runtime-mcp check passes, telemetry pair backfilled

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
