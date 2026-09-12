# Skill Bill Runtime Architecture

This document defines the enforced architecture for `runtime-kotlin`.

## DB-first feature-task continuation

Feature-task continuation is repository-scoped and database-authoritative. At workflow creation, an immutable identity row binds the workflow id to a normalized issue key, canonical real-path Git-root identity, repository-relative governed spec path, persisted mode, and standalone/goal-child route scope. Read-only lookup never chooses among multiple eligible rows by timestamp.

The feature `spec.md` remains the governed product contract; it is not a mutable workflow ledger. A sibling `decomposition-manifest.yaml` is the sole prepared-feature authority marker and always contains one or more executable subtasks; a bare `spec.md` is preparation intake. Continuation lookup remains authoritative and precedes artifact discovery. Pre-planning, planning, phase outputs, and the phase ledger remain durable database artifacts. Initial implementation continuation is hydrated from the completed `plan`. Audit-gap remediation reuses the immutable original completed `preplan` and `plan` outputs and never loops back to either planning phase. A `gaps_found` audit must produce a versioned, complete repair plan. That normalized plan is persisted before the backward edge, and remediation reconciles its dependency-ordered repair items with an exact terminal result set. Subsequent audits retain recurring gap identities, distinguish genuinely new gaps, and block equivalent non-progress loops without storing prompts, diffs, source bodies, or raw tool output. Review remediation is a single bounded round: `review` runs once, `changes_requested` may launch one `implement_fix` pass (`review_fix` cap 1, then advance to `validate`), and review does not run again after that fix.

Decomposed goals execute discovery and preplan once at the parent, then persist a distinct immutable plan checkpoint for each ordered subtask. Normalized checkpoint tables are the continuation authority. Status reads only bounded fields: shared-preplan readiness, planned and total counts, first missing subtask, and a concise reason. Resume reuses compatible checkpoints; hard reset atomically invalidates planning and child continuation state.

Child creation hydrates the shared preplan and child's plan as completed dependencies with goal-planning provenance. They add no child execution duration, tokens, or agent attribution. Standalone feature-task workflows retain directly executed and attributed preplan and plan phases.

Runtime worker ownership is mutable state kept separately from immutable execution identity. A worker lease records a random owner token, monotonic fencing generation, host and boot identity, PID plus process-birth evidence, heartbeat/expiry, and the incomplete phase attempt. Every heartbeat, phase write, takeover reservation, transfer, and release must match both token and generation. Process liveness is exact only when host, boot, PID, and birth evidence agree; unverifiable or mismatched ownership must fail loudly instead of terminating a process or creating a replacement workflow. Confirmed takeover first reserves ownership with compare-and-set, then requests graceful shutdown and escalates only if the same process identity remains live.

A non-terminal row orphaned by a killed child process self-heals: when its worker lease has expired and the injected supervisor confirms the process dead (only `NotRunning`; a live process, an active lease, or ambiguous evidence is left untouched), the row transitions to the resumable `pending` state at its existing step and the lease is released under owner-token/generation fencing. The pass runs unconditionally at runner startup and in the goal parent's child-supervision seam, before the resume point is resolved, and is idempotent and concurrent-safe through the lease machinery. Manual lease clearing and out-of-band row deletion remain the corruption fallback only.

The runtime uses a hexagonal JVM graph with entry adapters at the outside,
application use cases in the orchestration layer, ports as the dependency
boundary, domain models and rules below the ports, and concrete infrastructure
behind those ports. `runtime-core` is only the composition root and runtime
metadata module.

```text
runtime-cli / runtime-mcp data gateways
  -> runtime-application use cases and the pinned runtime-engine inbound API
    -> runtime-ports
      -> runtime-domain models and rules
      -> runtime-contracts helpers for port-owned boundary payload contracts

runtime-engine
  -> runtime-application + runtime-ports + runtime-domain + runtime-contracts

runtime-infra-fs / runtime-infra-http / runtime-infra-sqlite
  -> runtime-ports + runtime-domain + runtime-contracts

runtime-core
  -> application services, engine services, ports, domain, and concrete adapters for DI wiring
```

## Gradle Modules

- `runtime-contracts`: contract DTOs, JSON/ordered-map helpers, runtime surface
  contracts, `*SchemaPaths` constants, `*_CONTRACT_VERSION` constants, and the
  `skillbill.error` runtime exception taxonomy. It no longer owns the JSON-Schema
  validators or their schema-resource copy tasks; those moved to
  `runtime-infra-fs` (see below). It also owns `skillbill.contracts.time.JvmSystemClock`,
  the single ambient wall-clock seam, which cannot live in `runtime-domain` because
  domain effect purity forbids `System.currentTimeMillis`.
- `runtime-domain`: pure agent-add-on, learning, review, telemetry, workflow,
  install-plan, scaffold, and skill-remove models/rules. Public domain data
  types live in area-owned `model` packages, including the
  `skillbill.model.FileLocation` value type that carries repo paths through domain
  and port signatures without a `java.nio` dependency.
- `runtime-ports`: `skillbill.model.RuntimeContext`, persistence sessions,
  repositories, gateway interfaces, telemetry port interfaces, workflow git
  operations, decomposition-manifest file-store ports, port-owned model types,
  the `skillbill.model.toPath` and `skillbill.ports.repository.toFileLocation` bridges that adapters use to turn
  a `FileLocation` into a `java.nio.file.Path`, and shared payload projection for
  boundary events that must be consumed by both application and infrastructure
  adapters.
- `runtime-application`: CLI/MCP/shared use cases outside the engine run loop,
  workflow orchestration, telemetry lifecycle orchestration,
  presenter-to-contract mapping, and validated decomposition-manifest file/artifact projection through workflow ports.
- `runtime-engine`: feature-task run loop, goal runner, goal planning, and
  planning projection use cases. It depends on `runtime-application` for the
  shared services those loops call today and exposes a pinned inbound API
  through `RuntimeComponent`.
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
  validators (`AgentAddonSchemaValidator`, `InstallPlanSchemaValidator`,
  `WorkflowStateSchemaValidator` /
  `CanonicalWorkflowStateSchemaValidator`, `DecompositionManifestSchemaValidator`,
  and the `DecompositionManifestCoherenceValidator`) plus their schema-resource
  copy tasks (`copyInstallPlanSchema`, `copyWorkflowStateSchema`,
  `copyDecompositionManifestSchema`), reached only through domain-neutral ports.
- `runtime-core`: Kotlin-Inject component definitions and DI
  providers. It may know concrete adapters only inside composition code.
  `runtime-core` publishes only the generated Kotlin-Inject ABI edges that its
  public `RuntimeComponent` exposes today: `runtime-application` service types,
  the pinned `runtime-engine` inbound API, and `runtime-ports` context/port types.
  It does not publish `runtime-domain`, `runtime-contracts`, or concrete
  infrastructure modules as API dependencies. Because those generated service,
  engine, and port types have their own public signatures, the transitive public
  ABI closure is currently runtime-application, runtime-engine, runtime-ports,
  runtime-domain, and runtime-contracts; that closure is tested and must not grow
  into infrastructure or entrypoint modules.
  Downstream entry adapters and tests still declare the modules they use
  directly instead of treating `runtime-core` as a broad dependency umbrella.
  If Kotlin-Inject ever requires another generated ABI edge to be public, the
  exact edge and generated type must be documented here and mirrored by an
  architecture test. SKILL-52.2 subtask 5 adds
  `RuntimeCoreCompositionOnlyTest` as a no-regression guard: the exact
  `api(project(...))` and `implementation(project(...))` edge sets on
  `runtime-core/build.gradle.kts` are pinned, and the test fails if any
  infrastructure (`runtime-infra-*`) or entrypoint (`runtime-cli`, `runtime-mcp`)
  module ever appears as `api(...)`.
- `runtime-cli`: Clikt command tree, option validation, terminal rendering,
  JSON output, help, completion surfaces, and CLI runtime context creation.
  SKILL-52.2 subtask 5 narrows the main-source project dependency allow-list to
  `runtime-application`, `runtime-contracts`, `runtime-core`, `runtime-domain`,
  and `runtime-ports`. `runtime-infra-fs` and `runtime-infra-http` are dropped
  — runtime-cli has no concrete `skillbill.infrastructure.*` imports outside
  test sources; the infrastructure adapters are resolved through
  `RuntimeComponent` (kotlin-inject). The allow-list is enforced by
  `RuntimeAdapterDependencyAllowlistTest`.
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
runtime-engine
runtime-infra-fs
runtime-infra-http
runtime-infra-sqlite
runtime-cli
runtime-mcp
runtime-ports
```

## Package Ownership

- `skillbill`: runtime metadata that is safe for all runtime modules to read.
- `skillbill.di`: Kotlin-Inject composition root, owned by `runtime-core`.
  `RuntimeComponent` mixes in one `Runtime<Area>Provides` interface per area
  (install, telemetry, goal planning, goal runner, review, feature task,
  workflow, validators, scaffold, diagnostics); an area with more than ten
  provides splits by its own sub-area (`RuntimeFeatureSpecProvides`,
  `RuntimeReviewAddonCatalogProvides`, `RuntimeScaffoldValidationProvides`,
  `RuntimeGoalPlanningSweepProvides`), never by pairing two areas. Each
  `@Provides` is declared once, and `RuntimeBootstrapBindings` holds only the
  ambient construction seam.
- `skillbill.application`: use cases, workflow orchestration, lifecycle
  telemetry orchestration, repository-port coordination, and application-owned
  mappers. Public inputs and results live in area-owned `skillbill.application.<area>.model` packages.
- area-owned `skillbill.application.<area>.model` packages: public application input/result models.
- `skillbill.model`: shared runtime model types that are not owned by a
  narrower area: `RuntimeContext`, `EnvironmentContext`, `TransportContext`,
  `WorkflowOpsContext`, `OptionalCallbacks`, and `RepositoryRoot`.
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
  application or adapter-owned packages. This package spans two modules: the
  DTOs, helpers, and constants compile in `runtime-contracts`, and the schema
  validator classes compile into `runtime-infra-fs` under
  `skillbill.infrastructure.fs.contracts` and its subpackages
  (`SchemaValidatorLocale`, `install.InstallPlanSchemaValidator`,
  `review.ReviewContextSchemaValidator` and `ReviewContextSchemaLocator`, and
  the workflow, feature-task, goal, and decomposition schema validators plus
  validator-only helpers such as `IssueKeySchemaRefInlining`). Phase-output
  structural repair and strict parsing live in `skillbill.infrastructure.fs.phaseoutput`,
  not under `skillbill.infrastructure.fs.contracts`, because they are
  adapter-owned parse/repair engines rather than schema validators.
- `skillbill.error`: runtime exception taxonomy.
- `skillbill.agent.model`: phase handoff string envelopes for agent phase input and output owned by `runtime-domain`.
- `skillbill.agentaddon` and `skillbill.agentaddon.model`: governed agent-add-on
  filesystem discovery and schema validation owned by `runtime-infra-fs`, plus
  typed declaration models owned by `runtime-domain`.
- `skillbill.workflow.engine` and `skillbill.workflow.engine.model`: workflow
  engine, snapshot codec, continuation assembly, and engine models owned by
  `runtime-domain`.
- `skillbill.workflow.decomposition` and
  `skillbill.workflow.decomposition.model`: decomposition manifest codec,
  wire-map conversion, and decomposition models owned by `runtime-domain`.
- `skillbill.workflow.goal` and `skillbill.workflow.goal.model`: goal
  observability, progress events, subtask review artifacts, and goal models
  owned by `runtime-domain`.
- `skillbill.workflow.taskruntime` and
  `skillbill.workflow.taskruntime.model`: feature-task runtime phase workflow,
  handoff projections, phase records, and taskruntime models owned by
  `runtime-domain`.
- `skillbill.workflow.idestatus`: IDE status validation owned by
  `runtime-domain`.
- `skillbill.workflow.specsource`: spec-source reading owned by
  `runtime-domain`.
- `skillbill.workflow.verify`: Feature Verify workflow definition
  (`FeatureVerifyWorkflowDefinition`) owned by `runtime-domain`.
- `skillbill.goalrunner` and `skillbill.goalrunner.model`: pure goal-runner
  liveness policy, worker-subtask parsing, status projection, accounting, and
  attempt-ledger models owned by `runtime-domain`.
- `skillbill.idestatus` and `skillbill.idestatus.model`: agent activity label
  and stamp types for IDE status presentation owned by `runtime-domain`.
- `skillbill.engine`: feature-task run loop, goal runner, goal planning, and
  planning projection use cases owned by `runtime-engine`.
- `skillbill.infrastructure.fs.goalplanning`: filesystem discovery of shared
  repository and validation context owned by `runtime-infra-fs`, plus
  headings-first boundary memory: a programmatic parse of governed
  `## [<date>] <title>` entries into a bounded heading catalog (no model call in
  the indexer), and a separate body resolver that materializes entry bodies only
  for the heading ids preplanning selected.
- `skillbill.featurespec` and `skillbill.featurespec.model`: feature-spec
  preparation policy and typed preparation/write models owned by
  `runtime-domain`.
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
- `skillbill.telemetry.model`: telemetry settings normalization and lifecycle
  telemetry records owned by `runtime-domain`.
- `skillbill.application.telemetry`: telemetry sync orchestration, config
  mutation rules, and port-backed runtime surfaces owned by `runtime-application`.
- `skillbill.text`: UTF-8 truncation and size helpers owned by `runtime-domain`.
- `skillbill.infrastructure.fs`: filesystem gateways for repo validation,
  install, scaffold, native-agent, launcher, telemetry config, git workflow,
  review input loading, decomposition-manifest file storage, and skill-remove
  ports.
- `skillbill.infrastructure.http`: HTTP telemetry client and telemetry proxy
  payload mapping.
- `skillbill.infrastructure.sqlite`: SQLite session factory, schema, migrations,
  SQL statement bindings, repositories, review stores, stats, and telemetry
  outbox persistence owned by `runtime-infra-sqlite`.
- `skillbill.cli`: CLI adapter code. It validates CLI input, formats terminal
  output, maps typed results to contract payloads, and delegates behavior to
  application services or ports.
- `skillbill.mcp`: MCP adapter code. It validates MCP input, shapes MCP
  payloads, owns MCP-specific schema seams, and delegates shared behavior to
  application services or ports.

## Boundary Rules

1. CLI and MCP data gateways are entry adapters. They validate and
   translate input, then delegate to application use cases or ports.
2. Application owns workflow and use-case orchestration. It must not depend on
   Clikt, Compose, MCP adapter types, JDBC, Java HTTP clients, or concrete
   infrastructure packages.
3. Domain packages must not depend on CLI, MCP, JDBC, Java HTTP
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
    (`toReviewFinishedTelemetryPayload` plus its private nested
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
    mappers (`runtime-cli` `ScaffoldCliResultMappers`).

    <!-- open-boundary-allowlist:start -->

    - `skillbill.application.decomposition.baseBranch`
    - `skillbill.application.decomposition.executionModel`
    - `skillbill.application.decomposition.parentSpecPath`
    - `skillbill.application.decomposition.parseStackBranches`
    - `skillbill.application.decomposition.parseSubtasks`
    - `skillbill.application.decomposition.specSource`
    - `skillbill.engine.featuretask.CompletedImplementationOutputArgs.outputMap`
    - `skillbill.engine.featuretask.CompletionProjectionRejectionArgs.outputMap`
    - `skillbill.engine.featuretask.FeatureTaskPhaseSettlementService.auditSettle`
    - `skillbill.engine.featuretask.FeatureTaskPhaseSettlementService.block`
    - `skillbill.engine.featuretask.FeatureTaskPhaseSettlementService.complete`
    - `skillbill.engine.featuretask.FeatureTaskPhaseSettlementService.findEnvelope`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeGoalContinuationArtifactPatcher.save`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeOutputVerification.auditProseValue`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeOutputVerification.dispositionsFrom`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeOutputVerification.rejectedFindingDispositions`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeOutputVerification.verdictFor`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeOutputVerification.verifiedFindingDispositions`
    - `skillbill.engine.featuretask.FeatureTaskRuntimePhaseReviewGenerationApi.recordedFindingVerdicts`
    - `skillbill.engine.featuretask.FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeReviewEnvelope.envelopeMap`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopAttemptSettlement.settleValidatedOutputBoundary`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopAttemptSettlement.rejectValidatedOutput`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopCheckpointRemediation.completedImplementFixProducedOutputs`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopDrive.completeReservedGoalReviewPass`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopLaunch.outputEnvelopeOf`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputPersistence.persistRejectedVerificationFindings`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.firstValidatedOutputRejection`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.auditGapProgressPause`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.findingVerificationBoundaryBodyDeliveryDecision`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.findingVerificationBoundaryDispositionGate`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.findingVerificationBoundaryDispositionGateImpl`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.outputVerificationGateReason`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.verifyFindingsBoundaryContext`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.verifyFindingsDispositionGateContext`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopRecordRejection.payloadFreeSemanticGateConstraint`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopRecordRejection.scrubResponseDerivedGateDetail`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopRepairReceipt.implementFixRepairReceiptSettlement`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopRepairReceipt.repairReceiptShapeSettlement`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopSubtaskCommit.revalidated`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopValidationGate.gateTriageCapturedProducedOutputs`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopValidationGate.looseOutputEnvelope`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunState.parsedOutputsByPayload`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeSubtaskFinalisation.readHandoff`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeSubtaskFinalisation.withCommitSha`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeSubtaskFinalisationHandoff.readHandoff`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeSubtaskFinalisationHandoff.withCommitSha`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeVerificationGateReasons.findingVerificationDisposition`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeVerificationGateReasons.reviewVerificationSignal`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeWorkflowPersistence.persistPatch`
    - `skillbill.engine.featuretask.GoalReviewPassCompletionRequest.normalizedOutput`
    - `skillbill.engine.featuretask.ImplementFixRepairReceiptArgs.outputMap`
    - `skillbill.engine.featuretask.SettleValidatedOutputAfterFingerprintArgs.outputMap`
    - `skillbill.engine.featuretask.SettleValidatedOutputPauseArgs.outputMap`
    - `skillbill.engine.featuretask.TerminalOutputAttemptArgs.outputMap`
    - `skillbill.engine.featuretask.WorkflowRowAdvance.stepUpdates`
    - `skillbill.engine.featuretask.checkpointIdentitiesFrom`
    - `skillbill.engine.featuretask.continuationFromArtifacts`
    - `skillbill.engine.featuretask.continuationPatch`
    - `skillbill.engine.featuretask.decodeStrictKeyedArtifactMap`
    - `skillbill.engine.featuretask.decomposeTerminalFrom`
    - `skillbill.engine.featuretask.deliveredProjectionHistoryFrom`
    - `skillbill.engine.featuretask.deliveredProjectionsFrom`
    - `skillbill.engine.featuretask.featureSizeFromArtifacts`
    - `skillbill.engine.featuretask.featureTaskRuntimeParseRepairReceipt`
    - `skillbill.engine.featuretask.featureTaskRuntimeParseRepairReceiptOrNull`
    - `skillbill.engine.featuretask.featureTaskRuntimeRepairReceiptShapeRejection`
    - `skillbill.engine.featuretask.findingVerificationCheckpointPatch`
    - `skillbill.engine.featuretask.goalContinuationFieldAdoptionFrom`
    - `skillbill.engine.featuretask.implementationAttemptPatch`
    - `skillbill.engine.featuretask.implementationAttemptsFrom`
    - `skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap`
    - `skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing.toArtifactMap`
    - `skillbill.engine.featuretask.mutatingReconciliationGateReason`
    - `skillbill.engine.featuretask.operatorBlockRetryFrom`
    - `skillbill.engine.featuretask.FeatureTaskRuntimeRunState.parsedOutput`
    - `skillbill.engine.featuretask.phaseBriefingsFrom`
    - `skillbill.engine.featuretask.phaseLedgerFrom`
    - `skillbill.engine.featuretask.phaseRecordsFrom`
    - `skillbill.engine.featuretask.producerProjectionGateReason`
    - `skillbill.engine.featuretask.quarantineEntriesFrom`
    - `skillbill.engine.featuretask.rawReviewResultsFromArtifacts`
    - `skillbill.engine.featuretask.recordProjectionMeasurements`
    - `skillbill.engine.featuretask.remediationBaseRecoveryEvidenceEntry`
    - `skillbill.engine.featuretask.requireValidPlanningProjection`
    - `skillbill.engine.featuretask.resolvedBranchFrom`
    - `skillbill.engine.featuretask.reviewGenerationFrom`
    - `skillbill.engine.featuretask.reviewStateFromArtifacts`
    - `skillbill.engine.featuretask.reviewStatePatch`
    - `skillbill.engine.featuretask.stepUpdatesFrom`
    - `skillbill.engine.featuretask.terminalBlockedReasonFrom`
    - `skillbill.engine.featuretask.validateEnvelopeWire`
    - `skillbill.engine.featuretask.validatePersistenceWire`
    - `skillbill.engine.goalplanning.toEnvelopeMap`
    - `skillbill.engine.goalrunner.GoalRunnerChildRepairWedgeApplyLoop.ApplyState.artifacts`
    - `skillbill.engine.goalrunner.GoalRunnerChildRepairWedgeApplyLoop.ApplyState.evidenceEntries`
    - `skillbill.engine.goalrunner.GoalRunnerChildRepairWedgeApplyLoop.ApplyState.patch`
    - `skillbill.engine.goalrunner.GoalRunnerMissingResultPrefixCandidate.output`
    - `skillbill.engine.goalrunner.GoalRunnerStaleBlockedOutcomeContext.artifacts`
    - `skillbill.engine.goalrunner.childRepairWedgeEvidenceMap`
    - `skillbill.engine.goalrunner.continuationArtifactFromMap`
    - `skillbill.engine.goalrunner.planning.GoalPlanningContextPromptFormatter.append`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContext.planningPacket`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.catalog`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.catalogHeadingIds`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.digest`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.discardedCatalog`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.emptyCatalog`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.includedSubtaskIds`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.migrate`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.orderedSubtasks`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.validate`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacketLegacy.migrateFromPacketVersion1`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacketLegacy.migrateFromPacketVersion2`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacketLegacy.migrateFromPacketVersion3`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacketValidation.digest`
    - `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacketValidation.normalizedSubtasks`
    - `skillbill.engine.goalrunner.planning.enrichPreplan`
    - `skillbill.engine.goalrunner.planning.freshPlanningPacket`
    - `skillbill.engine.goalrunner.planning.gatherSharedContext`
    - `skillbill.engine.goalrunner.planning.planningPacketFrom`
    - `skillbill.engine.goalrunner.planning.unsuccessfulStatusReason`
    - `skillbill.engine.goalrunner.terminalJsonObjectWithoutResultPrefix`
    - `skillbill.engine.goalrunner.toStatusMap`
    - `skillbill.application.idestatus.model.IdeStatusProblem.details`
    - `skillbill.application.idestatus.model.IdeStatusSnapshot.toStatusWireMap`
    - `skillbill.engine.planningprojection.producerProjectionGateReason`
    - `skillbill.engine.planningprojection.requireValidPlanningProjection`
    - `skillbill.application.review.model.ReviewContextEnvelope.asWireMap`
    - `skillbill.application.review.toBoundedPayload`
    - `skillbill.application.review.toProjectionPayload`
    - `skillbill.ports.review.model.GovernedReviewEvidenceCodec.TOOL_SPECS`
    - `skillbill.ports.review.model.GovernedReviewEvidenceCodec.discoveryRequest`
    - `skillbill.ports.review.model.GovernedReviewEvidenceCodec.expansionRequest`
    - `skillbill.ports.review.model.GovernedReviewEvidenceCodec.payload`
    - `skillbill.ports.review.model.GovernedReviewEvidenceCodec.readRequest`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewOutcomeDispositionReduction.blockerDispositions`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse.citationDiagnostics`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse.parseStructuredFindings`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse.recordedVerdicts`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse.reviewRunIdOf`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse.structuredFindings`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.blockerDispositions`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.commitFocusedAccounting`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.evidenceCoverageComplete`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.fromOutput`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.outcomeFor`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.rejectedVerificationFindings`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.unaddressedFindings`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.unresolvedCount`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummarySanitize.labelFor`
    - `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewVerificationRejection.rejectedVerificationFindings`
    - `skillbill.goalrunner.subtaskreview.recordedVerdicts`
    - `skillbill.goalrunner.subtaskreview.reviewPassVerdict`
    - `skillbill.goalrunner.subtaskreview.reviewRunIdOf`
    - `skillbill.goalrunner.subtaskreview.structuredFindings`
    - `skillbill.application.telemetry.LifecycleTelemetryService.featureTaskRuntimeFinished`
    - `skillbill.application.telemetry.LifecycleTelemetryService.featureTaskRuntimeStarted`
    - `skillbill.application.telemetry.LifecycleTelemetryService.featureVerifyFinished`
    - `skillbill.application.telemetry.LifecycleTelemetryService.featureVerifyStarted`
    - `skillbill.application.telemetry.LifecycleTelemetryService.goalFinished`
    - `skillbill.application.telemetry.LifecycleTelemetryService.goalIssueFinished`
    - `skillbill.application.telemetry.LifecycleTelemetryService.goalStarted`
    - `skillbill.application.telemetry.LifecycleTelemetryService.goalSubtaskFinished`
    - `skillbill.application.telemetry.LifecycleTelemetryService.prDescriptionGenerated`
    - `skillbill.application.telemetry.LifecycleTelemetryService.qualityCheckFinished`
    - `skillbill.application.telemetry.LifecycleTelemetryService.qualityCheckStarted`
    - `skillbill.application.telemetry.lifecycleErrorPayload`
    - `skillbill.application.telemetry.lifecycleOkPayload`
    - `skillbill.application.telemetry.lifecycleSkippedPayload`
    - `skillbill.application.telemetry.orchestratedPayload`
    - `skillbill.application.telemetry.orchestratedStartedSkippedPayload`
    - `skillbill.application.workflow.FeatureTaskRuntimePhaseLedgerDecoder.decode`
    - `skillbill.application.workflow.decodeFeatureTaskRuntimePhaseRecords`
    - `skillbill.application.workflow.decodeWorkflowArtifacts`
    - `skillbill.application.workflow.model.WorkflowUpdateRequest.artifactsPatch`
    - `skillbill.application.workflow.model.WorkflowUpdateRequest.stepUpdates`
    - `skillbill.application.workflow.parentProjectionArtifacts`
    - `skillbill.application.workflow.subtaskStartArtifacts`
    - `skillbill.application.workflow.updateGoalParentForBlockedPhaseRetry`
    - `skillbill.goalrunner.model.GoalAttemptLedger.toArtifactList`
    - `skillbill.goalrunner.model.GoalAttemptLedgerEntry.toArtifactMap`
    - `skillbill.goalrunner.model.GoalRunnerStatusProjection.latestObservabilityEvent`
    - `skillbill.goalrunner.model.GoalRunnerStatusProjectionRuntimeInputs.latestObservabilityEvent`
    - `skillbill.goalrunner.model.GoalRunnerStatusProjector.project`
    - `skillbill.install.model.InstallPlanWireValidator.validate`
    - `skillbill.install.model.buildInstallPlanWireMap`
    - `skillbill.learnings.learningEntryPayload`
    - `skillbill.learnings.learningPayload`
    - `skillbill.learnings.learningSessionJson`
    - `skillbill.learnings.learningSummaryPayload`
    - `skillbill.learnings.scopeCounts`
    - `skillbill.learnings.summarizeLearningReferences`
    - `skillbill.goalrunner.backwardEdgeCountsFromLedger`
    - `skillbill.goalrunner.blockedReasonFrom`
    - `skillbill.goalrunner.commitShaFrom`
    - `skillbill.goalrunner.declaredProgressEventFrom`
    - `skillbill.goalrunner.derivedTerminalOutcomeFor`
    - `skillbill.goalrunner.goalContinuationOutcome`
    - `skillbill.ports.goalrunner.persistence.model.GoalChildPlanningHydrationResult.artifacts`
    - `skillbill.ports.goalrunner.persistence.model.GoalChildPlanningHydrationResult.stepUpdates`
    - `skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyStateInit.artifacts`
    - `skillbill.ports.goalrunner.persistence.model.HistoryArtifactAppend.entryMap`
    - `skillbill.goalrunner.progressEventFrom`
    - `skillbill.goalrunner.terminalOutcomeFor`
    - `skillbill.goalrunner.toArtifactMap`
    - `skillbill.goalrunner.toArtifactsMap`
    - `skillbill.ports.goalrunner.runner.GoalRunnerTerminalOutcomeStore.recoverMissingResultPrefixOutput`
    - `skillbill.ports.goalrunner.runner.GoalRunnerWorkflowProgressStore.progressEvents`
    - `skillbill.workflow.taskruntime.phaseartifacts.decodeStrictKeyedArtifactMap`
    - `skillbill.workflow.taskruntime.phaseartifacts.decomposeTerminalFrom`
    - `skillbill.workflow.taskruntime.phaseartifacts.goalContinuationFieldAdoptionFrom`
    - `skillbill.workflow.taskruntime.phaseartifacts.operatorBlockRetryFrom`
    - `skillbill.workflow.taskruntime.phaseartifacts.phaseLedgerFrom`
    - `skillbill.workflow.taskruntime.phaseartifacts.phaseRecordsFrom`
    - `skillbill.workflow.taskruntime.phaseartifacts.resolvedBranchFrom`
    - `skillbill.workflow.taskruntime.phaseartifacts.reviewGenerationFrom`
- `skillbill.ports.review.model.GovernedReviewEvidenceCodec.TOOL_SPECS`
- `skillbill.ports.review.model.GovernedReviewEvidenceCodec.discoveryRequest`
- `skillbill.ports.review.model.GovernedReviewEvidenceCodec.expansionRequest`
- `skillbill.ports.review.model.GovernedReviewEvidenceCodec.payload`
- `skillbill.ports.review.model.GovernedReviewEvidenceCodec.readRequest`
    - `skillbill.ports.review.model.ReviewAccountingRecord.boundedPayload`
    - `skillbill.ports.validation.model.ReleaseRefMetadata.toPayload`
    - `skillbill.ports.validation.model.RepoValidationReport.toPayload`
    - `skillbill.ports.workflow.decomposition.DecompositionManifestPersistencePort.encodeManifestYaml`
    - `skillbill.application.decomposition.DecompositionManifestWriter.manifestFromWorkflowUpdate`
    - `skillbill.application.decomposition.DecompositionManifestWriter.maybeWriteFromWorkflowUpdate`
    - `skillbill.application.decomposition.DecompositionManifestWriter.writeFromWorkflowUpdate`
    - `skillbill.workflow.decomposition.runtime.decodeArtifactKeys`
    - `skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestRuntimeUpdate.artifactsPatch`
    - `skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestRuntimeUpdate.existingArtifacts`
    - `skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestRuntimeUpdate.stepUpdates`
    - `skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestWorkflowProjectionInput.artifactsPatch`
    - `skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestWriteRequest.planningResult`
    - `skillbill.ports.workflow.decomposition.runtime.model.DecompositionPlanManifestInput.artifactsPatch`
    - `skillbill.ports.workflow.decomposition.runtime.model.DecompositionPlanManifestInput.existingArtifacts`
    - `skillbill.ports.workflow.decomposition.runtime.model.DecompositionPlanManifestInput.plan`
    - `skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput.toArtifactMap`
    - `skillbill.goalrunner.GoalObservabilityArtifacts.patchForProgressEvent`
    - `skillbill.goalrunner.GoalObservabilityArtifacts.patchForRuntimeEvent`
    - `skillbill.goalrunner.model.GoalObservabilityProgressInput.artifacts`
    - `skillbill.goalrunner.model.GoalObservabilityRuntimeEventInput.artifacts`
    - `skillbill.ports.workflow.sessionSummary`
    - `skillbill.ports.workflow.model.toPayload`
    - `skillbill.review.context.ReviewContextEnvelopeValidator.validate`
    - `skillbill.review.context.ReviewContextEnvelopeValidator.validateSpecIntentProjection`
    - `skillbill.scaffold.model.PlatformManifest.customFields`
    - `skillbill.telemetry.model.TelemetryConfigDocument.payload`
    - `skillbill.telemetry.model.TelemetryProxyCapabilities.additionalFields`
    - `skillbill.telemetry.model.TelemetryRemoteStatsResult.metrics`
    - `skillbill.workflow.decomposition.DecompositionManifestCodec.decodeMap`
    - `skillbill.workflow.decomposition.DecompositionManifestValidator.validate`
    - `skillbill.workflow.decomposition.DecompositionManifestValidator.validateYamlText`
    - `skillbill.workflow.decomposition.toWireMap`
    - `skillbill.application.workflow.WorkflowWireProjections.compactContinueMap`
    - `skillbill.workflow.engine.WorkflowEngine.continueDecision`
    - `skillbill.application.workflow.WorkflowWireProjections.continueMap`
    - `skillbill.application.workflow.WorkflowWireProjections.inputProjectionMap`
    - `skillbill.application.workflow.WorkflowWireProjections.resumeMap`
    - `skillbill.application.workflow.WorkflowWireProjections.snapshotMap`
    - `skillbill.application.workflow.WorkflowWireProjections.summaryMap`
    - `skillbill.application.workflow.WorkflowWireProjections.updateAcknowledgementMap`
    - `skillbill.workflow.engine.model.WorkflowContinuationArtifactSummary.value`
    - `skillbill.workflow.engine.model.WorkflowContinueView.extraFields`
    - `skillbill.workflow.engine.model.WorkflowContinueView.sessionSummary`
    - `skillbill.workflow.engine.model.WorkflowContinueView.stepArtifacts`
    - `skillbill.workflow.engine.model.WorkflowInputProjection.artifacts`
    - `skillbill.workflow.engine.model.WorkflowSnapshotView.artifacts`
    - `skillbill.workflow.engine.model.WorkflowUpdateInput.artifactsPatch`
    - `skillbill.workflow.engine.model.WorkflowUpdateInput.stepUpdates`
    - `skillbill.workflow.goal.GoalObservabilityEventValidator.validate`
    - `skillbill.workflow.goal.GoalPlanningPreparationEnvelopeValidator.validate`
    - `skillbill.workflow.goal.GoalProgressEventValidator.validate`
    - `skillbill.workflow.goal.model.GoalObservabilityEvent.toArtifactMap`
    - `skillbill.workflow.goal.model.GoalObservabilityEvent.toCompactSummaryMap`
    - `skillbill.workflow.goal.model.GoalObservabilityHistory.toArtifactList`
    - `skillbill.workflow.goal.model.GoalProgressEvent.toArtifactMap`
    - `skillbill.workflow.goal.model.GoalProgressHistory.toArtifactList`
    - `skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition.fromArtifactMap`
    - `skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition.toArtifactMap`
    - `skillbill.workflow.goal.model.GoalSubtaskCommitFocusedAccounting.fromArtifactMap`
    - `skillbill.workflow.goal.model.GoalSubtaskCommitFocusedAccounting.toArtifactMap`
    - `skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder.decode`
    - `skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder.decodeContinuationOnly`
    - `skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly`
    - `skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding.fromArtifactMap`
    - `skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding.toArtifactMap`
    - `skillbill.workflow.goal.model.GoalSubtaskReviewPassResult.fromArtifactMap`
    - `skillbill.workflow.goal.model.GoalSubtaskReviewPassResult.toArtifactMap`
    - `skillbill.workflow.goal.model.GoalSubtaskReviewState.boundedDispositionSummary`
    - `skillbill.workflow.goal.model.GoalSubtaskReviewState.fromArtifactMap`
    - `skillbill.workflow.goal.model.GoalSubtaskReviewState.toArtifactMap`
    - `skillbill.workflow.goal.model.appendBoundedHistoryBySequence`
    - `skillbill.workflow.goal.model.goalObservabilityHistoryFromArtifacts`
    - `skillbill.workflow.goal.model.goalObservabilityLatestEventFromArtifacts`
    - `skillbill.workflow.goal.model.goalObservabilityLatestEventForLiveness`
    - `skillbill.ports.idestatus.IdeStatusValidator.validate`
    - `skillbill.workflow.taskruntime.FeatureTaskRuntimeBuildReceiptValidator.validateBuildReceipt`
    - `skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator.validateEnvelope`
    - `skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator.validateDeclaration`
    - `skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator.validateMeasurement`
    - `skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator.validatePersistenceRecord`
    - `skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator.validateSharedEvidenceProjection`
    - `skillbill.workflow.taskruntime.FeatureTaskRuntimeImplementationAttemptValidator.validateImplementationAttemptRecord`
    - `skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator.validateAndReadPhaseOutput`
    - `skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator.validatePlanningProjection`
    - `skillbill.workflow.taskruntime.FeatureTaskRuntimeQuarantineValidator.validateQuarantineRecord`
    - `skillbill.workflow.taskruntime.ProsePhaseOutputSynthesizer.envelopeFromSettlement`
    - `skillbill.workflow.taskruntime.ProsePhaseOutputSynthesizer.trySynthesize`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticDegradationMeasurement.toTelemetryMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticSignal.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticSignal.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationFieldAdoption.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationFieldAdoption.toArtifactMap`
    - `skillbill.goalrunner.model.FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap`
    - `skillbill.goalrunner.model.FeatureTaskRuntimeGoalContinuationOutcome.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalPlanningImport.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope.fromEnvelopeMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope.toEnvelopeMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjection.toEnvelopeMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef.toDeclarationMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory.fromMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionMeasurement.toTelemetryMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptCheckpoint.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptCheckpoint.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptDeviation.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptDeviation.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptReconciliation.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptReconciliation.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRejectionMeasurement.toTelemetryMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairConstruct.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairConstruct.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairDisturbedRemedy.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairDisturbedRemedy.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedgerEntry.toProjectionMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedgerProjection.toProjectionMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt.validateEntries`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceiptEntry.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint.toEnvelopeMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement.toTelemetryMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRunRecord.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput.envelope`
    - `skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration.fromArtifactMap`
    - `skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration.toArtifactMap`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesFromArtifact`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesToArtifact`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimeDecomposePlanOutcomeOrNull`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimeDiagnosticSignalsFromWire`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptRecordToWire`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptsFromWire`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimeIsDecompositionPackage`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimePlanningProjectionFromEnvelope`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineEntriesFromWire`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineRecordToWire`
    - `skillbill.workflow.taskruntime.model.featureTaskRuntimeRunInvariantsFromArtifactMap`
    - `skillbill.workflow.taskruntime.model.toArtifactMap`

        - `skillbill.application.decomposition.decodeArtifacts`
    - `skillbill.application.decomposition.decodeDecompositionManifestMap`
    - `skillbill.application.decomposition.encodeDecompositionManifestMap`
    - `skillbill.application.decomposition.manifestPathFromArtifacts`
    - `skillbill.engine.goalrunner.GoalParentProjectionWriter.artifacts`
    - `skillbill.engine.goalrunner.goalContinuation`
    - `skillbill.engine.goalrunner.goalReviewArtifacts`
    - `skillbill.engine.goalrunner.goalReviewEmissionEnvelope`
    - `skillbill.engine.goalrunner.maxHistorySequence`
    - `skillbill.engine.goalrunner.missingResultPrefixTerminalOutcomeArtifact`
    - `skillbill.engine.goalrunner.planning.model.GoalChildPlanningHydration.artifacts`
    - `skillbill.engine.goalrunner.planning.model.GoalChildPlanningHydration.stepUpdates`
    - `skillbill.engine.goalrunner.toArtifactMap`
    - `skillbill.engine.goalrunner.toArtifactsMap`
    - `skillbill.application.workflow.outOfBandAcceptancesFromLegacyArtifacts`
    - `skillbill.application.workflow.reviewPolicyFromLegacyArtifacts`
<!-- open-boundary-allowlist:end -->

    The allow-list grandfathers legacy raw-map surfaces. The rule
    applies prospectively: new public declarations cannot join the
    legacy raw-map surface without being added to both the allow-list
    constant and this section in the same change.
    Inner-layer test sources in `runtime-application`, `runtime-domain`, and
    `runtime-ports` are also part of this boundary: their `src/test/kotlin`,
    `src/jvmTest/kotlin`, and `src/commonTest/kotlin` roots must not import
    `skillbill.infrastructure.*`, `skillbill.cli.*`, or `skillbill.mcp.*`.
    Adapter and infrastructure test trees are outside that inner-layer scan.
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
    `schema_migrations`, keyed by migration name. Version numbers order the list
    but do not identify a migration: branches assign them independently, so two
    lineages can ship different migrations under the same number.

The subsystem package set is:

```text
skillbill.agent.model
skillbill.agentaddon
skillbill.application
skillbill.boundary
skillbill.cli
skillbill.config
skillbill.contracts
skillbill.di
skillbill.domain.skillremove
skillbill.engine
skillbill.error
skillbill.featurespec
skillbill.goalrunner
skillbill.idestatus
skillbill.install
skillbill.infrastructure
skillbill.learnings
skillbill.mcp
skillbill.model
skillbill.ports
skillbill.review
skillbill.scaffold
skillbill.telemetry
skillbill.text
skillbill.workflow
skillbill.workflow.verify
```

## Feature-Task Workflow Family

- `bill-feature-task` is the public workflow identity for the runtime-backed
  feature-task engine. Feature-verify remains a distinct workflow family and
  store.
- The Kotlin runtime definition is
  `skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition`
  (id prefix `wftr`, contract version `FEATURE_TASK_RUNTIME_CONTRACT_VERSION`);
  persisted rows use `workflow_name=bill-feature-task` and `mode=runtime`.
- `skill-bill feature-task` and `feature-task-stats` are the CLI surfaces for
  this workflow family.

## Runtime Contract And Schema Seams

- Runtime contract schemas live in `orchestration/contracts/`. The
  `*SchemaPaths` constants and `*_CONTRACT_VERSION` constants stay in
  `runtime-contracts`. The JVM JSON-Schema validators, their typed schema
  errors, and their classpath-resource copy tasks live in `runtime-infra-fs`,
  reached only through the domain-neutral ports `InstallPlanWireValidator`,
  `DecompositionManifestValidator`, and `WorkflowSnapshotValidator`.
- Workflow-state schema validation is owned by
  `skillbill.infrastructure.fs.contracts.workflow.WorkflowStateSchemaValidator` (its default
  implementation `CanonicalWorkflowStateSchemaValidator`), compiled into
  `runtime-infra-fs`. The runtime-domain workflow engine MUST NOT import that
  validator directly — instead it depends on the domain-owned port
  `skillbill.workflow.engine.WorkflowSnapshotValidator`, which the composition root
  wires to the infra adapter
  `skillbill.infrastructure.fs.WorkflowSnapshotValidatorInfraAdapter`. The
  port takes the typed `skillbill.workflow.engine.model.WorkflowStateSnapshot`, not a
  `Map<String, Any?>`; projecting that record onto the canonical wire shape is
  adapter work owned by
  `skillbill.infrastructure.fs.WorkflowStateSnapshotWireMapper`, so
  `WorkflowEngine` never builds a snapshot map. The owning read seam is still
  `skillbill.workflow.engine.WorkflowEngine`; durable record
  mapping stays pure and the next engine read rejects drift. Architecture
  tests forbid any `skillbill.infrastructure.fs.contracts.workflow.*SchemaValidator*` or
  `skillbill.infrastructure.fs.*Mapper` import under `runtime-domain` workflow
  source. (SKILL-52.2 Subtask 4 narrowed the
  `runtime-domain -> runtime-contracts` module-graph edge to non-validator
  helpers only; SKILL-233 narrowed it further to `JsonCodec` — including its
  stdlib-typed `parseValue` / `valueToJsonString` facade that keeps
  `kotlinx.serialization` out of `runtime-domain` — the `*_CONTRACT_VERSION`
  constants, `InstallPlanSchemaPaths`, and the typed
  `InvalidWorkflowStateSchemaError` / `MalformedJsonTextError`. The
  `WorkflowContracts` ordering helper is no longer reachable from
  `runtime-domain`; it moved with the wire maps to
  `skillbill.application.workflow.WorkflowWireProjections`.)
- Install-plan schema validation is owned by
  `skillbill.infrastructure.fs.contracts.install.InstallPlanSchemaValidator`, compiled into
  `runtime-infra-fs` and reached through the domain-owned port
  `skillbill.install.model.InstallPlanWireValidator`. The owning seams are
  install-plan building and CLI/MCP emission, both of which validate through the
  injected port rather than importing the validator directly.
- Decomposition-manifest schema validation is owned by
  `skillbill.infrastructure.fs.contracts.workflow.DecompositionManifestSchemaValidator` (paired
  with `DecompositionManifestCoherenceValidator`), compiled into
  `runtime-infra-fs` and reached through the domain-owned port
  `skillbill.workflow.decomposition.DecompositionManifestValidator`. The owning parse/emission
  seam is `skillbill.application.decomposition.DecompositionManifestFileWrites`, which
  validates YAML text and in-memory maps through that port before workflow
  artifacts are persisted or returned. Repo-local manifest text persistence is
  owned by
  `skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore`
  behind `skillbill.ports.workflow.decomposition.DecompositionManifestStore`.
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
  `skillbill.infrastructure.fs.contracts.workflow.GoalProgressEventSchemaValidator` in
  `runtime-infra-fs`, reached through the domain-owned port
  `skillbill.workflow.goal.GoalProgressEventValidator` (wired in `RuntimeComponent`
  to `skillbill.infrastructure.fs.GoalProgressEventValidatorAdapter`, mirroring
  `GoalObservabilityEventValidator`). The owning durable write/parse seam is
  `skillbill.application.WorkflowGoalRunnerOutcomeStore.recordProgressEvent`,
  which validates the declared-progress event map through the injected port
  before it is appended to the bounded `goal_progress_run_history` /
  `goal_progress_latest_event` workflow artifacts. The supervisor read seam
  (`WorkflowGoalRunnerOutcomeStore.progress`) decodes the latest declared event
  softly so a malformed stored record cannot disable deterministic liveness.
- IDE status schema validation
  (`orchestration/contracts/ide-status-schema.yaml`) is owned by
  `skillbill.infrastructure.fs.contracts.workflow.IdeStatusSchemaValidator` in
  `runtime-infra-fs`, reached through the domain-owned port
  `skillbill.workflow.idestatus.IdeStatusValidator` (wired in `RuntimeComponent` to
  `IdeStatusValidatorAdapter`). The owning emit seam is
  `skillbill.application.work.IdeStatusService`, which validates before CLI
  JSON emission.
- IDE status selection has a **retention ceiling**
  (`skillbill.application.work.IdeStatusSelectionPolicy.retainedAt`). The IDE
  surface reports work the runtime is currently reporting on, never a ledger of
  every unresolved row. Each tier ages out against its authoritative
  `updated_at`: active/paused after `LIVE_RETENTION` (24h, generous enough that a
  long quiet phase never drops a genuine run), blocked after `BLOCKED_RETENTION`
  (also 24h — blocked work is a prompt awaiting the user, not a finished event),
  and failed/terminal after `SETTLED_RETENTION` (6h). Past the ceiling the
  candidate is dropped and the repository reports `no_matching_work`, so a
  settled or abandoned workflow reads as idle rather than occupying the widget.
  Clock skew (observation before update) never drops work.
  `SETTLED_RETENTION` must stay strictly greater than
  `IdeStatusFreshnessClassifier.FRESH_WINDOW`: equal values make retention and
  freshness exact complements, and no settled snapshot could ever be emitted with
  `freshness: "stale"`.
- The authoritative `updated_at` for a candidate is resolved once, in
  `IdeStatusService.authoritativeUpdatedAt`, and reused for retention, freshness
  classification, and the emitted wire field. Projectors must not re-derive it —
  two anchors for one candidate let the widget freeze its elapsed clocks against a
  timestamp the selection never saw.

## Phase Context Boundary (SKILL-137 handoff projections)

SKILL-146 makes this boundary explicitly four-part: complete producer output is
private evidence; a named consumer projection is the only prompt-visible
derivative; repository state has an immutable checkpoint identity; and
phase-local instructions use workflow-owned invariant allowlists. Declaration
and persistence wires have independent incompatible `0.2` contracts. Delivered
records identify the workflow, consumer, producer iteration, and checkpoint;
the versioned, exact-decoded `FeatureTaskRuntimePhaseRecord` wire is the
authoritative durable private-evidence record written and read by the phase
recorder. It remains under the private phase-record artifact key, separate from
the delivered-projection key and prompt-facing read API. Unknown fields,
missing record identity, and unsupported versions are incompatible rather than
defaulted.
legacy records missing that identity loud-fail with restart or explicit
out-of-band migration guidance. The operator action is deliberately identical at
workflow, briefing, handoff, private-evidence, and delivered-projection read
seams: restart the active run or use the documented out-of-band migration
procedure. Unsupported versions are never defaulted or interpreted as the
current least-context shape.

Budgets are enforced before launch against serialized UTF-8 bytes and
collection items. The runtime never truncates, drops fields, or falls back to a
complete artifact. Measurements contain identifiers, byte/item counts, token
estimates, and failure classifications only, never prompt or evidence bodies.
They are written only through lifecycle telemetry and progress stores; no
measurement or diagnostic field is added to a phase receipt or other
prompt-consumable domain artifact.

A feature-task-runtime phase no longer receives the complete output of its
upstream phases. Context reaching a phase is split into four parts with distinct
owners, storage, and failure modes.

**1. Private evidence.** Complete validated phase output stays on
`FeatureTaskRuntimePhaseRecord.outputArtifact` (and `rejectedOutput` for
schema-rejected attempts) under the
`feature_task_runtime_phase_records` artifact key. It is the run's durable record
of what each phase actually produced. Nothing reads it into a prompt directly.

**2. Consumer projection.** What a phase receives is declared, not inferred.
`PhaseHandoffProjectionDeclaration` (`runtime-domain`) names one source, one
projection contract id/version, prompt visibility, a UTF-8-byte and
collection-item budget, and a repository-checkpoint policy.
`FeatureTaskRuntimePhaseDeclaration.projectionDeclarations` is the sole place a
source can be declared; `consumedUpstreamPhaseIds` is derived from it, so a
recorded output with no declaration is never delivered.
`FeatureTaskRuntimeHandoffProjectionValidator` turns declarations into a
`FeatureTaskRuntimeHandoffEnvelope` of named typed projections and compact
references. It rejects — never truncates — on a missing required source,
malformed or undeclared field, unsupported contract version, duplicate
projection name, budget overflow, invalid compact reference, or
checkpoint-policy violation, each through
`InvalidFeatureTaskRuntimeHandoffProjectionError` naming the workflow, consumer
phase, projection, and contract without echoing payload bodies. The envelope has
its own Draft 2020-12 contract
(`orchestration/contracts/feature-task-runtime-handoff-envelope-schema.yaml`,
pinned by `FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION` and
`FeatureTaskRuntimeHandoffEnvelopeSchemaContractVersionTest`), reached from the
domain only through the `FeatureTaskRuntimeHandoffEnvelopeValidator` port with
`FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter` in `runtime-infra-fs` —
the same domain/infra validator-boundary convention as
`WorkflowSnapshotValidator`. Delivered envelopes persist separately from private
evidence as `FeatureTaskRuntimeDeliveredProjectionRecord` under
`feature_task_runtime_delivered_projections`; the two artifact keys must never
merge, because merging them is exactly how a round trip could hand a consumer
the private artifact in place of its projection. Raw-map exposure is confined to
`@OpenBoundaryMap`-annotated wire seams.

A projection may declare `inlineAlternative` to deliver a lossless compact
reference instead of inline content. A `private_evidence_artifact` reference is
accepted only when the declaration also sets `allowsPrivateArtifactReference`,
and the reference itself is minted by the runtime from the source's durable
identity, so dereferencing it is a deterministic runtime operation rather than
model-driven retrieval.

**3. Repository-derived context.** `FeatureTaskRuntimeRepositoryCheckpoint`
carries a deterministic fingerprint, optional base/head refs, and working-tree
ownership. Policies are `not_required`, `must_match`, and
`refresh_from_repository`. Both checkpoint-aware policies require and carry a
freshly resolved checkpoint. `must_match` is retained as a legacy durable wire
value and, like `refresh_from_repository`, accepts repository movement and
re-derives the consumer scope. The domain stays git-agnostic: the application layer resolves
the checkpoint in `FeatureTaskRuntimeRunLoop` through the existing
`WorkflowGitOperations` port, reusing the same `repositoryFingerprint` extension
the audit-repair path already depends on. No new git port was introduced.

**Shared review evidence (SKILL-164).** Branch/commit review evidence is derived
once per `FeatureTaskRuntimeRepositoryCheckpoint.fingerprint` into a repo-local
artifact under `.skill-bill/run-evidence/<workflowId>/<fingerprint>/`. The
delivered projection is a reference only — `store_path` plus a bounded
file/hunk index — never inlined diff bytes, so the planning-projection budget
stays independent of branch diff size. The contract is
`orchestration/contracts/feature-task-runtime-shared-evidence-projection-schema.yaml`,
pinned by `FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION` and
validated on every store read: schema-invalid or unreadable content re-derives,
while a fingerprint that contradicts its addressed location loud-fails. Audit
consumes this projection as a floor alongside `scoped_repository_state`; it
never replaces audit's scoped repository read, because audit's highest-value
finding is a criterion with no code behind it and therefore no diff. Telemetry
records each resolve as `skillbill_feature_task_runtime_shared_evidence` with
outcome `derivation`, `reuse`, or `checkpoint_change_rederivation`.

Finalization path inventories come from the checkpoint's runtime-resolved
base/head and scoped owned-path comparison. Implementation receipt paths are
claims only: validation scope, boundary candidates, commit inclusions and
exclusions, and PR changed paths are derived from the resolved inventory. Runtime
continuation exposes bounded validation, boundary, history,
commit, and PR requests or receipts; it never substitutes the private audit,
review, implementation, validation, or history artifacts. The `build` phase
(SKILL-204) runs only the pack `validation_gate.build_command` for
compile/buildability proof and never invokes the collect-all validation gate.
Default standalone runs skip `build` (`review -> validate`); goal continuation
stamps which quality gate a child runs (subtask 2).

**4. Phase-local instructions.** Run identity remains durable state on every
briefing, but prompt rendering is selected per phase by
`FeatureTaskRuntimeRunInvariantPromptAllowlist`.
`FeatureTaskRuntimeRunInvariantPromptField` classifies each invariant as
identity, acceptance-contract, policy, ceremony, review, add-on, or finalization.
Identity, ceremony, and policy mandates reach every phase; the acceptance
contract is withheld from the finalization phases (`write_history`,
`commit_push`, `pr`), which act on work audit and validate already settled.
Policy mandates are not withheld: they are free-form operator directives that
govern the irreversible outward-facing phases, and this allowlist is their only
delivery path.
Hydrated add-on content is scoped by the manifest-owned
`feature_addon_usage.feature-task` consumer assignment, which is run-scoped:
every phase of a feature-task run is that consumer, so there is no narrower
per-phase gate in `FeatureTaskRuntimePhasePromptComposer.budgetedAddonsFor`.
What that seam does own is the budget — hydrated add-on content is budgeted
independently of phase receipts, so neither budget can borrow the other's
headroom and an oversized add-on rejects rather than inflating the briefing.

`FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING` now bounds only the
non-projection framing. Projection bodies are bounded by their own declared
budgets, which is what makes the no-truncation guarantee expressible: the
assembler has no budget left to split, so it has nothing to truncate.

The shipped per-edge declarations are currently one coarse whole-receipt
projection per edge (`FeatureTaskRuntimePhaseWorkflowDefinition.upstreamReceiptProjections`).
That proves the mechanism is load-bearing without yet claiming any edge is
minimally scoped; fine-grained named-field projections replace them per edge
later. Those coarse projections are declared `required = false` because presence
of a declared upstream output is already gated ahead of launch by the run loop's
missing-upstream block; the validator's required path stays load-bearing for
declarations that own their own presence contract.

Because those coarse receipts carry a whole phase output, their budgets are sized
against recorded runtime phase outputs rather than picked as round numbers: no
phase other than `preplan` exceeded 20,844 UTF-8 bytes across 239 durable
outputs, while `preplan` reached 131,901. Hence `PHASE_RECEIPT` (65,536 bytes)
for every edge and `PREPLAN_DIGEST_RECEIPT` (196,608 bytes) for the single
`preplan` -> `plan` edge. A rejection therefore means a phase output grew far
beyond every observed size, not that an ordinary run outgrew its budget. Re-size
them from the same measurement when the delivered shape narrows to named fields.

When a projection is rejected anyway, `FeatureTaskRuntimeRunLoop` catches
`InvalidFeatureTaskRuntimeHandoffProjectionError` at the launch seam and blocks
the phase through the ordinary `blockAndPersistInPhase` path with a
`needs_user_action` disposition. The rejection is static declaration or
configuration drift rather than agent output, so retrying without operator action
reproduces it; blocking durably keeps the phase row and the run's finalization
consistent instead of unwinding out of a run that already persisted
`STATUS_RUNNING`.

### Producer-side enforcement (SKILL-140 Subtask 1)

A bounded planning projection was validated only at its consumer's launch seam,
where the producing phase is already settled `completed`. A malformed digest,
plan, or receipt therefore blocked the *next* phase — with no fix loop able to
reach the phase that actually wrote it — and the run wedged. The producer gate
closes that gap: a completed phase that owns a projection must emit one its
consumer can parse, checked at the producing phase's own schema gate so a
violation re-enters that phase's bounded fix loop and blocks only at the existing
cap.

`FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor` is the
single domain-owned routing map from producing phase id to the projection kind it
owes (`preplan` -> `preplanning_digest`, `plan` -> `executable_plan`, `implement`
-> `implementation_receipt`, and null for every other phase, including the derived
`plan_commitment`, which no phase produces). `producerProjectionGateReason` in
`FeatureTaskRuntimeRunnerPolicies` reads that map and, for a completed envelope
whose phase owns a kind, calls the same `featureTaskRuntimePlanningProjectionFromEnvelope`
with the same `planningProjectionValidator` port the launch seam uses — no
projection rule is restated at the gate. `FeatureTaskRuntimePlanningProjectionEdgeTest`
binds the two sides so any envelope the gate accepts the launch seam accepts for
the corresponding consumer edge, and neither can be made stricter than the other.

The gate runs in `settleValidatedOutput` only after `terminalBlockedReasonFrom`,
so a blocked or failed envelope — whose `produced_outputs` carries blocking
reasons, not a projection claim — settles through the terminal path and never
reaches the gate. A `decompose`-mode plan is likewise exempt: it terminates the
run at planning and hands the planning stopper a separately-contracted
decomposition package (`featureTaskRuntimeIsDecompositionPackage`), which no
consumer parses as an executable plan. That exemption is scoped to the
executable-plan producer (`plan`), the only phase with a decompose stopper
backstop; a `preplan` or `implement` output merely shaped like a decomposition
package has no backstop and still faces the gate, so it cannot settle `completed`
and wedge its consumer. The rejection reason names the phase, the
expected projection kind, and the underlying validation failure (its source label
plus reason), bounded by the existing `SCHEMA_GATE_DETAIL_MAX_CHARS` schema-gate
detail truncation — no second truncation rule.

### Quarantine-and-regenerate (SKILL-140 Subtask 4)

Producer-side gating (Subtask 1) reduces launch-seam rejections to legacy and
drift records — precisely the population an in-band recovery edge can repair.
When `FeatureTaskRuntimeRunLoop.launchAndCapture` catches
`InvalidFeatureTaskRuntimePlanningProjectionSchemaError` or an
`InvalidWorkflowStateSchemaError` on an upstream handoff envelope, it no longer
blocks on first occurrence. Instead the consumer settles with the synthetic
`RECORD_REJECTED` verdict, which drives the existing
`FeatureTaskRuntimeTransitionFunction` over a pinned consumer→producer
regeneration edge (`plan`→`preplan`, `implement`→`plan`, `audit`→`implement`,
each with its own `regenerate_*` loop id and the `MAX_RECORD_REGENERATION_ATTEMPTS`
cap). No parallel state machine is introduced: the same loop-id, edge-iteration,
watermark, and crash-resume machinery the review-fix and audit-gap loops use
bounds regeneration, so a crash mid-regeneration resumes the same cap sequence
without reset.

Before the edge fires, the rejected record is appended to a durable, append-only
quarantine store (`FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY`,
validated by the canonical quarantine schema). That store is private evidence: it
is never resolved into an upstream projection, so no rejected byte reaches an
agent prompt or briefing, and no runtime path ever mutates or deletes an entry —
only out-of-band operator action may. The producer's settled `completed` status
is invalidated through the existing phase-record machinery (its rejected payload
moves to `rejected_output`, its status returns to `running`), so the handoff
contract's `selectLatestOutputsByPhase` no longer surfaces the rejected record and
the regenerated higher-iteration output supersedes it on this or any resumed run.

Cap exhaustion blocks durably with a reason naming the quarantined record, its
producing phase, and the attempt count. A record the runtime cannot attribute to
a producing phase, or whose producer a goal-continuation truncation dropped from
the resolved pipeline, blocks durably with an actionable reason rather than
attempting an impossible re-entry. Static declaration/config drift
(`InvalidFeatureTaskRuntimeHandoffProjectionError`), briefing byte-ceiling
overflow, and an audit's own `audit_repair_state` drift keep their
first-occurrence durable block: re-running a producer cannot fix them.
Out-of-band row deletion or migration is the corruption fallback for records the
edge cannot regenerate. Per-run regeneration telemetry records activation counts,
attempt counts, and outcome-class tallies on the
`skillbill_feature_task_runtime_finished` event — counts and class labels only, never record contents.

Canonicalization and reconciliation of malformed durable projection records
beyond this recovery edge belong to later SKILL-140 subtasks.

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

- `ARCHITECTURE.md`, Gradle settings, and the architecture-test module catalog
  describe the same module and subsystem graph.
- `runtime-core` contains only `skillbill` and `skillbill.di` source packages.
- `runtime-core` does not directly re-export contract or concrete
  infrastructure modules as adapter API, and its transitive API closure stays
  limited to the documented Kotlin-Inject generated ABI closure.
- Top-level runtime modules do not depend upward or on sibling concrete
  adapters where forbidden.
- Infrastructure modules do not depend on runtime-core, CLI, MCP, or
  sibling concrete infrastructure adapters.
- CLI and MCP adapters declare direct runtime dependencies and do not
  use runtime-core as an implementation umbrella.
- CLI and MCP adapters call application services and ports instead
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
- typed CLI presenter models are the input to CLI text rendering.
- `docs/architecture/gradle-module-split-evaluation.md` records the physical
  Gradle split decision and readiness rules.
- Every `runtime-cli` command area's transitive `skillbill.cli` import closure
  contains only the shared `kernel` and `model` leaves, never a sibling command
  area and never the composition root `skillbill.cli.core`.
- No runtime module source file, and no main-source file, type, or member
  declaration, carries the spillover signature (`*Extras`, `*Continued`,
  `*Helpers`, `*Support`, `*Misc`, `*Fns<N>`, letter-plus-digit, or bare
  trailing-digit siblings) outside a named exemption; the bare `Support`,
  `Helpers`, `Misc`, and `Extras` forms apply to `src/main` only, the numbered
  forms to every `src` tree.
- No main-source site outside `skillbill.di` constructs a concrete class
  `RuntimeComponent` binds; `RuntimeCompositionGuardArchitectureTest` enforces
  the census and names sanctioned second entrypoints explicitly.
- `skillbill.infrastructure.fs.scaffold.runtime.ScaffoldStandaloneEntrypoint` is the sanctioned
  second scaffold entrypoint for in-tree parity and rollback tests that cannot
  reach `RuntimeComponent`; production paths use `FileSystemScaffoldOrchestrator`.
- A failed `uninstall` mutation is a recorded degradation with a non-zero exit
  code, shared by launcher removal, desktop removal, recursive tree removal,
  agent-target cleanup, native-agent unlinking, and MCP unregistration.
- The Raw Map Boundary Rule (rule 11) and its Open-Boundary Allow-List are
  enforced by `RuntimeArchitectureTest.runtime architecture forbids raw map
  shapes outside the open-boundary allowlist` and
  `RuntimeArchitectureTest.open-boundary allow-list documents required
  exceptions` (the latter asserts ARCHITECTURE.md and the curated
  allow-list constant stay in sync). New exceptions MUST be added to both
  the allow-list constant and ARCHITECTURE.md in the same change.

### SKILL-227 runtime-application guardrails

`ProductionLogicalTypeLineCeilingArchitectureTest` attributes each production
Kotlin file to a logical type: type-declaring files bill to the first top-level
named type FQN; extension-only files bill every line to each distinct
extension-receiver FQN. A shrink-only baseline records offenders above the
1200-line ceiling; baselined units may only shrink and unlisted units must stay
at or below the ceiling.

`ApplicationPackageAcyclicityArchitectureTest` tracks mutual import pairs among
the areas of one package prefix under one scan root, both passed as parameters.
The `runtime-application` baseline is shrink-only; any new mutual-import pair
not already baselined fails the build.

`RuntimeApplicationAmbientClockArchitectureTest` bans `Instant.now()`,
`LocalDateTime.now()`, `LocalDate.now()`, and `Clock.systemUTC()` under a
parameterized scan root. The `runtime-application` baseline is shrink-only.

`InjectConstructorDefaultsArchitectureTest` bans default arguments on
`@Inject` constructors and dependency bags consumed by them, and non-private
property initializers on an `@Inject` class that declares no primary
constructor. Production wiring must bind every port explicitly in
`RuntimeComponent`; test-only stubs such as `ApprovingReviewDriverStub` are
never reachable through an unbound dependency.

The scanner strips comments and string and character literals before it walks
delimiters, so a default whose literal holds an unbalanced brace or paren does
not hide the properties declared after it. The `runtime-application` baseline is
empty by rule, not by census: the recorder never rewrites it and the test
asserts it stays empty, so a new default fails the build instead of being
recorded away.

### SKILL-229 runtime-cli guardrails

The acyclicity, ambient-clock, and `@Inject`-defaults scanners are shared, not
copied: each takes its scan root (and, for acyclicity, its package prefix) as a
parameter, and `runtime-cli` is a second case over the same scanner body. A
second copy of a scanner scoped to another module is not an acceptable
substitute.

`AmbientEnvironmentArchitectureTest` bans `System.getenv`, `System.getProperty`,
`Path.of("")`, and `Paths.get("")` under a parameterized scan root. Its scope is
the scan root plus a recorded baseline per module, with no per-pattern carve-outs;
test infrastructure stays outside the scanned root. Named file-path exemptions on
`PrincipleEnforcementInventory.ambientEnvironmentExemptions` omit a process entry
from baseline recording only; every other main-source site must still match an
empty baseline. Today that list names
`runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/core/Main.kt` as the
MCP process boundary.

The four `runtime-cli` baselines started as a census — 16 mutual-import pairs, 2
ambient-clock sites, 22 ambient-environment sites, and `CliRunState`'s 8
default-valued fields — and subtasks 2 and 3 emptied all four. Each
`runtime-cli` case asserts set equality against its baseline rather than absence
of unlisted sites, so a scanner that ignored its new scan-root or
package-prefix parameter cannot pass against a stale baseline; with the
baselines empty that equality is a hard ban. Regenerate these baselines from
the scanners with `RECORD_ARCHITECTURE_BASELINES=1`, never by hand.

`RuntimeCliAreaIsolationArchitectureTest` proves what an empty cycle baseline
cannot: every command area's transitive `skillbill.cli` import closure must
contain only the shared leaves `skillbill.cli.kernel` and `skillbill.cli.model`,
never a sibling command area and never the composition root
`skillbill.cli.core`. A cycle baseline can be emptied by moving a single import
even when the areas stay entangled through one-directional hub edges, so the
closure assertion is the guard that any command area builds and tests alone.
The scan enumerates every area it finds under `skillbill.cli` and exempts one
name, `CLI_COMPOSITION_ROOT_AREA`; probing a single hand-picked area would let a
one-directional edge such as `goal -> featuretask` pass both guards.
`skillbill.cli.core` holds only the composition root — `CliComponent`,
`CliRuntime`, `Main`, `SkillBillCommand`, `CliCommandGroups`, and
`CliUtilityCommandGroups` — and it is the only package that may import a
command area. `install` therefore owns its own command tree and top-level
group, and the units two command areas share — the completion telemetry drain
and the `WorkflowUpdateResult` payload mapper — live in `skillbill.cli.kernel`.

`RuntimeSpilloverFileNameArchitectureTest` bans the spillover filename signature
across every module source root. Exemptions are a named list on
`PrincipleEnforcementInventory`, empty by rule, never an ad-hoc regex carve-out.
The 1200-line per-file ceiling (2026-09-04 decision) moves only by decision
entry, never by baseline or exemption: a re-merged unit above it fails the
logical-type ceiling instead.

### SKILL-231 inward-layer guardrails

The package-acyclicity, ambient-clock, ambient-environment, and
`@Inject`-defaults scanners are instantiated once per Gradle module through
`PrincipleEnforcementInventory.moduleArchitectureScanCases`, driven by
`RuntimeModuleCatalog.declaredGradleModules`. Each case supplies its own main
scan root and package prefix (or scan root alone for ambient-environment and
inject-defaults) rather than forking a second scanner class.

`AmbientEnvironmentArchitectureTest` takes its scan root as a parameter; every
module main source root has a recorded baseline. The `runtime-cli` baseline
remains empty by rule.

`RuntimeSpilloverFileNameArchitectureTest` scans every module's `src` tree
(main and test), matching `*Extras`, `*Continued`, `*Helpers<N>`, `*Fns<N>`,
`*Support<N>`, `*Misc<N>`, letter-plus-digit suffixes, and bare trailing-digit
names when a de-digited or differently digitized sibling exists in the same
package directory. Under `src/main` the bare `*Support`, `*Helpers`, `*Misc`,
and `*Extras` forms are banned too, and the same pattern runs over every
top-level and member declaration name (class, object, interface, fun, val, var)
with string literals and comments stripped. File violations are keyed on
repository-relative paths and identifier violations on `path#name`, both against
`baselines/spillover-file-name-baseline.txt`.

`RuntimeCoreCompositionOnlyTest` pins every module's `api(project(...))` and
`implementation(project(...))` sets to today's edges, alongside the retained
infrastructure-and-entrypoint `api` ban on `runtime-core`. `runtime-core` keeps
`api(:runtime-application)` and `api(:runtime-ports)` as the kotlin-inject ABI
edges. `runtime-infra-fs`, `runtime-infra-http`, and `runtime-infra-sqlite`
narrow `api(:runtime-ports)` and `api(:runtime-domain)` to `implementation`.
`runtime-cli` carries no `api` project edges.

Baselines that were empty on main (`runtime-application` and `runtime-cli`
package-cycle, ambient-clock, ambient-environment, and inject-defaults baselines,
plus the runtime-application inject-defaults floor) stay empty by rule. Module
baselines recorded here are shrink-only ceilings: they may only shrink, never
grow without an explicit baseline update through the recorder.

### Port null-object classification

`PortNullObjectAbsenceArchitectureTest` requires that no `Unavailable`, `Noop`,
`Empty`, or `Unconfigured` object is declared in any runtime module's main
source. A port whose absence a production call site actually reaches is
nullable, and the reached site names its fallback (`?: JdkHttpRequester`,
`?: git`) or returns the absent answer. The substitutes that tests still need
live in the owning module's `src/testFixtures` under their original packages,
so they are unreachable from a published runtime.

`RuntimeContractModuleImportRulesTest` pins the two inward layers: `runtime-ports`
declares interfaces and DTOs and imports no adapter machinery
(`java.io`, `java.nio.file.Files`, `kotlinx.serialization`, `me.tatarka.inject`,
`org.yaml`), and `runtime-domain` imports no serialization, charset, or IO
library (`java.io`, `java.nio.charset`, `com.fasterxml`, `kotlinx.serialization`,
`org.yaml`). Domain code reaches JSON only through the stdlib-typed
`skillbill.contracts.JsonCodec` facade and text encoding only through
`kotlin.text.Charsets`. Both guards assert an empty violation list; neither
carries a baseline.

`data object` cases of a sealed hierarchy — `ValidationGateTriageResult.Empty`
is the one in the tree — are not substitutes and the census excludes them.

### Destructive command failure policy

`uninstall` is the runtime's only destructive command. A mutation it cannot
apply is a recorded degradation with a non-zero exit code, never a warning
string on a zero exit. Launcher removal, desktop removal, recursive tree
removal, agent-target cleanup, native-agent unlinking, and MCP unregistration
share that one policy through `UninstallMutationRecorder`, which owns it: each
site hands the recorder the failed mutation, the recorder emits a
`skillbill.ports.diagnostics.RuntimeDiagnostics` error record and contributes
to a failed outcome, and the command reports a non-zero exit code. No mutation
site formats its own warning or decides its own severity. A partial uninstall —
launcher symlink removed, state tree left behind — therefore cannot report
success.

The completion telemetry drain is the one deliberate swallow that stays: it
must not change the run's exit code and must not reach the run's stdout or
stderr. It is not silent. Every abandonment path — the worker still alive after
the join timeout, an interrupted join, and the worker's own failure — emits a
`RuntimeDiagnostics` warning, which is the sanctioned channel under
`docs/observability-policy.md` for a degradation that must stay off the run's
output surfaces.

`skillbill.application.runtime.RuntimeSingleton` scopes services and adapters that hold a cache, connection,
or lease across accessor reads (`DatabaseSessionFactory`,
`FeatureTaskRuntimeWorkerSupervisor`, `FeatureTaskRuntimeWorkerCoordinator`,
`DurableGoalPlanningAttemptRecorder`). Deliberately unscoped services:

- `GoalRunner` — per-access construction is intentional until subtask 2 makes
  `validationQualityRetries` durable across accessor reads.
- Stateless orchestration services (`WorkflowService`, `ReviewService`,
  `ParallelCodeReviewRunner`, and similar) — no cross-call mutable state.

Runtime database selection belongs to the bound `EnvironmentContext`. The
`DatabaseSessionFactory` resolves and retains one normalized path for its
component lifetime; application and port operations do not accept database
path overrides. CLI parsing selects `--db` before component creation, while
MCP and embedded callers bind their selected path in the same context. The
`EnvironmentContext.dbPathOverride` property is configuration at that
composition boundary, not operation or request plumbing.

## Workflow Git status inventory

The closed workflow-Git result vocabulary is owned by
`skillbill.ports.workflow.gitops.model`:

- `WorkflowGitOperationResult` is the sealed `Ok`/`Failed` result. Its
  non-null `value` and `error` payloads default to empty strings. The result
  cases own the canonical `wireValue` tokens `ok` and `error`, and
  `WorkflowGitOperationResult.fromWire` is the only decoder for that result.
- `WorkflowGitOperationStatus` owns the same `ok` and `error` tokens for
  structured Git DTOs and is the only decoder for those DTO status fields.
  `WorkflowScopedPathContentsResult.status`,
  `WorkflowSelectedDiffHunksResult.status`, and
  `WorkflowWorktreeActivityResult.status` use this enum; none is an open
  provider vocabulary.
- `GoalSubtaskReviewBaselineResult.status` and
  `GoalSubtaskReviewInputResult.status` remain the legacy Git review
  operation envelope boundary. Their `ok`/`error` tokens are consumed only
  by the review adapter and application recovery seams; they are not
  `WorkflowGitOperationResult` values and do not authorize raw status access
  on that sealed result.

`recordsNothingToCommit` is a pure result extension that searches both
  payloads. The Git adapter may normalize a recognized no-change failure to an
  empty `Ok`, while goal finalization separately accepts a marker-bearing
  `Failed`; both paths are intentional and preserve their existing payload
  semantics.

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

- `skillbill.learnings.learningEntryPayload` [subtask 5] — typed learnings surface.
- `skillbill.learnings.learningPayload` [subtask 5] — typed learnings surface.
- `skillbill.learnings.learningSessionJson` [subtask 5] — typed learnings surface.
- `skillbill.learnings.learningSummaryPayload` [subtask 5] — typed learnings surface.
- `skillbill.learnings.scopeCounts` [subtask 5] — typed learnings surface.
- `skillbill.learnings.summarizeLearningReferences` [subtask 5] — typed learnings surface.

### open_extension (@OpenBoundaryMap)

- `skillbill.application.decomposition.baseBranch`
- `skillbill.application.decomposition.executionModel`
- `skillbill.application.decomposition.parentSpecPath`
- `skillbill.application.decomposition.parseStackBranches`
- `skillbill.application.decomposition.parseSubtasks`
- `skillbill.application.decomposition.specSource`
- `skillbill.engine.featuretask.CompletedImplementationOutputArgs.outputMap`
- `skillbill.engine.featuretask.CompletionProjectionRejectionArgs.outputMap`
- `skillbill.engine.featuretask.FeatureTaskPhaseSettlementService.auditSettle`
- `skillbill.engine.featuretask.FeatureTaskPhaseSettlementService.block`
- `skillbill.engine.featuretask.FeatureTaskPhaseSettlementService.complete`
- `skillbill.engine.featuretask.FeatureTaskPhaseSettlementService.findEnvelope`
- `skillbill.engine.featuretask.FeatureTaskRuntimeGoalContinuationArtifactPatcher.save`
- `skillbill.engine.featuretask.FeatureTaskRuntimeOutputVerification.auditProseValue`
- `skillbill.engine.featuretask.FeatureTaskRuntimeOutputVerification.dispositionsFrom`
- `skillbill.engine.featuretask.FeatureTaskRuntimeOutputVerification.rejectedFindingDispositions`
- `skillbill.engine.featuretask.FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings`
- `skillbill.engine.featuretask.FeatureTaskRuntimeOutputVerification.verdictFor`
- `skillbill.engine.featuretask.FeatureTaskRuntimeOutputVerification.verifiedFindingDispositions`
- `skillbill.engine.featuretask.FeatureTaskRuntimePhaseReviewGenerationApi.recordedFindingVerdicts`
- `skillbill.engine.featuretask.FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput`
- `skillbill.engine.featuretask.FeatureTaskRuntimeReviewEnvelope.envelopeMap`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopAttemptSettlement.settleValidatedOutputBoundary`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopAttemptSettlement.rejectValidatedOutput`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopCheckpointRemediation.completedImplementFixProducedOutputs`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopDrive.completeReservedGoalReviewPass`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopLaunch.outputEnvelopeOf`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputPersistence.persistRejectedVerificationFindings`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.firstValidatedOutputRejection`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.auditGapProgressPause`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.findingVerificationBoundaryBodyDeliveryDecision`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.findingVerificationBoundaryDispositionGate`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.findingVerificationBoundaryDispositionGateImpl`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.outputVerificationGateReason`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.verifyFindingsBoundaryContext`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopOutputVerification.verifyFindingsDispositionGateContext`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopRecordRejection.payloadFreeSemanticGateConstraint`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopRecordRejection.scrubResponseDerivedGateDetail`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopRepairReceipt.implementFixRepairReceiptSettlement`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopRepairReceipt.repairReceiptShapeSettlement`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopSubtaskCommit.revalidated`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopValidationGate.gateTriageCapturedProducedOutputs`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunLoopValidationGate.looseOutputEnvelope`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunState.parsedOutputsByPayload`
- `skillbill.engine.featuretask.FeatureTaskRuntimeSubtaskFinalisation.readHandoff`
- `skillbill.engine.featuretask.FeatureTaskRuntimeSubtaskFinalisation.withCommitSha`
- `skillbill.engine.featuretask.FeatureTaskRuntimeSubtaskFinalisationHandoff.readHandoff`
- `skillbill.engine.featuretask.FeatureTaskRuntimeSubtaskFinalisationHandoff.withCommitSha`
- `skillbill.engine.featuretask.FeatureTaskRuntimeVerificationGateReasons.findingVerificationDisposition`
- `skillbill.engine.featuretask.FeatureTaskRuntimeVerificationGateReasons.reviewVerificationSignal`
- `skillbill.engine.featuretask.FeatureTaskRuntimeWorkflowPersistence.persistPatch`
- `skillbill.engine.featuretask.GoalReviewPassCompletionRequest.normalizedOutput`
- `skillbill.engine.featuretask.ImplementFixRepairReceiptArgs.outputMap`
- `skillbill.engine.featuretask.SettleValidatedOutputAfterFingerprintArgs.outputMap`
- `skillbill.engine.featuretask.SettleValidatedOutputPauseArgs.outputMap`
- `skillbill.engine.featuretask.TerminalOutputAttemptArgs.outputMap`
- `skillbill.engine.featuretask.WorkflowRowAdvance.stepUpdates`
- `skillbill.engine.featuretask.checkpointIdentitiesFrom`
- `skillbill.engine.featuretask.continuationFromArtifacts`
- `skillbill.engine.featuretask.continuationPatch`
- `skillbill.engine.featuretask.decodeStrictKeyedArtifactMap`
- `skillbill.engine.featuretask.decomposeTerminalFrom`
- `skillbill.engine.featuretask.deliveredProjectionHistoryFrom`
- `skillbill.engine.featuretask.deliveredProjectionsFrom`
- `skillbill.engine.featuretask.featureSizeFromArtifacts`
- `skillbill.engine.featuretask.featureTaskRuntimeParseRepairReceipt`
- `skillbill.engine.featuretask.featureTaskRuntimeParseRepairReceiptOrNull`
- `skillbill.engine.featuretask.featureTaskRuntimeRepairReceiptShapeRejection`
- `skillbill.engine.featuretask.findingVerificationCheckpointPatch`
- `skillbill.engine.featuretask.goalContinuationFieldAdoptionFrom`
- `skillbill.engine.featuretask.implementationAttemptPatch`
- `skillbill.engine.featuretask.implementationAttemptsFrom`
- `skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap`
- `skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing.toArtifactMap`
- `skillbill.engine.featuretask.mutatingReconciliationGateReason`
- `skillbill.engine.featuretask.operatorBlockRetryFrom`
- `skillbill.engine.featuretask.FeatureTaskRuntimeRunState.parsedOutput`
- `skillbill.engine.featuretask.phaseBriefingsFrom`
- `skillbill.engine.featuretask.phaseLedgerFrom`
- `skillbill.engine.featuretask.phaseRecordsFrom`
- `skillbill.engine.featuretask.producerProjectionGateReason`
- `skillbill.engine.featuretask.quarantineEntriesFrom`
- `skillbill.engine.featuretask.rawReviewResultsFromArtifacts`
- `skillbill.engine.featuretask.recordProjectionMeasurements`
- `skillbill.engine.featuretask.remediationBaseRecoveryEvidenceEntry`
- `skillbill.engine.featuretask.requireValidPlanningProjection`
- `skillbill.engine.featuretask.resolvedBranchFrom`
- `skillbill.engine.featuretask.reviewGenerationFrom`
- `skillbill.engine.featuretask.reviewStateFromArtifacts`
- `skillbill.engine.featuretask.reviewStatePatch`
- `skillbill.engine.featuretask.stepUpdatesFrom`
- `skillbill.engine.featuretask.terminalBlockedReasonFrom`
- `skillbill.engine.featuretask.validateEnvelopeWire`
- `skillbill.engine.featuretask.validatePersistenceWire`
- `skillbill.engine.goalplanning.toEnvelopeMap`
- `skillbill.engine.goalrunner.GoalRunnerChildRepairWedgeApplyLoop.ApplyState.artifacts`
- `skillbill.engine.goalrunner.GoalRunnerChildRepairWedgeApplyLoop.ApplyState.evidenceEntries`
- `skillbill.engine.goalrunner.GoalRunnerChildRepairWedgeApplyLoop.ApplyState.patch`
- `skillbill.engine.goalrunner.GoalRunnerMissingResultPrefixCandidate.output`
- `skillbill.engine.goalrunner.GoalRunnerStaleBlockedOutcomeContext.artifacts`
- `skillbill.engine.goalrunner.childRepairWedgeEvidenceMap`
- `skillbill.engine.goalrunner.continuationArtifactFromMap`
- `skillbill.engine.goalrunner.planning.GoalPlanningContextPromptFormatter.append`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContext.planningPacket`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.catalog`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.catalogHeadingIds`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.digest`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.discardedCatalog`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.emptyCatalog`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.includedSubtaskIds`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.migrate`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.orderedSubtasks`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacket.validate`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacketLegacy.migrateFromPacketVersion1`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacketLegacy.migrateFromPacketVersion2`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacketLegacy.migrateFromPacketVersion3`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacketValidation.digest`
- `skillbill.engine.goalrunner.planning.GoalPlanningSharedContextPacketValidation.normalizedSubtasks`
- `skillbill.engine.goalrunner.planning.enrichPreplan`
- `skillbill.engine.goalrunner.planning.freshPlanningPacket`
- `skillbill.engine.goalrunner.planning.gatherSharedContext`
- `skillbill.engine.goalrunner.planning.planningPacketFrom`
- `skillbill.engine.goalrunner.planning.unsuccessfulStatusReason`
- `skillbill.engine.goalrunner.terminalJsonObjectWithoutResultPrefix`
- `skillbill.engine.goalrunner.toStatusMap`
- `skillbill.application.idestatus.model.IdeStatusProblem.details`
- `skillbill.application.idestatus.model.IdeStatusSnapshot.toStatusWireMap`
- `skillbill.engine.planningprojection.producerProjectionGateReason`
- `skillbill.engine.planningprojection.requireValidPlanningProjection`
- `skillbill.application.review.model.ReviewContextEnvelope.asWireMap`
- `skillbill.application.review.toBoundedPayload`
- `skillbill.application.review.toProjectionPayload`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewOutcomeDispositionReduction.blockerDispositions`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse.citationDiagnostics`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse.parseStructuredFindings`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse.recordedVerdicts`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse.reviewRunIdOf`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse.structuredFindings`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.blockerDispositions`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.commitFocusedAccounting`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.evidenceCoverageComplete`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.fromOutput`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.outcomeFor`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.rejectedVerificationFindings`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.unaddressedFindings`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer.unresolvedCount`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummarySanitize.labelFor`
- `skillbill.goalrunner.subtaskreview.GoalSubtaskReviewVerificationRejection.rejectedVerificationFindings`
- `skillbill.goalrunner.subtaskreview.recordedVerdicts`
- `skillbill.goalrunner.subtaskreview.reviewPassVerdict`
- `skillbill.goalrunner.subtaskreview.reviewRunIdOf`
- `skillbill.goalrunner.subtaskreview.structuredFindings`
- `skillbill.application.telemetry.LifecycleTelemetryService.featureTaskRuntimeFinished`
- `skillbill.application.telemetry.LifecycleTelemetryService.featureTaskRuntimeStarted`
- `skillbill.application.telemetry.LifecycleTelemetryService.featureVerifyFinished`
- `skillbill.application.telemetry.LifecycleTelemetryService.featureVerifyStarted`
- `skillbill.application.telemetry.LifecycleTelemetryService.goalFinished`
- `skillbill.application.telemetry.LifecycleTelemetryService.goalIssueFinished`
- `skillbill.application.telemetry.LifecycleTelemetryService.goalStarted`
- `skillbill.application.telemetry.LifecycleTelemetryService.goalSubtaskFinished`
- `skillbill.application.telemetry.LifecycleTelemetryService.prDescriptionGenerated`
- `skillbill.application.telemetry.LifecycleTelemetryService.qualityCheckFinished`
- `skillbill.application.telemetry.LifecycleTelemetryService.qualityCheckStarted`
- `skillbill.application.telemetry.lifecycleErrorPayload`
- `skillbill.application.telemetry.lifecycleOkPayload`
- `skillbill.application.telemetry.lifecycleSkippedPayload`
- `skillbill.application.telemetry.orchestratedPayload`
- `skillbill.application.telemetry.orchestratedStartedSkippedPayload`
- `skillbill.application.workflow.FeatureTaskRuntimePhaseLedgerDecoder.decode`
- `skillbill.application.workflow.decodeFeatureTaskRuntimePhaseRecords`
- `skillbill.application.workflow.decodeWorkflowArtifacts`
- `skillbill.application.workflow.model.WorkflowUpdateRequest.artifactsPatch`
- `skillbill.application.workflow.model.WorkflowUpdateRequest.stepUpdates`
- `skillbill.application.workflow.parentProjectionArtifacts`
- `skillbill.application.workflow.subtaskStartArtifacts`
- `skillbill.application.workflow.updateGoalParentForBlockedPhaseRetry`
- `skillbill.goalrunner.model.GoalAttemptLedger.toArtifactList`
- `skillbill.goalrunner.model.GoalAttemptLedgerEntry.toArtifactMap`
- `skillbill.goalrunner.model.GoalRunnerStatusProjection.latestObservabilityEvent`
- `skillbill.goalrunner.model.GoalRunnerStatusProjectionRuntimeInputs.latestObservabilityEvent`
- `skillbill.goalrunner.model.GoalRunnerStatusProjector.project`
- `skillbill.install.model.InstallPlanWireValidator.validate`
- `skillbill.install.model.buildInstallPlanWireMap`
- `skillbill.goalrunner.backwardEdgeCountsFromLedger`
- `skillbill.goalrunner.blockedReasonFrom`
- `skillbill.goalrunner.commitShaFrom`
- `skillbill.goalrunner.declaredProgressEventFrom`
- `skillbill.goalrunner.derivedTerminalOutcomeFor`
- `skillbill.goalrunner.goalContinuationOutcome`
- `skillbill.ports.goalrunner.persistence.model.GoalChildPlanningHydrationResult.artifacts`
- `skillbill.ports.goalrunner.persistence.model.GoalChildPlanningHydrationResult.stepUpdates`
- `skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyStateInit.artifacts`
- `skillbill.ports.goalrunner.persistence.model.HistoryArtifactAppend.entryMap`
- `skillbill.goalrunner.progressEventFrom`
- `skillbill.goalrunner.terminalOutcomeFor`
- `skillbill.goalrunner.toArtifactMap`
- `skillbill.goalrunner.toArtifactsMap`
- `skillbill.ports.goalrunner.runner.GoalRunnerTerminalOutcomeStore.recoverMissingResultPrefixOutput`
- `skillbill.ports.goalrunner.runner.GoalRunnerWorkflowProgressStore.progressEvents`
- `skillbill.workflow.taskruntime.phaseartifacts.decodeStrictKeyedArtifactMap`
- `skillbill.workflow.taskruntime.phaseartifacts.decomposeTerminalFrom`
- `skillbill.workflow.taskruntime.phaseartifacts.goalContinuationFieldAdoptionFrom`
- `skillbill.workflow.taskruntime.phaseartifacts.operatorBlockRetryFrom`
- `skillbill.workflow.taskruntime.phaseartifacts.phaseLedgerFrom`
- `skillbill.workflow.taskruntime.phaseartifacts.phaseRecordsFrom`
- `skillbill.workflow.taskruntime.phaseartifacts.resolvedBranchFrom`
- `skillbill.workflow.taskruntime.phaseartifacts.reviewGenerationFrom`
- `skillbill.ports.review.model.GovernedReviewEvidenceCodec.TOOL_SPECS`
- `skillbill.ports.review.model.GovernedReviewEvidenceCodec.discoveryRequest`
- `skillbill.ports.review.model.GovernedReviewEvidenceCodec.expansionRequest`
- `skillbill.ports.review.model.GovernedReviewEvidenceCodec.payload`
- `skillbill.ports.review.model.GovernedReviewEvidenceCodec.readRequest`
- `skillbill.ports.review.model.ReviewAccountingRecord.boundedPayload`
- `skillbill.ports.validation.model.ReleaseRefMetadata.toPayload`
- `skillbill.ports.validation.model.RepoValidationReport.toPayload`
- `skillbill.ports.workflow.decomposition.DecompositionManifestPersistencePort.encodeManifestYaml`
- `skillbill.application.decomposition.DecompositionManifestWriter.manifestFromWorkflowUpdate`
- `skillbill.application.decomposition.DecompositionManifestWriter.maybeWriteFromWorkflowUpdate`
- `skillbill.application.decomposition.DecompositionManifestWriter.writeFromWorkflowUpdate`
- `skillbill.workflow.decomposition.runtime.decodeArtifactKeys`
- `skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestRuntimeUpdate.artifactsPatch`
- `skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestRuntimeUpdate.existingArtifacts`
- `skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestRuntimeUpdate.stepUpdates`
- `skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestWorkflowProjectionInput.artifactsPatch`
- `skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestWriteRequest.planningResult`
- `skillbill.ports.workflow.decomposition.runtime.model.DecompositionPlanManifestInput.artifactsPatch`
- `skillbill.ports.workflow.decomposition.runtime.model.DecompositionPlanManifestInput.existingArtifacts`
- `skillbill.ports.workflow.decomposition.runtime.model.DecompositionPlanManifestInput.plan`
- `skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput.toArtifactMap`
- `skillbill.goalrunner.GoalObservabilityArtifacts.patchForProgressEvent`
- `skillbill.goalrunner.GoalObservabilityArtifacts.patchForRuntimeEvent`
- `skillbill.goalrunner.model.GoalObservabilityProgressInput.artifacts`
- `skillbill.goalrunner.model.GoalObservabilityRuntimeEventInput.artifacts`
- `skillbill.ports.workflow.sessionSummary`
- `skillbill.ports.workflow.model.toPayload`
- `skillbill.review.context.ReviewContextEnvelopeValidator.validate`
- `skillbill.review.context.ReviewContextEnvelopeValidator.validateSpecIntentProjection`
- `skillbill.scaffold.model.PlatformManifest.customFields`
- `skillbill.telemetry.model.TelemetryConfigDocument.payload`
- `skillbill.telemetry.model.TelemetryProxyCapabilities.additionalFields`
- `skillbill.telemetry.model.TelemetryRemoteStatsResult.metrics`
- `skillbill.workflow.decomposition.DecompositionManifestValidator.validate`
- `skillbill.workflow.decomposition.DecompositionManifestValidator.validateYamlText`
- `skillbill.application.workflow.WorkflowWireProjections.compactContinueMap`
- `skillbill.application.workflow.WorkflowWireProjections.continueMap`
- `skillbill.application.workflow.WorkflowWireProjections.inputProjectionMap`
- `skillbill.application.workflow.WorkflowWireProjections.resumeMap`
- `skillbill.application.workflow.WorkflowWireProjections.snapshotMap`
- `skillbill.application.workflow.WorkflowWireProjections.summaryMap`
- `skillbill.application.workflow.WorkflowWireProjections.updateAcknowledgementMap`
- `skillbill.workflow.engine.model.WorkflowContinuationArtifactSummary.value`
- `skillbill.workflow.engine.model.WorkflowContinueView.extraFields`
- `skillbill.workflow.engine.model.WorkflowContinueView.sessionSummary`
- `skillbill.workflow.engine.model.WorkflowContinueView.stepArtifacts`
- `skillbill.workflow.engine.model.WorkflowInputProjection.artifacts`
- `skillbill.workflow.engine.model.WorkflowSnapshotView.artifacts`
- `skillbill.workflow.engine.model.WorkflowUpdateInput.artifactsPatch`
- `skillbill.workflow.engine.model.WorkflowUpdateInput.stepUpdates`
- `skillbill.workflow.goal.GoalObservabilityEventValidator.validate`
- `skillbill.workflow.goal.GoalPlanningPreparationEnvelopeValidator.validate`
- `skillbill.workflow.goal.GoalProgressEventValidator.validate`
- `skillbill.workflow.goal.model.GoalObservabilityEvent.toArtifactMap`
- `skillbill.workflow.goal.model.GoalObservabilityEvent.toCompactSummaryMap`
- `skillbill.workflow.goal.model.GoalObservabilityHistory.toArtifactList`
- `skillbill.workflow.goal.model.GoalProgressEvent.toArtifactMap`
- `skillbill.workflow.goal.model.GoalProgressHistory.toArtifactList`
- `skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition.fromArtifactMap`
- `skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition.toArtifactMap`
- `skillbill.workflow.goal.model.GoalSubtaskCommitFocusedAccounting.fromArtifactMap`
- `skillbill.workflow.goal.model.GoalSubtaskCommitFocusedAccounting.toArtifactMap`
- `skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder.decode`
- `skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder.decodeContinuationOnly`
- `skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly`
- `skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding.fromArtifactMap`
- `skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding.toArtifactMap`
- `skillbill.workflow.goal.model.GoalSubtaskReviewPassResult.fromArtifactMap`
- `skillbill.workflow.goal.model.GoalSubtaskReviewPassResult.toArtifactMap`
- `skillbill.workflow.goal.model.GoalSubtaskReviewState.boundedDispositionSummary`
- `skillbill.workflow.goal.model.GoalSubtaskReviewState.fromArtifactMap`
- `skillbill.workflow.goal.model.GoalSubtaskReviewState.toArtifactMap`
- `skillbill.workflow.goal.model.appendBoundedHistoryBySequence`
- `skillbill.workflow.goal.model.goalObservabilityHistoryFromArtifacts`
- `skillbill.workflow.goal.model.goalObservabilityLatestEventFromArtifacts`
- `skillbill.workflow.goal.model.goalObservabilityLatestEventForLiveness`
- `skillbill.ports.idestatus.IdeStatusValidator.validate`
- `skillbill.workflow.taskruntime.FeatureTaskRuntimeBuildReceiptValidator.validateBuildReceipt`
- `skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator.validateEnvelope`
- `skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator.validateDeclaration`
- `skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator.validateMeasurement`
- `skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator.validatePersistenceRecord`
- `skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator.validateSharedEvidenceProjection`
- `skillbill.workflow.taskruntime.FeatureTaskRuntimeImplementationAttemptValidator.validateImplementationAttemptRecord`
- `skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator.validateAndReadPhaseOutput`
- `skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator.validatePlanningProjection`
- `skillbill.workflow.taskruntime.FeatureTaskRuntimeQuarantineValidator.validateQuarantineRecord`
- `skillbill.workflow.taskruntime.ProsePhaseOutputSynthesizer.envelopeFromSettlement`
- `skillbill.workflow.taskruntime.ProsePhaseOutputSynthesizer.trySynthesize`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticDegradationMeasurement.toTelemetryMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticSignal.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticSignal.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationFieldAdoption.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationFieldAdoption.toArtifactMap`
- `skillbill.goalrunner.model.FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap`
- `skillbill.goalrunner.model.FeatureTaskRuntimeGoalContinuationOutcome.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalPlanningImport.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope.fromEnvelopeMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope.toEnvelopeMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjection.toEnvelopeMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef.toDeclarationMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory.fromMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionMeasurement.toTelemetryMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptCheckpoint.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptCheckpoint.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptDeviation.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptDeviation.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptReconciliation.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptReconciliation.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRejectionMeasurement.toTelemetryMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairConstruct.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairConstruct.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairDisturbedRemedy.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairDisturbedRemedy.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedgerEntry.toProjectionMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedgerProjection.toProjectionMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt.validateEntries`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceiptEntry.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint.toEnvelopeMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement.toTelemetryMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRunRecord.toArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.toArtifactMap`
- `skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput.envelope`
- `skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration.fromArtifactMap`
- `skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration.toArtifactMap`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesFromArtifact`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesToArtifact`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimeDecomposePlanOutcomeOrNull`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimeDiagnosticSignalsFromWire`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptRecordToWire`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptsFromWire`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimeIsDecompositionPackage`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimePlanningProjectionFromEnvelope`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineEntriesFromWire`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineRecordToWire`
- `skillbill.workflow.taskruntime.model.featureTaskRuntimeRunInvariantsFromArtifactMap`
- `skillbill.workflow.taskruntime.model.toArtifactMap`

- `skillbill.application.decomposition.decodeArtifacts`
- `skillbill.application.decomposition.decodeDecompositionManifestMap`
- `skillbill.application.decomposition.encodeDecompositionManifestMap`
- `skillbill.application.decomposition.manifestPathFromArtifacts`
- `skillbill.engine.goalrunner.GoalParentProjectionWriter.artifacts`
- `skillbill.engine.goalrunner.goalContinuation`
- `skillbill.engine.goalrunner.goalReviewArtifacts`
- `skillbill.engine.goalrunner.goalReviewEmissionEnvelope`
- `skillbill.engine.goalrunner.maxHistorySequence`
- `skillbill.engine.goalrunner.missingResultPrefixTerminalOutcomeArtifact`
- `skillbill.engine.goalrunner.planning.model.GoalChildPlanningHydration.artifacts`
- `skillbill.engine.goalrunner.planning.model.GoalChildPlanningHydration.stepUpdates`
- `skillbill.engine.goalrunner.toArtifactMap`
- `skillbill.engine.goalrunner.toArtifactsMap`
- `skillbill.application.workflow.outOfBandAcceptancesFromLegacyArtifacts`
- `skillbill.application.workflow.reviewPolicyFromLegacyArtifacts`

### private_serializer

_None — placeholder._

### postponed_with_reason

- `skillbill.workflow.decomposition.DecompositionManifestCodec.decodeMap` [subtask 4] — decomposition manifest codec entrypoint.
- `skillbill.workflow.decomposition.toWireMap` [subtask 4] — decomposition manifest wire-map encoder.
- `skillbill.workflow.engine.WorkflowEngine.continueDecision` [subtask 4] — workflow-engine continue-decision raw-map seam.

<!-- skill-52-2-inventory:end -->

# Wire vocabulary

Runtime-domain wire-token declarations own closed enum tokens and their aliases. Runtime-contracts
`*Keys` declarations own durable and wire payload keys; `SharedPayloadKeys` is the shared owner for
the workflow envelope keys. `ProsePhaseOutputParse` delegates status normalization to
`SettlementStatus`, while `DecompositionStatus` retains its separate `completed` input alias and
`complete` output token. `WireVocabularyArchitectureTest` discovers every runtime main source
through `RuntimeModuleCatalog`, indexes declarations with source locations, rejects same-owner
duplicates and local vocabulary restatements, and reports the measured baseline-to-final delta.
Identical spellings in different enums remain separate when their decoding context differs; the
owner and decoder are recorded together so a lexical match cannot silently change wire behavior. A
collection literal restates a token only inside that owner's decoding context — the file names the
owning type or declares it in its own package — so a generic word such as `error` or `type` in an
unrelated payload is not a restatement of an enum that happens to spell it the same way.

# Native-agent installation integrity

Native-agent rendering promotes artifacts atomically into the installed cache and records each
Skill Bill-managed link in the user-home `.skill-bill/native-agent-link-inventory.json`. The inventory stores
the logical worker name, provider, installed path, cache target, and content digest. Reconciliation
uses the complete prior inventory to remove obsolete or dangling managed links; it never deletes a
regular file or a symlink that no longer resolves to its recorded managed target. Install verifies
the linked artifact's logical name, digest, target, and readability before committing the inventory.

# Delegated code-review architecture

**One authoritative preparation.** `ParallelCodeReviewRunner` resolves scope, diff, dominant stack,
rubrics, and project rules once for the whole review, then hands every top-level lane the same
immutable parent packet. A lane never re-resolves a fact the parent already established, and the
review runs one scope-discovery command regardless of how many specialists it launches.

**Flattened manifest layering.** `ReviewLaunchPlanPolicy.flatten` walks a routed pack's declared
composition and emits one direct specialist lane per selected area, with the nearest owning layer
winning and the full origin-layer chain retained for attribution. A composed root such as `kmp`
therefore expands straight to its own specialists plus the required baseline specialists; the
baseline review skill is never launched as a nested orchestrator.

**Forbidden child rediscovery.** `ReviewOperationPolicy` classifies every operation a specialist
requests without consulting platform, pack, or provider identity. Repository status, scope and
base/head discovery, diff recomputation, build and test invocation, pack and add-on resolution,
routing, learnings resolution, telemetry ownership, project-guidance traversal, and opaque searches
are refused because the parent packet already carries those facts. Project guidance reaches a
specialist only as packet-attested matched rule references, never as a file body.

**Bounded evidence and expansion ledger.** `ReviewEvidenceBroker` is the single measured surface a
specialist may act through. Assigned paths are served in bounded batches; anything outside the
assignment needs an authorized expansion whose record belongs to the parent packet's expansion
ledger and whose assignment digest must match the requesting lane. Once a lane produces a terminal
outcome the broker keeps returning that outcome instead of serving more context.

**Native-agent preflight.** When delegated execution selects provider-native specialists, every
`(agent, logical worker)` assignment is verified against the managed native-agent link inventory
before any worker starts. A missing, stale, or dangling link fails the whole review with
`MissingInstalledNativeAgentError` and its governed repair command; there is no generic-worker
fallback.

**Independent parallel lanes.** The two top-level lanes share the parent packet and nothing else.
Each holds its own assignments, evidence brokers, budgets, and accounting nodes, so one lane's
budget termination, timeout, or process failure never disturbs its sibling. Accounting folds each
session exactly once: direct usage sums owned sessions, an inclusive provider report already
containing its descendants is never added to them again, and counters aggregate the same way.

# Closed status-family inventory

Closed workflow and decomposition decisions use the domain-owned `DecompositionStatus`,
`WorkflowStatus`, and `WorkflowStepStatus` vocabularies. Their `wireValue` members are the only
declarations of the supported tokens, and `fromWire` is used at durable-map seams. The following
fields are either typed here or remain open because their values are supplied by a provider, a pack,
or a versioned durable payload whose vocabulary is intentionally owned by that boundary:

- `skillbill.goalrunner.model.GoalRunnerLivenessSnapshot.processState`,
  `skillbill.ports.agentrun.model.AgentRunLivenessSnapshot.processState`, and
  `skillbill.goalrunner.model.GoalRunnerSupervisionEvent.continuationMode`/`processState` use the
  domain-owned `GoalRunnerProcessState` and `GoalRunnerContinuationMode` enums. Their artifact
  writers emit `wireValue`, and the supervision projection uses the explicit `UNKNOWN` process
  state when no liveness snapshot is available.
- `skillbill.goalrunner.model.GoalRunnerLivenessSnapshot.livenessState` and
  `skillbill.ports.agentrun.model.AgentRunLivenessSnapshot.livenessState` use
  `GoalRunnerLivenessState`, as does
  `skillbill.goalrunner.model.GoalRunnerLivenessDecision.state`;
  `skillbill.goalrunner.model.GoalPlanningStatusSnapshot.state` and
  `skillbill.application.idestatus.model.IdeStatusPlanning.state` use `GoalPlanningStatusState`;
  `skillbill.goalrunner.model.GoalRunnerStatusProjection.executionLiveness` and
  `skillbill.goalrunner.model.GoalRunnerStatusProjectionRuntimeInputs.executionLiveness` use
  `ExecutionLiveness`; `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedgerEntry.status`
  uses `FeatureTaskRuntimeRepairLedgerStatus`; and
  `skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership.leaseState` uses
  `FeatureTaskRuntimeWorkerLeaseState`. Each owning enum provides the sole `wireValue`/`fromWire`
  mapping for its fields.

- `skillbill.workflow.decomposition.model.DecompositionManifest.status` and
  `DecompositionSubtask.status`: legacy manifest state accepts unknown future values and keeps the
  raw wire value for forward-compatible read and rewrite.
- `skillbill.workflow.engine.model.WorkflowStepState.status`, `WorkflowStateSnapshot.workflowStatus`,
  and `Workflow*View.workflowStatus`: workflow definitions are pack-owned and may add statuses;
  typed branches use the shared vocabulary where the runtime makes a closed decision.
- `skillbill.ports.workflow.model.WorkflowStateRecord.workflowStatus`: this is the persisted port
  record crossing the SQLite and workflow-engine compatibility seam, so it preserves unknown
  definition values; consumers convert it with `workflowStatus()` before making closed decisions.
- `skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate.workflowStatus`
  and `skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestRuntimeUpdate.workflowStatus`
  are read from SQLite worker/decomposition update rows and preserve the workflow-definition token
  while crossing worker and decomposition update ports; their consumers convert it with
  `workflowStatus()` before closed dispatch.
- `skillbill.goalrunner.model.GoalRunnerObservabilityProgressInput.workflowStatus` preserves the
  caller-supplied workflow-definition token while `WorkflowServiceInputMapping` projects
  observability from durable artifacts; it is not a process or goal status owned by the
  observability model.
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.status` is owned by
  `WorkflowStepStatus`, and `FeatureTaskRuntimeGoalContinuationOutcome.status` is owned by
  `GoalRunnerTerminalStatus`; both durable decoders reject unknown values at their artifact seams.
- `skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseStatus.status` remains a
  presentation string; `FeatureTaskRuntimeStatusService` decodes it with `WorkflowStepStatus`
  before count and phase-selection decisions and emits the enum's `wireValue`.
- `skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskOutcome.status` is owned by
  `GoalRunnerTerminalStatus`; `FeatureTaskRuntimeRunnerLaunchOutcomes` uses exhaustive typed
  dispatch and the CLI presentation emits its `wireValue`.
- `skillbill.telemetry.model.SyncResult.status` is owned by `TelemetrySyncStatus`; the remaining
  `FeatureTaskRuntimeFinishedRecord.completionStatus`, `QualityCheckFinishedRecord.result`,
  `FeatureVerifyFinishedRecord.auditResult`/`completionStatus`,
  `GoalStartedRecord.status`, `GoalSubtaskFinishedRecord.status`, `GoalFinishedRecord.status`,
  `GoalIssueFinishedRecord.status`, and telemetry mode fields are provider and telemetry labels
  owned by their emitting contracts.
- `skillbill.goalrunner.model.GoalRunnerProgressEvent.kind`,
  `skillbill.goalrunner.model.GoalRunnerLivenessSnapshot.phase`,
  `skillbill.goalrunner.model.GoalRunnerSupervisionEvent.phase`,
  `skillbill.review.context.model.ReviewContextPacket.status`, and
  `skillbill.review.context.model.ReviewBuildTestFact.kind`/`outcome` are exact open labels owned by
  their emitting contracts. `skillbill.ports.scaffold.model.ScaffoldBaselineLayer.mode` is the
  presentation of a pack-owned baseline mode and remains open at that port boundary.
  `ImportedReview.executionMode` and `ReviewSummary.executionMode` are
  owned by `ReviewExecutionMode`; the decoder preserves nullable absence and emits `wireValue`.
- `skillbill.review.model.ReviewRunLane.resolutionState` is owned by
  `ReviewLaneResolutionState`, and `ReviewRunLane.reviewDisposition` is owned by
  `ReviewLaneReviewDisposition`; SQLite legacy null or unknown values fail closed to unresolved
  and incomplete before application dispatch.
- `skillbill.ports.review.model.ReviewScopeFacts.status`,
  `skillbill.review.model.GoalRunSummary.status` preserve review-store and provider status labels;
  the review scope and stats boundaries own those vocabularies and do not make closed decisions
  from the raw values.
- `skillbill.ports.scaffold.model.ScaffoldSkillStatus.completionStatus` is owned by
  `ScaffoldCompletionStatus`, and `ScaffoldSectionStatus.status` is owned by
  `ScaffoldSectionCompletionStatus`; both use their enum `wireValue`/`fromWire` pair at the
  authoring adapter boundary. `ScaffoldValidateResult.mode` and `.status` use
  `ScaffoldValidationMode` and `ScaffoldValidationStatus`, respectively, with decoding at the
  authoring adapter boundary. `skillbill.scaffold.model.ScaffoldModels.mode`/`kind` and
  `skillbill.ports.agentrun.model.AgentRunLauncherModels.phase` remain open because their values
  are extension-owned labels. `GoalPlanningBoundaryHeading.kind` is owned by
  `GoalPlanningBoundaryHeadingKind`; `GoalPlanningContext` has no `kind` field.
- `skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement.kind` uses the sealed
  `FeatureTaskPhaseSettlementKind`. Service-produced values use its three canonical cases, while
  durable reads retain an unknown wire token in `Unknown` for forward-compatible rewrite.
- `skillbill.learnings.model.LearningRecord.status` and `LearningEntry.status` remain external
  learning labels whose contracts validate presence and shape; the producer owns the
  vocabulary.
- `skillbill.workflow.taskruntime.model.SettlementEnvelopeRequest.status` uses
  `SettlementStatus`; prose settlement accepts only the completed, blocked, and failed members.
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause.pauseKind` uses
  `FeatureTaskRuntimeAuditGapPauseKind` at its durable artifact boundary.
- `skillbill.review.context.model.ReviewAccountingInput.terminalOutcome` and
  `ReviewAccountingNode.terminalOutcome` use `ReviewAccountingTerminalOutcome`; integration
  accounting continues to use `ReviewIntegrationTerminalOutcome`.
- `skillbill.workflow.goal.model.GoalSubtaskCommitFocusedAccounting.integrationTerminalOutcome`
  uses `ReviewIntegrationTerminalOutcome`; durable artifact decoding uses `fromWire` and emission
  uses `wireValue`, preserving the existing integration tokens, unknown-value rejection, and
  skipped-pass reason rule.
- `skillbill.review.context.model.ReviewBudgetOutcome.budgetKind` uses `ReviewBudgetKind`; all
  budget dimensions are decoded once at the review budget seam and emitted through `wireValue`.
