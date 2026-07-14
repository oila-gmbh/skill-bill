# Skill Bill Runtime Architecture

This document defines the enforced architecture for `runtime-kotlin`.

## DB-first feature-task continuation

Feature-task continuation is repository-scoped and database-authoritative. At workflow creation, an immutable identity row binds the workflow id to a normalized issue key, canonical real-path Git-root identity, repository-relative governed spec path, persisted mode, and standalone/goal-child route scope. Read-only lookup never chooses among multiple eligible rows by timestamp.

The feature `spec.md` remains the governed product contract; it is not a mutable workflow ledger. Pre-planning, planning, phase outputs, and the phase ledger remain durable database artifacts. Initial implementation continuation is hydrated from the completed `plan`. Audit-gap remediation reuses the immutable original completed `preplan` and `plan` outputs and never loops back to either planning phase.

Runtime worker ownership is mutable state kept separately from immutable execution identity. A worker lease records a random owner token, monotonic fencing generation, host and boot identity, PID plus process-birth evidence, heartbeat/expiry, and the incomplete phase attempt. Every heartbeat, phase write, takeover reservation, transfer, and release must match both token and generation. Process liveness is exact only when host, boot, PID, and birth evidence agree; unverifiable or mismatched ownership must fail loudly instead of terminating a process or creating a replacement workflow. Confirmed takeover first reserves ownership with compare-and-set, then requests graceful shutdown and escalates only if the same process identity remains live.

The runtime uses a hexagonal JVM graph with entry adapters at the outside,
application use cases in the orchestration layer, ports as the dependency
boundary, domain models and rules below the ports, and concrete infrastructure
behind those ports. `runtime-core` is only the composition root and runtime
metadata module.

```text
runtime-cli / runtime-mcp / runtime-desktop data gateways
  -> runtime-application use cases
    -> runtime-ports
      -> runtime-domain models and rules
      -> runtime-contracts helpers for port-owned boundary payload contracts

runtime-infra-fs / runtime-infra-http / runtime-infra-sqlite
  -> runtime-ports + runtime-domain + runtime-contracts

runtime-core
  -> application services, ports, domain, and concrete adapters for DI wiring
```

## Gradle Modules

- `runtime-contracts`: contract DTOs, JSON/ordered-map helpers, runtime surface
  contracts, `*SchemaPaths` constants, `*_CONTRACT_VERSION` constants, and the
  `skillbill.error` runtime exception taxonomy. It no longer owns the JSON-Schema
  validators or their schema-resource copy tasks; those moved to
  `runtime-infra-fs` (see below).
- `runtime-domain`: pure learning, review, telemetry, workflow, install-plan,
  scaffold, and skill-remove models/rules. Public domain data types live in
  area-owned `model` packages.
- `runtime-ports`: `skillbill.model.RuntimeContext`, persistence sessions,
  repositories, gateway interfaces, telemetry port interfaces, workflow git
  operations, decomposition-manifest file-store ports, port-owned model types,
  and shared payload projection for boundary events that must be consumed by
  both application and infrastructure adapters.
- `runtime-application`: CLI/MCP/shared use cases, workflow orchestration,
  telemetry lifecycle orchestration, presenter-to-contract mapping, and
  validated decomposition-manifest file/artifact projection through workflow
  ports.
- `runtime-infra-sqlite`: SQLite schema, migrations, connection/session
  behavior, SQL-backed repositories, review persistence, review stats, and
  telemetry outbox persistence.
- `runtime-infra-http`: telemetry HTTP client/requester implementation and
  telemetry proxy payload mapping.
- `runtime-infra-fs`: filesystem and process adapters for telemetry config,
  install plan/apply, install staging, governed scaffold/load/render,
  repo validation, native-agent rendering/linking, launcher MCP registration,
  git workflow operations, decomposition-manifest file storage, and
  skill-remove filesystem cascades. It also owns the concrete JSON-Schema
  validators (`InstallPlanSchemaValidator`, `WorkflowStateSchemaValidator` /
  `CanonicalWorkflowStateSchemaValidator`, `DecompositionManifestSchemaValidator`,
  and the `DecompositionManifestCoherenceValidator`) plus their schema-resource
  copy tasks (`copyInstallPlanSchema`, `copyWorkflowStateSchema`,
  `copyDecompositionManifestSchema`), reached only through domain-neutral ports.
- `runtime-core`: `RuntimeModule`, Kotlin-Inject component definitions, and DI
  providers. It may know concrete adapters only inside composition code.
  `runtime-core` publishes only the generated Kotlin-Inject ABI edges that its
  public `RuntimeComponent` exposes today: `runtime-application` service types
  and `runtime-ports` context/port types. It does not publish `runtime-domain`,
  `runtime-contracts`, or concrete infrastructure modules as API dependencies.
  Because those generated service and port types have their own public
  signatures, the transitive public ABI closure is currently
  runtime-application, runtime-ports, runtime-domain, and runtime-contracts;
  that closure is tested and must not grow into infrastructure or entrypoint
  modules.
  Downstream entry adapters and tests still declare the modules they use
  directly instead of treating `runtime-core` as a broad dependency umbrella.
  If Kotlin-Inject ever requires another generated ABI edge to be public, the
  exact edge and generated type must be documented here and mirrored by an
  architecture test. SKILL-52.2 subtask 5 adds
  `RuntimeCoreCompositionOnlyTest` as a no-regression guard: the exact
  `api(project(...))` and `implementation(project(...))` edge sets on
  `runtime-core/build.gradle.kts` are pinned, and the test fails if any
  infrastructure (`runtime-infra-*`) or entrypoint (`runtime-cli`, `runtime-mcp`,
  `runtime-desktop`) module ever appears as `api(...)`.
- `runtime-cli`: Clikt command tree, option validation, terminal rendering,
  JSON output, help, completion surfaces, and CLI runtime context creation.
  SKILL-52.2 subtask 5 narrows the main-source project dependency allow-list to
  `runtime-application`, `runtime-contracts`, `runtime-core`, `runtime-domain`,
  and `runtime-ports`. `runtime-infra-fs` and `runtime-infra-http` are dropped
  — runtime-cli has no concrete `skillbill.infrastructure.*` imports outside
  test sources; the infrastructure adapters are resolved through
  `RuntimeComponent` (kotlin-inject). The allow-list is enforced by
  `RuntimeAdapterDependencyAllowlistTest`.
- `runtime-desktop`: optional Compose Multiplatform JVM desktop app. It owns
  the desktop app-shell, navigation, Room/datastore state, design system,
  desktop feature screens, and desktop data gateways. Shared governed behavior
  stays in runtime services and ports. SKILL-52.2 subtask 5 narrows
  `runtime-desktop:core:data` jvmMain to `runtime-application`,
  `runtime-contracts`, `runtime-core`, `runtime-domain`, and `runtime-ports`
  (plus the desktop-internal `core:common`/`core:database`/`core:domain`
  modules already declared on commonMain). `runtime-infra-fs` is dropped from
  jvmMain — the desktop data gateways have no concrete
  `skillbill.infrastructure.fs.*` imports outside jvmTest; filesystem adapters
  resolve through `RuntimeComponent`. `runtime-contracts` is now explicit so
  the gateways' direct `skillbill.error.*` imports
  (`SkillBillRuntimeException`, `InvalidScaffoldPayloadError`,
  `MissingInstallSelectionRecordError`, `ScaffoldRollbackError`) do not depend
  on transitive runtime-application API. `runtime-desktop:feature:skillbill`
  declares no upstream runtime-application / runtime-domain / runtime-ports /
  runtime-contracts dependencies — the data gateway crosses the runtime
  boundary, and the feature module talks to gateways through desktop-domain
  port types. Allow-lists are enforced by `RuntimeAdapterDependencyAllowlistTest`.
- `runtime-mcp`: MCP adapter surface, MCP-specific payload shaping, stdio
  server, MCP telemetry schema validation, and MCP runtime context creation.
  SKILL-52.2 subtask 5 narrows the main-source project dependency allow-list to
  `runtime-application`, `runtime-contracts`, `runtime-core`, `runtime-domain`,
  and `runtime-ports`. `runtime-infra-fs` and `runtime-infra-http` are dropped
  — runtime-mcp has no concrete `skillbill.infrastructure.*` imports outside
  test sources; the infrastructure adapters are resolved through
  `RuntimeComponent`. The allow-list is enforced by
  `RuntimeAdapterDependencyAllowlistTest`.

The Gradle module set is:

```text
runtime-application
runtime-contracts
runtime-core
runtime-domain
runtime-infra-fs
runtime-infra-http
runtime-infra-sqlite
runtime-cli
runtime-desktop
runtime-desktop:core:common
runtime-desktop:core:data
runtime-desktop:core:database
runtime-desktop:core:datastore
runtime-desktop:core:designsystem
runtime-desktop:core:domain
runtime-desktop:core:navigation
runtime-desktop:core:testing
runtime-desktop:core:ui
runtime-desktop:feature:skillbill
runtime-mcp
runtime-ports
```

## Package Ownership

- `skillbill`: runtime metadata that is safe for all runtime modules to read.
- `skillbill.di`: Kotlin-Inject composition roots and providers, owned by
  `runtime-core`.
- `skillbill.application`: use cases, workflow orchestration, lifecycle
  telemetry orchestration, repository-port coordination, and application-owned
  mappers. Public inputs and results live in `skillbill.application.model`.
- `skillbill.application.model`: public application input/result models.
- `skillbill.model`: shared runtime model types that are not owned by a
  narrower area, currently `RuntimeContext`.
- `skillbill.config.*`: repo-local configuration domain models and resolution
  policy owned by `runtime-domain`.
- `skillbill.boundary`: cross-area marker types that do not fit a single
  module's `model` package. Currently owns
  `skillbill.boundary.OpenBoundaryMap`, the annotation that callers in
  `runtime-application`, `runtime-domain`, and `runtime-ports` apply
  to documented raw-map open boundaries. The annotation lives in
  `runtime-domain` so all three modules can apply it without inverting
  the dependency direction.
- `skillbill.ports.*`: port contracts for persistence, install, scaffold,
  validation, telemetry, workflow git operations, and decomposition-manifest
  file storage. Public port DTOs and results live in
  `skillbill.ports.*.model`; shared adapter-facing payload projection may live
  there when both application and infrastructure need the same boundary
  contract.
- `skillbill.contracts.*`: contract DTOs, JSON helpers, runtime surface
  contracts, `*SchemaPaths` constants, and `*_CONTRACT_VERSION` constants.
  Mapping from application/domain/port models into contract DTOs belongs in
  application or adapter-owned packages. This package now spans two modules: the
  DTOs, helpers, and constants compile in `runtime-contracts`, and the schema
  validator classes under `skillbill.contracts.install` and
  `skillbill.contracts.workflow` compile into `runtime-infra-fs`. The package
  name is retained on the moved validators to preserve their classpath resource
  paths and import compatibility (recorded in `agent/decisions.md` 2026-06-12).
- `skillbill.error`: runtime exception taxonomy.
- `skillbill.workflow` and `skillbill.workflow.model`: pure workflow engine,
  workflow definitions, decomposition manifest codec, wire-map conversion, and
  workflow/decomposition models owned by `runtime-domain`.
- `skillbill.goalrunner` and `skillbill.goalrunner.model`: pure goal-runner
  liveness policy, worker-subtask parsing, status projection, accounting, and
  attempt-ledger models owned by `runtime-domain`.
- `skillbill.featurespec` and `skillbill.featurespec.model`: feature-spec
  preparation policy and typed preparation/write models owned by
  `runtime-domain`.
- `skillbill.workflow.implement` and `skillbill.workflow.verify`: active
  workflow runtime-surface metadata owned by `runtime-application`.
- `skillbill.install.model`: install-plan and install-apply domain models plus
  install-plan wire-map conversion owned by `runtime-domain`.
- `skillbill.scaffold.model`: platform manifest, scaffold result, skill-class,
  routing, add-on, and review-composition models owned by `runtime-domain`.
- `skillbill.domain.skillremove` and `skillbill.domain.skillremove.model`: pure
  skill-remove service, target validation, rollback/refusal types, and removal
  models owned by `runtime-domain`.
- `skillbill.learnings` and `skillbill.learnings.model`: learning scope/source
  validation rules, learning payload helpers, and learning models owned by
  `runtime-domain`.
- `skillbill.review` and `skillbill.review.model`: pure review parsing, triage
  decision normalization, and review models owned by `runtime-domain`.
- `skillbill.telemetry` and `skillbill.telemetry.model`: telemetry settings
  normalization, sync orchestration, config mutation rules, lifecycle telemetry
  records, and port-backed runtime surfaces owned by `runtime-application` and
  `runtime-domain`.
- `skillbill.infrastructure.fs`: filesystem gateways for repo validation,
  install, scaffold, native-agent, launcher, telemetry config, git workflow,
  review input loading, decomposition-manifest file storage, and skill-remove
  ports.
- `skillbill.infrastructure.http`: HTTP telemetry client and telemetry proxy
  payload mapping.
- `skillbill.infrastructure.sqlite` and `skillbill.db`: SQLite session factory,
  schema, migrations, repositories, review stores, stats, and telemetry outbox
  persistence.
- `skillbill.install`, `skillbill.scaffold`, `skillbill.nativeagent`,
  `skillbill.launcher`, and `skillbill.skillremove`: filesystem/process
  implementation packages owned by `runtime-infra-fs`.
- `skillbill.cli`: CLI adapter code. It validates CLI input, formats terminal
  output, maps typed results to contract payloads, and delegates behavior to
  application services or ports.
- `skillbill.mcp`: MCP adapter code. It validates MCP input, shapes MCP
  payloads, owns MCP-specific schema seams, and delegates shared behavior to
  application services or ports.
- `skillbill.desktop`: desktop app-shell and feature code. Desktop data
  gateways call application services and ports.

## Boundary Rules

1. CLI, MCP, and desktop data gateways are entry adapters. They validate and
   translate input, then delegate to application use cases or ports.
2. Application owns workflow and use-case orchestration. It must not depend on
   Clikt, Compose, MCP adapter types, JDBC, Java HTTP clients, or concrete
   infrastructure packages.
3. Domain packages must not depend on CLI, MCP, desktop, JDBC, Java HTTP
   clients, filesystem APIs, process environment APIs, infrastructure packages,
   or application services.
4. Port packages must not depend on application, infrastructure, entry
   adapters, or composition roots.
5. Contracts packages must not depend on application, domain area packages,
   ports, infrastructure, entry adapters, or composition roots. `runtime-contracts`
   main source is a pure DTO/constants/exceptions leaf: it MUST NOT contain any
   JSON-Schema validator, any `com.networknt.*` or `com.fasterxml.jackson.*`
   reference, or any `java.nio.file.Files` filesystem call. The concrete schema
   validators and their schema-resource copy tasks live in `runtime-infra-fs`,
   and `runtime-domain` / `runtime-application` reach schema validation only
   through the domain-owned ports `InstallPlanWireValidator`,
   `DecompositionManifestValidator`, and `WorkflowSnapshotValidator` — never by
   importing a concrete `*SchemaValidator` / `*CoherenceValidator`.
6. Infrastructure packages implement ports and may depend on domain,
   contracts, ports, and JVM APIs. They must not depend on runtime-core or
   entry adapters.
7. `runtime-core` is the composition layer. Its source packages are limited to
   `skillbill` and `skillbill.di`; only composition code may import concrete
   infrastructure implementations.
8. Entry adapters must not bypass application services and ports by importing
   concrete implementation packages such as filesystem install/scaffold,
   native-agent, launcher, skill-remove, SQLite, or HTTP adapter internals.
9. Application use cases access SQLite through repository and unit-of-work
   ports. Read use cases call a read session; write use cases call an explicit
   transaction session.
10. Telemetry application use cases depend on `TelemetrySettingsProvider`,
    `TelemetryConfigStore`, `TelemetryClient`, and
    `TelemetryOutboxRepository`. HTTP request mechanics belong in
    `skillbill.infrastructure.http`; config file IO belongs in
    `skillbill.infrastructure.fs`; telemetry ports expose typed domain result
    models from `skillbill.telemetry.model`; telemetry proxy wire DTOs belong
    in `skillbill.contracts.telemetry`; telemetry proxy payload mapping belongs
    with the HTTP adapter.
11. JSON maps, YAML maps, MCP payloads, CLI JSON payloads, and terminal strings
    are boundary concerns. Internal use cases expose typed models.

    **Raw Map Boundary Rule (SKILL-52.1):** public declarations on
    `runtime-application`, `runtime-domain`, and `runtime-ports` MUST NOT
    return or accept `Map<String, Any?>`, `Map<String, Any>`,
    `Map<String, *>`, string-keyed `MutableMap`, `HashMap`, or
    `LinkedHashMap` variants, or type aliases to those shapes unless
    they are either (a) listed by FQN
    in the curated allow-list section below (and mirrored in the
    `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` constant in
    `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`),
    or (b) annotated with `@skillbill.boundary.OpenBoundaryMap`. The
    architecture tests `runtime architecture forbids raw map shapes
    outside the open-boundary allowlist`, `open-boundary allow-list
    documents required exceptions`, and `every OpenBoundaryMap
    annotated declaration is documented in the architecture allow-list`
    enforce this rule together — the annotation is not a silent escape
    valve.

    **Open-Boundary Allow-List (SKILL-52.1):** the curated, documented
    exceptions are listed by fully-qualified name in the
    machine-readable block below. The architecture parity test parses
    the bullets between the HTML-comment start and end markers
    surrounding the bullet list below and asserts a strict set
    equality against the test constant.

    The list grandfathers in legacy raw-map surfaces (scaffold
    gateways, review repository, telemetry runtimes, learning payload
    helpers, lifecycle telemetry payloads, etc.) that pre-date the
    typed-DTO conversion. Entries are grouped by which follow-up
    subtask owns their removal so the work stays scoped:

    Port-model `toPayload` is the only sanctioned presentation-in-ports
    shape and is grandfathered for compatibility, not a pattern for new
    port DTOs. The current bounded examples are
    `RepoValidationReport.toPayload`, `ReleaseRefMetadata.toPayload`,
    and the review-finished telemetry payload family
    (`ReviewFinishedTelemetryPayload.toPayload` plus its private nested
    mappers). Any retained presentation-in-ports shape must remain
    documented here and mirrored by the architecture guard allow-list
    when it is a public raw-map boundary.

    - **Workflow scope (SKILL-52.1 documented open boundaries):**
      open-boundary serializer helpers, contracts wire-map facades,
      decomposition-manifest codec/projection seams, and the
      `WorkflowFamily.sessionSummary` durable-record lookup.
    - **Deferred-debt fields annotated `@OpenBoundaryMap`:**
      `WorkflowSnapshotView.artifacts`,
      `WorkflowContinueView.stepArtifacts`,
      `WorkflowContinueView.extraFields`, and
      `WorkflowContinueView.sessionSummary` — intentional debt to be
      retired when `WorkflowContinueView` gains a typed family
      discriminator (subtask 2/3). Also
      `WorkflowUpdateInput.stepUpdates` /
      `WorkflowUpdateInput.artifactsPatch` and
      `WorkflowUpdateRequest.stepUpdates` /
      `WorkflowUpdateRequest.artifactsPatch` — caller-supplied JSON
      patches with no shared schema until then. Also
      `PlatformManifest.customFields` — schema custom-field
      passthrough for platform packs.
    - **Accepted permanent open boundaries (SKILL-52.3 subtask 4):**
      the lifecycle telemetry payload helpers
      (`lifecycleOkPayload`, `lifecycleSkippedPayload`,
      `lifecycleErrorPayload`, `orchestratedStartedSkippedPayload`,
      `orchestratedPayload`) and the `LifecycleTelemetryService` emit
      methods stay raw-map by design: they are forward-compatible
      MCP/CLI event bags, now annotated `@OpenBoundaryMap`. The
      `SystemService.doctor` / `SystemService.version` surfaces were
      typed to `DoctorContract` / `VersionContract` and the adapters
      now own `.toPayload()`. Review service, review repository, and
      `TelemetryService` typed-boundary work closed in subtask 3.

    **Typed-Result-Model Open-Boundary Pattern (SKILL-52.1 subtask 3):**
    when a producer's wire shape is sealed by golden tests but no
    stable schema exists for every key in the payload, the result
    model MAY carry a single `@OpenBoundaryMap`-annotated
    `payload: Map<String, Any?>` field that holds the legacy
    `linkedMapOf` contents verbatim. Stable top-level scalars (e.g.
    `status`, `skillName`, `validatorRan`) are lifted to strongly-typed
    fields on the same model so callers can branch on them without
    re-reading the open-boundary map. The adapter-side
    `ScaffoldCliResultMappers` mappers emit the wire payload by
    returning the `payload` field directly — adapters own the
    wire-shape contract, and the typed model preserves byte-equivalence.
    The MCP adapter currently only exposes the `newSkillScaffold(...)`
    endpoint (which uses the strongly-typed `ScaffoldResult` directly),
    so it does not yet need a parallel mapper file; when MCP gains a
    raw-map scaffold endpoint, an `McpScaffoldResultMappers` file will
    be reintroduced alongside that wiring.

    **Closed desktop debt (SKILL-52.2 subtask 5):** the desktop adapter
    at `runtime-desktop/core/data/.../RuntimeRepoBrowserService.kt` was
    historically a third reader of the `@OpenBoundaryMap` `payload` map
    for `list`, `validate`, and `saveExactContent`. SKILL-52.2 subtask
    5 lifts the three remaining service-level reads:
    `saveExactContent` returns the typed `ScaffoldSaveExactContentResult`
    (the return value is unused beyond signalling success), `validate`
    consumes the typed `ScaffoldValidateResult.status` field for the
    pass/fail decision, and `list` consumes a typed
    `List<AuthoredSkillEntry>` projected by a dedicated mapper. SKILL-52.3
    subtask 3 then retired the `@OpenBoundaryMap` `payload` fields on the
    scaffold result DTOs entirely, so the desktop
    `runtime-desktop:core:data/.../service/mapper/` files
    (`ScaffoldListResultMapper`, `ValidationSummaryMapper`) now consume the
    typed `ScaffoldListResult.skills` / `ScaffoldValidateResult.status` +
    `issues` fields directly with no raw-map indexing. The
    `RuntimeDesktopGatewayPolicyTest` architecture test forbids raw-map
    `.payload[` reads in the desktop service/mapper sources outright and
    fails on any regression.

    Service/gateway PUBLIC APIs MAY NOT return raw `Map<String, Any?>`.
    Once a producer is typed (subtask 3 retired the eight
    `ScaffoldGateway` raw-map producers — `list`, `show`, `explain`,
    `validate`, `upgrade`, `fill`, `saveExactContent`, `editWithBodyFile`),
    re-adding a raw-map return type at the service/gateway level
    requires an explicit allow-list entry AND a documented rationale.
    The pattern's exemplars are `PlatformManifest.customFields` (open
    boundary for schema custom fields) and `WorkflowSnapshotView.artifacts`
    (durable workflow artifacts passthrough). The eight scaffold
    typed-result-model `payload` fields that SKILL-52.1 subtask 3 left as
    exemplars were retired in SKILL-52.3 subtask 3: each `Scaffold*Result`
    DTO is now fully typed and the wire map is rebuilt in the adapter
    mappers (`runtime-cli` `ScaffoldCliResultMappers`, desktop
    `ScaffoldListResultMapper` / `ValidationSummaryMapper`).

    <!-- open-boundary-allowlist:start -->

    - `skillbill.workflow.WorkflowEngine.snapshotMap`
    - `skillbill.workflow.WorkflowEngine.summaryMap`
    - `skillbill.workflow.WorkflowEngine.resumeMap`
    - `skillbill.workflow.WorkflowEngine.continueMap`
    - `skillbill.workflow.WorkflowEngine.compactContinueMap`
    - `skillbill.workflow.WorkflowEngine.updateAcknowledgementMap`
    - `skillbill.workflow.model.WorkflowContinuationArtifactSummary.value`
    - `skillbill.workflow.WorkflowEngine.continueDecision`
    - `skillbill.workflow.WorkflowSnapshotValidator.validate`
    - `skillbill.install.model.InstallPlanWireValidator.validate`
    - `skillbill.workflow.DecompositionManifestValidator.validate`
    - `skillbill.workflow.DecompositionManifestValidator.validateYamlText`
    - `skillbill.ports.workflow.DecompositionManifestFileStore.encodeManifestYaml`
    - `skillbill.workflow.DecompositionManifestCodec.decodeMap`
    - `skillbill.workflow.toWireMap`
    - `skillbill.application.decomposition.decodeDecompositionManifestMap`
    - `skillbill.application.decomposition.encodeDecompositionManifestMap`
    - `skillbill.application.decomposition.DecompositionManifestWriter.writeFromWorkflowUpdate`
    - `skillbill.application.decomposition.DecompositionManifestWriter.manifestFromWorkflowUpdate`
    - `skillbill.application.decomposition.DecompositionManifestWriter.maybeWriteFromWorkflowUpdate`
    - `skillbill.application.workflow.WorkflowFamily.sessionSummary`
    - `skillbill.workflow.GoalObservabilityEventValidator.validate`
    - `skillbill.workflow.model.GoalObservabilityEvent.toArtifactMap`
    - `skillbill.workflow.model.GoalObservabilityEvent.toCompactSummaryMap`
    - `skillbill.workflow.model.GoalObservabilityHistory.toArtifactList`
    - `skillbill.workflow.model.goalObservabilityLatestEventFromArtifacts`
    - `skillbill.workflow.model.goalObservabilityHistoryFromArtifacts`
    - `skillbill.goalrunner.model.GoalRunnerStatusProjection.latestObservabilityEvent`
    - `skillbill.goalrunner.model.GoalRunnerStatusProjectionExtras.latestObservabilityEvent`
    - `skillbill.goalrunner.model.GoalRunnerStatusProjector.project`
    - `skillbill.workflow.model.GoalProgressEvent.toArtifactMap`
    - `skillbill.workflow.model.GoalProgressHistory.toArtifactList`
    - `skillbill.workflow.GoalProgressEventValidator.validate`
    - `skillbill.workflow.model.appendBoundedHistoryBySequence`
    - `skillbill.goalrunner.model.GoalSessionAccounting.toArtifactMap`
    - `skillbill.goalrunner.model.GoalSessionAccountingHistory.toArtifactList`
    - `skillbill.goalrunner.model.GoalAttemptLedgerEntry.toArtifactMap`
    - `skillbill.goalrunner.model.GoalAttemptLedger.toArtifactList`
    - `skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator.validateAndReadPhaseOutput`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.GoalSubtaskReviewPassResult.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.GoalSubtaskReviewPassResult.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.GoalSubtaskReviewArtifactDecoder.decode`
    - `skillbill.workflow.taskruntime.model.GoalSubtaskReviewState.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.GoalSubtaskReviewState.fromArtifactMap`
    - `skillbill.ports.workflow.model.GoalSubtaskReviewInput.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap`
    - `skillbill.ports.goalrunner.GoalRunnerTerminalOutcomeStore.recoverMissingResultPrefixOutput`
    - `skillbill.workflow.taskruntime.model.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimeRunInvariantsFromArtifactMap`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimeDecomposePlanOutcomeOrNull`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal.fromArtifactMap`
    - `skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing.toArtifactMap`
    - `skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap`
    - `skillbill.application.telemetry.lifecycleOkPayload`
    - `skillbill.application.telemetry.lifecycleSkippedPayload`
    - `skillbill.application.telemetry.lifecycleErrorPayload`
    - `skillbill.application.telemetry.orchestratedStartedSkippedPayload`
    - `skillbill.application.telemetry.orchestratedPayload`
    - `skillbill.application.telemetry.LifecycleTelemetryService.featureImplementStarted`
    - `skillbill.application.telemetry.LifecycleTelemetryService.featureImplementFinished`
    - `skillbill.application.telemetry.LifecycleTelemetryService.featureTaskRuntimeStarted`
    - `skillbill.application.telemetry.LifecycleTelemetryService.featureTaskRuntimeFinished`
    - `skillbill.application.telemetry.LifecycleTelemetryService.qualityCheckStarted`
    - `skillbill.application.telemetry.LifecycleTelemetryService.qualityCheckFinished`
    - `skillbill.application.telemetry.LifecycleTelemetryService.featureVerifyStarted`
    - `skillbill.application.telemetry.LifecycleTelemetryService.featureVerifyFinished`
    - `skillbill.application.telemetry.LifecycleTelemetryService.prDescriptionGenerated`
    - `skillbill.application.telemetry.LifecycleTelemetryService.goalStarted`
    - `skillbill.application.telemetry.LifecycleTelemetryService.goalSubtaskFinished`
    - `skillbill.application.telemetry.LifecycleTelemetryService.goalFinished`
    - `skillbill.application.telemetry.LifecycleTelemetryService.goalIssueFinished`
    - `skillbill.learnings.learningPayload`
    - `skillbill.learnings.learningSummaryPayload`
    - `skillbill.learnings.scopeCounts`
    - `skillbill.learnings.learningSessionJson`
    - `skillbill.learnings.summarizeLearningReferences`
    - `skillbill.learnings.learningEntryPayload`
    - `skillbill.application.model.WorkflowUpdateRequest.stepUpdates`
    - `skillbill.application.model.WorkflowUpdateRequest.artifactsPatch`
    - `skillbill.application.model.FeatureImplementFinishedRequest.childSteps`
    - `skillbill.application.model.DecompositionManifestWriteRequest.planningResult`
    - `skillbill.application.model.DecompositionManifestRuntimeUpdate.stepUpdates`
    - `skillbill.application.model.DecompositionManifestRuntimeUpdate.artifactsPatch`
    - `skillbill.application.model.DecompositionManifestRuntimeUpdate.existingArtifacts`
    - `skillbill.install.model.buildInstallPlanWireMap`
    - `skillbill.scaffold.model.PlatformManifest.customFields`
    - `skillbill.telemetry.model.TelemetryConfigDocument.payload`
    - `skillbill.telemetry.model.TelemetryProxyCapabilities.additionalFields`
    - `skillbill.telemetry.model.TelemetryRemoteStatsResult.metrics`
    - `skillbill.telemetry.model.FeatureImplementFinishedRecord.childSteps`
    - `skillbill.workflow.model.WorkflowSnapshotView.artifacts`
    - `skillbill.workflow.model.WorkflowContinueView.stepArtifacts`
    - `skillbill.workflow.model.WorkflowContinueView.extraFields`
    - `skillbill.workflow.model.WorkflowContinueView.sessionSummary`
    - `skillbill.workflow.model.WorkflowUpdateInput.stepUpdates`
    - `skillbill.workflow.model.WorkflowUpdateInput.artifactsPatch`
    - `skillbill.ports.validation.model.RepoValidationReport.toPayload`
    - `skillbill.ports.validation.model.ReleaseRefMetadata.toPayload`

    <!-- open-boundary-allowlist:end -->

    The allow-list grandfathers legacy raw-map surfaces. The rule
    applies prospectively: new public declarations cannot join the
    legacy raw-map surface without being added to both the allow-list
    constant and this section in the same change.
    Inner-layer test sources in `runtime-application`, `runtime-domain`, and
    `runtime-ports` are also part of this boundary: their `src/test/kotlin`,
    `src/jvmTest/kotlin`, and `src/commonTest/kotlin` roots must not import
    `skillbill.infrastructure.*`, `skillbill.cli.*`, `skillbill.mcp.*`, or
    `skillbill.desktop.*`. Adapter, infrastructure, and desktop test trees,
    including `runtime-desktop/core/data/src/jvmTest`, are outside that
    inner-layer scan.
12. `java.nio.file.Path` is allowed in application, domain, and port public
    models and contracts only as an inert value type: callers may carry,
    compare, resolve, normalize, and render path values as data. Filesystem IO,
    home-directory expansion, `System.getProperty`, and process environment
    reads are adapter or composition concerns. Application/domain/port code must
    not call `Files`, `kotlin.io.path` IO helpers, `System.getenv`, or
    `System.getProperty`, and domain review parsing must stay limited to pure
    string and regex parsing.
13. Public data, enum, and sealed declarations in application, domain, and port
    modules live under explicit `model` packages. Services, runtimes, and port
    interfaces import those models instead of declaring public models inline.
14. SQLite schema changes are append-only versioned migrations recorded in
    `schema_migrations`.

The subsystem package set is:

```text
skillbill.application
skillbill.boundary
skillbill.cli
skillbill.config
skillbill.contracts
skillbill.db
skillbill.desktop
skillbill.di
skillbill.domain.skillremove
skillbill.error
skillbill.featurespec
skillbill.goalrunner
skillbill.install
skillbill.infrastructure
skillbill.launcher
skillbill.learnings
skillbill.mcp
skillbill.model
skillbill.nativeagent
skillbill.ports
skillbill.review
skillbill.scaffold
skillbill.skillremove
skillbill.telemetry
skillbill.workflow
skillbill.workflow.implement
skillbill.workflow.verify
```

## Feature-Task Workflow Family

- `bill-feature-task` is the public workflow identity for both implementation
  modes. Prose runs persist as `mode=prose` and runtime-backed runs persist as
  `mode=runtime` in the shared `feature_task_workflows` store. Feature-verify
  remains a distinct workflow family and store.
- `bill-feature-task-runtime` is the runtime-backed trigger surface. The Kotlin
  runtime owns its phase loop (`plan -> implement -> review -> audit ->
  validate`) and launches one agent per phase, reusing the goal-runner launcher
  and `WorkflowEngine` rather than a second prose orchestration loop. Its
  definition is `skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition`
  (id prefix `wftr`, contract version `FEATURE_TASK_RUNTIME_CONTRACT_VERSION`);
  persisted rows use `workflow_name=bill-feature-task`, `mode=runtime`, and
  `implementation_skill=bill-feature-task-runtime`.
- `bill-feature-task-prose` is the first-class prose mode. Persisted rows use
  `workflow_name=bill-feature-task`, `mode=prose`, and
  `implementation_skill=bill-feature-task-prose`; its stable prose step ids and
  artifact names remain unchanged.
- The `feature_task_runtime_*` and `feature_implement_*` CLI/MCP names are
  compatibility aliases to `bill-feature-task mode=runtime` and
  `bill-feature-task mode=prose` respectively. They are not separate
  authoritative workflow stores. `bill-feature`
  routes single-spec work to the canonical `bill-feature-task` router without
  hardcoding a mode. The
  authoritative recorded promote decision lives in
  `.feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md`; SKILL-70
  owns the router/prose/runtime split.
- The comparison procedure that produced the promotion evidence remains
  documented in
  [`docs/architecture/feature-task-runtime-comparison.md`](docs/architecture/feature-task-runtime-comparison.md).

## Runtime Contract And Schema Seams

- Runtime contract schemas live in `orchestration/contracts/`. The
  `*SchemaPaths` constants and `*_CONTRACT_VERSION` constants stay in
  `runtime-contracts`. The JVM JSON-Schema validators, their typed schema
  errors, and their classpath-resource copy tasks live in `runtime-infra-fs`,
  reached only through the domain-neutral ports `InstallPlanWireValidator`,
  `DecompositionManifestValidator`, and `WorkflowSnapshotValidator`.
- Workflow-state schema validation is owned by
  `skillbill.contracts.workflow.WorkflowStateSchemaValidator` (its default
  implementation `CanonicalWorkflowStateSchemaValidator`), compiled into
  `runtime-infra-fs`. The runtime-domain workflow engine MUST NOT import that
  validator directly — instead it depends on the domain-owned port
  `skillbill.workflow.WorkflowSnapshotValidator`, which the composition root
  wires to the infra adapter
  `skillbill.infrastructure.fs.WorkflowSnapshotValidatorInfraAdapter`. The
  owning read seam is still `skillbill.workflow.WorkflowEngine`; durable record
  mapping stays pure and the next engine read rejects drift. Architecture
  tests forbid any `skillbill.contracts.workflow.*SchemaValidator*` or
  `skillbill.contracts.*Mapper` import under `runtime-domain` workflow
  source. (SKILL-52.2 Subtask 4 narrowed the
  `runtime-domain -> runtime-contracts` module-graph edge to non-validator
  helpers only: `JsonSupport`, `WorkflowContracts` ordering helper, the
  `DECOMPOSITION_MANIFEST_CONTRACT_VERSION` constant, and the typed
  `InvalidWorkflowStateSchemaError`.)
- Install-plan schema validation is owned by
  `skillbill.contracts.install.InstallPlanSchemaValidator`, compiled into
  `runtime-infra-fs` and reached through the domain-owned port
  `skillbill.install.model.InstallPlanWireValidator`. The owning seams are
  install-plan building and CLI/MCP emission, both of which validate through the
  injected port rather than importing the validator directly.
- Decomposition-manifest schema validation is owned by
  `skillbill.contracts.workflow.DecompositionManifestSchemaValidator` (paired
  with `DecompositionManifestCoherenceValidator`), compiled into
  `runtime-infra-fs` and reached through the domain-owned port
  `skillbill.workflow.DecompositionManifestValidator`. The owning parse/emission
  seam is `skillbill.application.decomposition.DecompositionManifestFileWrites`, which
  validates YAML text and in-memory maps through that port before workflow
  artifacts are persisted or returned. Repo-local manifest text persistence is
  owned by
  `skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore`
  behind `skillbill.ports.workflow.DecompositionManifestFileStore`.
- Platform-pack manifest schema validation is owned by
  `skillbill.scaffold.PlatformPackSchemaValidator` in `runtime-infra-fs`. The
  owning parse seam is `skillbill.scaffold.ShellContentLoader.buildPack`.
- Native-agent composition schema validation is owned by
  `skillbill.nativeagent.NativeAgentCompositionSchemaValidator` in
  `runtime-infra-fs`. The owning parse seam is native-agent source loading and
  composition.
- Telemetry-event schema validation is owned by the MCP adapter because the MCP
  tool registry is the event-name source of truth. The owning parse seam is the
  MCP telemetry tool input validator in `runtime-mcp`.
- Goal declared-progress event schema validation
  (`orchestration/contracts/goal-progress-event-schema.yaml`) is owned by
  `skillbill.contracts.workflow.GoalProgressEventSchemaValidator` in
  `runtime-infra-fs`, reached through the domain-owned port
  `skillbill.workflow.GoalProgressEventValidator` (wired in `RuntimeComponent`
  to `skillbill.infrastructure.fs.GoalProgressEventValidatorAdapter`, mirroring
  `GoalObservabilityEventValidator`). The owning durable write/parse seam is
  `skillbill.application.WorkflowGoalRunnerOutcomeStore.recordProgressEvent`,
  which validates the declared-progress event map through the injected port
  before it is appended to the bounded `goal_progress_run_history` /
  `goal_progress_latest_event` workflow artifacts. The supervisor read seam
  (`WorkflowGoalRunnerOutcomeStore.progress`) decodes the latest declared event
  softly so a malformed stored record cannot disable deterministic liveness.

## Install Policy Ownership (SKILL-52.1 install-policy-foundation)

Install request validation and pure install-plan construction live in
`skillbill.install.policy` inside `runtime-domain`. The policy consumes typed
snapshots from `skillbill.install.model`: discovered base skills, platform pack
skills, detected agent targets, and default agent target paths. It resolves
selected platforms, planned skills, agent targets, MCP registration intent, and
the typed `InstallPlanDraft` without touching filesystem, process execution,
staging hashes, symlink checks, binary discovery, or rollback mechanics.

`runtime-infra-fs` remains the owner of filesystem/process mechanics: platform
manifest discovery and schema parsing, base-skill directory scans, agent
detection/default path probing, pointer realpath validation, content hashing,
staging path computation, symlink/native-agent/MCP/apply side effects, Windows
preflight, and rollback behavior. The infra builder converts those facts into
typed snapshots before calling the policy.

The install-plan wire map remains the schema source of truth at both existing
seams. `buildInstallPlan` still calls
`validateInstallPlanWireSnapshot(plan)`, and the CLI emission boundary still
revalidates the same helper output before emitting `installPlanPayload` or the
planning prefix of `installApplyPayload`. New install policy APIs must use typed
request/result/snapshot models and must not add public raw `Map<String, Any?>`
returns outside the documented open-boundary allow-list. Adapter modules may
call the shared wire-snapshot validator only at the approved builder and CLI
emission seams; they must not import the schema validator directly or declare
install planner/validator policy.

## Scaffold Capability Ports And Pure-Policy Ownership (SKILL-52.1 subtask 2)

The scaffold pipeline is being decomposed from the single legacy
`ScaffoldGateway` raw-map surface into typed capability ports and a pure-policy
module. Subtask 2 lands the port surface and the pure-policy ownership
boundary; the `ScaffoldGateway` raw-map elimination and the 18 scaffold
allow-list entries below are intentionally NOT yet removed — they remain
deferred to subtask 3.

- **Pure-policy ownership boundary:** every payload-shape rule, kind
  discriminator, subagent-rejection rule, platform-pack selection/defaults/
  notes computation, install-path builder, and platform-pack manifest YAML
  renderer that has no filesystem dependency lives in
  `skillbill.scaffold.policy` inside `runtime-domain`. Files in
  `runtime-domain/src/main/kotlin/skillbill/scaffold/policy/` MUST NOT
  import `skillbill.infrastructure.fs.*`,
  `skillbill.scaffold.ScaffoldService`, or
  `skillbill.scaffold.FileSystem*`. The
  `ImplementationOwnershipArchitectureTest.scaffoldPolicyPackagesMustNotImportInfraFs`
  test enforces this prospectively.
- **Capability-port surface:** scaffold IO is split across five
  capability-named ports under `skillbill.ports.scaffold.<capability>/`:
  - `source/ScaffoldSourceLoaderPort` (with
    `source/model/ScaffoldSourceLoaderModels`) — parses platform-pack
    manifests from disk.
  - `manifest/ScaffoldManifestPersistencePort` (with
    `manifest/model/ScaffoldManifestPersistenceModels`) — owns the
    `platform.yaml` read/snapshot/write/restore/render seams.
  - `staging/ScaffoldGeneratedStagingPort` (with
    `staging/model/ScaffoldGeneratedStagingModels`) — stages
    scaffold-generated artifact files with rollback.
  - `install/ScaffoldInstallLinkPort` (with
    `install/model/ScaffoldInstallLinkModels`) — applies install links
    to detected agent targets.
  - `repo/ScaffoldRepoValidationPort` (with
    `repo/model/ScaffoldRepoValidationModels`) — runs the post-stage
    governed-skill validation seam.
  - Each port has a matching `FileSystem<Capability>` adapter in
    `runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/` that
    delegates to the existing `skillbill.scaffold.AuthoringOperations`
    and `skillbill.scaffold.scaffold` IO seams. The legacy
    `FileSystemScaffoldGateway` adapter is intentionally retained — its
    raw-map removal belongs to subtask 3.
- **Subtask 3 deferred work (do not touch in this subtask):** the
  `skillbill.application.ScaffoldService.*` and
  `skillbill.ports.scaffold.ScaffoldGateway.*` raw-map open-boundary
  allow-list entries below remain in place. The accompanying
  `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` constant in
  `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`
  must continue to list them verbatim; the open-boundary
  start/end markers must not move.

## Architecture Guardrails

The architecture tests enforce the following rules:

- `ARCHITECTURE.md`, `RuntimeModule.declaredGradleModules`,
  `RuntimeModule.declaredSubsystemPackages`, Gradle settings, and smoke tests
  describe the same module and subsystem graph.
- `runtime-core` contains only `skillbill` and `skillbill.di` source packages.
- `runtime-core` does not directly re-export contract or concrete
  infrastructure modules as adapter API, and its transitive API closure stays
  limited to the documented Kotlin-Inject generated ABI closure.
- Top-level runtime modules do not depend upward, on desktop modules, or on
  sibling concrete adapters where forbidden.
- Infrastructure modules do not depend on runtime-core, CLI, MCP, desktop, or
  sibling concrete infrastructure adapters.
- CLI, MCP, and desktop adapters declare direct runtime dependencies and do not
  use runtime-core as an implementation umbrella.
- CLI, MCP, and desktop adapters call application services and ports instead
  of importing concrete install, scaffold, native-agent, launcher,
  skill-remove, SQLite, HTTP, validation, or filesystem implementation
  internals.
- MCP workflow calls must use application services.
- Application services remain independent from entry-point frameworks,
  concrete persistence, direct filesystem access, Java HTTP clients, and JDBC.
- repository and unit-of-work ports are the persistence boundary.
- versioned database migrations are recorded in `schema_migrations`.
- learning application use cases return typed results.
- Domain and port layers remain independent from adapters, infrastructure,
  composition roots, and implementation details.
- Public application, domain, and port model declarations live under `model`
  packages.
- LearningRecord is owned by the learnings domain.
- review parsing and triage decision normalization are pure surfaces.
- SQL-backed review persistence lives under `skillbill.infrastructure.sqlite.review`.
- telemetry proxy payload mapping belongs with the HTTP adapter.
- Learning, review, telemetry, workflow, install, scaffold, and skill-remove
  ownership stays in the packages named above.
- Workflow-state, install-plan, decomposition-manifest, platform-pack,
  native-agent composition, and telemetry-event schema validators are exercised
  at their owning parse seams.
- Runtime surfaces expose documented `RuntimeSurfaceContract` metadata for
  active workflow, scaffold, install, native-agent, and launcher operations.
- typed CLI presenter models are the input to CLI text rendering.
- `docs/architecture/gradle-module-split-evaluation.md` records the physical
  Gradle split decision and readiness rules.
- The Raw Map Boundary Rule (rule 11) and its Open-Boundary Allow-List are
  enforced by `RuntimeArchitectureTest.runtime architecture forbids raw map
  shapes outside the open-boundary allowlist` and
  `RuntimeArchitectureTest.open-boundary allow-list documents required
  exceptions` (the latter asserts ARCHITECTURE.md and the curated
  allow-list constant stay in sync). New exceptions MUST be added to both
  the allow-list constant and ARCHITECTURE.md in the same change.

## SKILL-52.2 — Runtime boundary closure inventory

This section classifies every current public raw-map declaration in
`runtime-application`, `runtime-domain`, and `runtime-ports` into one of
four SKILL-52.2 retirement categories. It is the planning ledger for the
remaining SKILL-52.2 subtasks (2–5).

The FQNs below are sourced from, and stay in strict-set parity with, the
canonical SKILL-52.1 Open-Boundary Allow-List declared above between the
`<!-- open-boundary-allowlist:start -->` / `<!-- open-boundary-allowlist:end -->`
markers and the `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` companion constant in
`runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`.
The architecture test
`SKILL-52.2 inventory classifies every public raw-map declaration exactly once`
parses this section and asserts that union(inventory FQNs) ==
union(findRawMapViolations ∪ findAnnotatedOpenBoundaryDeclarations) across
`runtime-application`, `runtime-domain`, and `runtime-ports`.

The subtask ids tagged below (subtask 2, subtask 3, subtask 4, subtask 5) refer
to SKILL-52.2 subtasks — they intentionally do NOT match the SKILL-52.1 subtask
numbering used inside the allow-list comments above.

Categories:

- `must_type_now` — public raw-map producer that MUST be replaced with a typed
  DTO during SKILL-52.2. Every entry carries its owning SKILL-52.2 subtask id.
- `open_extension` (`@OpenBoundaryMap`) — typed-DTO field/function intentionally
  modelled as an open boundary. The raw-map shape is the documented extension
  point and is guarded by the `@OpenBoundaryMap` annotation parity check.
- `private_serializer` — private/internal raw-map declaration that already
  lives behind a typed seam (serialization scratch space). These are NOT
  present in the SKILL-52.1 allow-list (which tracks only public surfaces) and
  therefore contribute no FQNs to this inventory; the category is retained as
  a planning slot so future audits can attach private serializer FQNs without
  reshaping the markers.
- `postponed_with_reason` — public raw-map surface whose retirement is
  deliberately deferred beyond SKILL-52.2 (workflow-engine snapshot codec,
  decomposition manifest codec/writer entrypoints, scaffold-policy
  pure-policy entrypoints). Every entry carries its owning SKILL-52.2 subtask
  id; the "reason" is the postponement note in the bullet.

<!-- skill-52-2-inventory:start -->

### must_type_now

- `skillbill.learnings.learningPayload` [subtask 5] — typed learnings
  payload DTO.
- `skillbill.learnings.learningSummaryPayload` [subtask 5] — typed
  learnings summary DTO.
- `skillbill.learnings.scopeCounts` [subtask 5] — typed learnings scope
  counts DTO.
- `skillbill.learnings.learningSessionJson` [subtask 5] — typed learnings
  session DTO.
- `skillbill.learnings.summarizeLearningReferences` [subtask 5] — typed
  learnings reference summary DTO.
- `skillbill.learnings.learningEntryPayload` [subtask 5] — typed learnings
  entry DTO.

### open_extension (@OpenBoundaryMap)

- `skillbill.workflow.WorkflowEngine.snapshotMap`
- `skillbill.workflow.WorkflowEngine.summaryMap`
- `skillbill.workflow.WorkflowEngine.resumeMap`
- `skillbill.workflow.WorkflowEngine.continueMap`
- `skillbill.workflow.WorkflowEngine.compactContinueMap`
- `skillbill.workflow.WorkflowEngine.updateAcknowledgementMap`
- `skillbill.workflow.model.WorkflowContinuationArtifactSummary.value`
- `skillbill.workflow.WorkflowSnapshotValidator.validate`
- `skillbill.install.model.InstallPlanWireValidator.validate`
- `skillbill.workflow.DecompositionManifestValidator.validate`
- `skillbill.workflow.DecompositionManifestValidator.validateYamlText`
- `skillbill.ports.workflow.DecompositionManifestFileStore.encodeManifestYaml`
- `skillbill.application.workflow.WorkflowFamily.sessionSummary`
- `skillbill.workflow.GoalObservabilityEventValidator.validate`
- `skillbill.workflow.model.GoalObservabilityEvent.toArtifactMap`
- `skillbill.workflow.model.GoalObservabilityEvent.toCompactSummaryMap`
- `skillbill.workflow.model.GoalObservabilityHistory.toArtifactList`
- `skillbill.workflow.model.goalObservabilityLatestEventFromArtifacts`
- `skillbill.workflow.model.goalObservabilityHistoryFromArtifacts`
- `skillbill.goalrunner.model.GoalRunnerStatusProjection.latestObservabilityEvent`
- `skillbill.goalrunner.model.GoalRunnerStatusProjectionExtras.latestObservabilityEvent`
- `skillbill.goalrunner.model.GoalRunnerStatusProjector.project`
- `skillbill.workflow.model.GoalProgressEvent.toArtifactMap`
- `skillbill.workflow.model.GoalProgressHistory.toArtifactList`
- `skillbill.workflow.GoalProgressEventValidator.validate`
- `skillbill.workflow.model.appendBoundedHistoryBySequence`
- `skillbill.goalrunner.model.GoalSessionAccounting.toArtifactMap`
- `skillbill.goalrunner.model.GoalSessionAccountingHistory.toArtifactList`
- `skillbill.goalrunner.model.GoalAttemptLedgerEntry.toArtifactMap`
- `skillbill.goalrunner.model.GoalAttemptLedger.toArtifactList`
- `skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator.validateAndReadPhaseOutput`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding.toArtifactMap`
- `skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.GoalSubtaskReviewPassResult.toArtifactMap`
- `skillbill.workflow.taskruntime.model.GoalSubtaskReviewPassResult.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.GoalSubtaskReviewArtifactDecoder.decode`
- `skillbill.workflow.taskruntime.model.GoalSubtaskReviewState.toArtifactMap`
- `skillbill.workflow.taskruntime.model.GoalSubtaskReviewState.fromArtifactMap`
- `skillbill.ports.workflow.model.GoalSubtaskReviewInput.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap`
- `skillbill.ports.goalrunner.GoalRunnerTerminalOutcomeStore.recoverMissingResultPrefixOutput`
- `skillbill.workflow.taskruntime.model.toArtifactMap`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimeRunInvariantsFromArtifactMap`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimeDecomposePlanOutcomeOrNull`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal.fromArtifactMap`
- `skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing.toArtifactMap`
- `skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap`
- `skillbill.application.model.WorkflowUpdateRequest.stepUpdates`
- `skillbill.application.model.WorkflowUpdateRequest.artifactsPatch`
- `skillbill.application.model.FeatureImplementFinishedRequest.childSteps`
- `skillbill.application.model.DecompositionManifestWriteRequest.planningResult`
- `skillbill.application.model.DecompositionManifestRuntimeUpdate.stepUpdates`
- `skillbill.application.model.DecompositionManifestRuntimeUpdate.artifactsPatch`
- `skillbill.application.model.DecompositionManifestRuntimeUpdate.existingArtifacts`
- `skillbill.install.model.buildInstallPlanWireMap`
- `skillbill.scaffold.model.PlatformManifest.customFields`
- `skillbill.telemetry.model.TelemetryConfigDocument.payload`
- `skillbill.telemetry.model.TelemetryProxyCapabilities.additionalFields`
- `skillbill.telemetry.model.TelemetryRemoteStatsResult.metrics`
- `skillbill.telemetry.model.FeatureImplementFinishedRecord.childSteps`
- `skillbill.workflow.model.WorkflowSnapshotView.artifacts`
- `skillbill.workflow.model.WorkflowContinueView.stepArtifacts`
- `skillbill.workflow.model.WorkflowContinueView.extraFields`
- `skillbill.workflow.model.WorkflowContinueView.sessionSummary`
- `skillbill.workflow.model.WorkflowUpdateInput.stepUpdates`
- `skillbill.workflow.model.WorkflowUpdateInput.artifactsPatch`
- `skillbill.ports.validation.model.RepoValidationReport.toPayload`
- `skillbill.ports.validation.model.ReleaseRefMetadata.toPayload`
- `skillbill.application.telemetry.lifecycleOkPayload`
- `skillbill.application.telemetry.lifecycleSkippedPayload`
- `skillbill.application.telemetry.lifecycleErrorPayload`
- `skillbill.application.telemetry.orchestratedStartedSkippedPayload`
- `skillbill.application.telemetry.orchestratedPayload`
- `skillbill.application.telemetry.LifecycleTelemetryService.featureImplementStarted`
- `skillbill.application.telemetry.LifecycleTelemetryService.featureImplementFinished`
- `skillbill.application.telemetry.LifecycleTelemetryService.featureTaskRuntimeStarted`
- `skillbill.application.telemetry.LifecycleTelemetryService.featureTaskRuntimeFinished`
- `skillbill.application.telemetry.LifecycleTelemetryService.qualityCheckStarted`
- `skillbill.application.telemetry.LifecycleTelemetryService.qualityCheckFinished`
- `skillbill.application.telemetry.LifecycleTelemetryService.featureVerifyStarted`
- `skillbill.application.telemetry.LifecycleTelemetryService.featureVerifyFinished`
- `skillbill.application.telemetry.LifecycleTelemetryService.prDescriptionGenerated`
- `skillbill.application.telemetry.LifecycleTelemetryService.goalStarted`
- `skillbill.application.telemetry.LifecycleTelemetryService.goalSubtaskFinished`
- `skillbill.application.telemetry.LifecycleTelemetryService.goalFinished`
- `skillbill.application.telemetry.LifecycleTelemetryService.goalIssueFinished`

### private_serializer

_None — the SKILL-52.1 allow-list tracks only public open-boundary surfaces.
Private/internal serializer scratch space (e.g. `WorkflowEngine.validatedSnapshotMap`,
`DecompositionManifestCodec` private extensions, `DecompositionManifestWriterSupport`
internals, `baseStatusPayload`, `telemetryMutationPayload`, internal helpers in
`TelemetryHttpRuntime` and `DefaultTelemetrySettingsProvider`) is intentionally
out-of-scope for the public-surface architecture scanner and therefore not
enumerated here. Future audits MAY attach private serializer FQNs to this
category without reshaping the marker block._

### postponed_with_reason

- `skillbill.workflow.WorkflowEngine.continueDecision` [subtask 4] —
  workflow-engine continue-decision raw-map `sessionSummary` parameter
  stays a wire-shape seam until the workflow-snapshot typed-DTO pass.
- `skillbill.workflow.DecompositionManifestCodec.decodeMap` [subtask 4] —
  decomposition manifest codec entrypoint; retired together with the
  workflow-snapshot typed-DTO pass.
- `skillbill.workflow.toWireMap` [subtask 4] — workflow wire-map encoder;
  retired together with the workflow-snapshot typed-DTO pass.
- `skillbill.application.decomposition.decodeDecompositionManifestMap` [subtask 4] —
  decomposition manifest decode entrypoint; postponed with the workflow
  family.
- `skillbill.application.decomposition.encodeDecompositionManifestMap` [subtask 4] —
  decomposition manifest encode entrypoint; postponed with the workflow
  family.
- `skillbill.application.decomposition.DecompositionManifestWriter.writeFromWorkflowUpdate`
  [subtask 4] — decomposition manifest writer entrypoint; postponed with
  the workflow family.
- `skillbill.application.decomposition.DecompositionManifestWriter.manifestFromWorkflowUpdate`
  [subtask 4] — decomposition manifest writer entrypoint; postponed with
  the workflow family.
- `skillbill.application.decomposition.DecompositionManifestWriter.maybeWriteFromWorkflowUpdate`
  [subtask 4] — decomposition manifest writer entrypoint; postponed with
  the workflow family.
<!-- skill-52-2-inventory:end -->
