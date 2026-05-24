# Skill Bill Runtime Architecture

This document defines the enforced architecture for `runtime-kotlin`.

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
        -> runtime-contracts helpers where domain wire-map code requires them

runtime-infra-fs / runtime-infra-http / runtime-infra-sqlite
  -> runtime-ports + runtime-domain + runtime-contracts

runtime-core
  -> application services, ports, domain, and concrete adapters for DI wiring
```

## Gradle Modules

- `runtime-contracts`: contract DTOs, JSON helpers, runtime surface contracts,
  contract-version constants, runtime/schema parse-seam validators, schema
  resource copy tasks, and runtime exception types.
- `runtime-domain`: pure learning, review, telemetry, workflow, install-plan,
  scaffold, and skill-remove models/rules. Public domain data types live in
  area-owned `model` packages.
- `runtime-ports`: `skillbill.model.RuntimeContext`, persistence sessions,
  repositories, gateway interfaces, telemetry port interfaces, workflow git
  operations, decomposition-manifest file-store ports, and port-owned model
  types.
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
  skill-remove filesystem cascades.
- `runtime-core`: `RuntimeModule`, Kotlin-Inject component definitions, and DI
  providers. It may know concrete adapters only inside composition code.
- `runtime-cli`: Clikt command tree, option validation, terminal rendering,
  JSON output, help, completion surfaces, and CLI runtime context creation.
- `runtime-desktop`: optional Compose Multiplatform JVM desktop app. It owns
  the desktop app-shell, navigation, Room/datastore state, design system,
  desktop feature screens, and desktop data gateways. Shared governed behavior
  stays in runtime services and ports.
- `runtime-mcp`: MCP adapter surface, MCP-specific payload shaping, stdio
  server, MCP telemetry schema validation, and MCP runtime context creation.

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
  `skillbill.ports.*.model`.
- `skillbill.contracts.*`: contract DTOs, JSON helpers, runtime surface
  contracts, and schema validators. Mapping from application/domain/port models
  into contract DTOs belongs in application or adapter-owned packages.
- `skillbill.error`: runtime exception taxonomy.
- `skillbill.workflow` and `skillbill.workflow.model`: pure workflow engine,
  workflow definitions, decomposition manifest codec, wire-map conversion, and
  workflow/decomposition models owned by `runtime-domain`.
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
   ports, infrastructure, entry adapters, or composition roots.
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
    `skillbill.infrastructure.fs`; telemetry proxy DTOs belong in
    `skillbill.contracts.telemetry`; telemetry proxy payload mapping belongs
    with the HTTP adapter.
11. JSON maps, YAML maps, MCP payloads, CLI JSON payloads, and terminal strings
    are boundary concerns. Internal use cases expose typed models.

    **Raw Map Boundary Rule (SKILL-52.1):** public declarations on
    `runtime-application`, `runtime-domain`, and `runtime-ports` MUST NOT
    return or accept `Map<String, Any?>`, `Map<String, *>`, or
    `MutableMap<String, Any?>` unless they are either (a) listed by FQN
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
    - **Subtask 3 will remove:** system service / install policy
      surfaces.
    - **Subtask 4 will remove:** lifecycle/review/telemetry payload
      surfaces and supporting top-level helpers.

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

    **Deferred desktop debt (SKILL-52.1 subtask 3, F-002):** the
    desktop adapter at
    `runtime-desktop/core/data/.../RuntimeRepoBrowserService.kt` is
    currently a third reader of the `@OpenBoundaryMap` `payload` map
    for `list`, `show`, `validate`, and `saveExactContent`. Lifting
    the structural list contents to typed fields (e.g.
    `skills: List<ScaffoldListEntry>`) is intentionally deferred to
    SKILL-52.1 subtask 4 — subtask 3 retired the gateway raw-map
    return types but did not migrate the desktop projection mappers,
    so the desktop service still reaches into `payload` for the
    nested structural fields. This is documented debt, not silently
    accepted drift.

    Service/gateway PUBLIC APIs MAY NOT return raw `Map<String, Any?>`.
    Once a producer is typed (subtask 3 retired the eight
    `ScaffoldGateway` raw-map producers — `list`, `show`, `explain`,
    `validate`, `upgrade`, `fill`, `saveExactContent`, `editWithBodyFile`),
    re-adding a raw-map return type at the service/gateway level
    requires an explicit allow-list entry AND a documented rationale.
    The pattern's exemplars are `PlatformManifest.customFields` (open
    boundary for schema custom fields), `WorkflowSnapshotView.artifacts`
    (durable workflow artifacts passthrough), and the eight scaffold
    typed-result-model `payload` fields enumerated below.

    <!-- open-boundary-allowlist:start -->

    - `skillbill.workflow.WorkflowEngine.snapshotMap`
    - `skillbill.workflow.WorkflowEngine.summaryMap`
    - `skillbill.workflow.WorkflowEngine.resumeMap`
    - `skillbill.workflow.WorkflowEngine.continueMap`
    - `skillbill.workflow.WorkflowEngine.continueDecision`
    - `skillbill.workflow.DecompositionManifestCodec.decodeMap`
    - `skillbill.workflow.toWireMap`
    - `skillbill.application.decodeDecompositionManifestMap`
    - `skillbill.application.encodeDecompositionManifestMap`
    - `skillbill.application.DecompositionManifestWriter.writeFromWorkflowUpdate`
    - `skillbill.application.DecompositionManifestWriter.manifestFromWorkflowUpdate`
    - `skillbill.application.DecompositionManifestWriter.maybeWriteFromWorkflowUpdate`
    - `skillbill.application.WorkflowFamily.sessionSummary`
    - `skillbill.application.ScaffoldService.scaffold`
    - `skillbill.ports.scaffold.ScaffoldGateway.scaffold`
    - `skillbill.scaffold.policy.requireString`
    - `skillbill.scaffold.policy.requireStringOrDefault`
    - `skillbill.scaffold.policy.validatePayloadVersion`
    - `skillbill.scaffold.policy.detectKind`
    - `skillbill.scaffold.policy.optionalSpecialistSubagents`
    - `skillbill.scaffold.policy.rejectLeafSubagentSpecialists`
    - `skillbill.scaffold.policy.rejectBaselineLayersForNonPlatformPack`
    - `skillbill.scaffold.policy.resolvePlatformPackSelection`
    - `skillbill.scaffold.policy.resolvePlatformPackDefaults`
    - `skillbill.application.SystemService.doctor`
    - `skillbill.application.SystemService.version`
    - `skillbill.application.lifecycleOkPayload`
    - `skillbill.application.lifecycleSkippedPayload`
    - `skillbill.application.lifecycleErrorPayload`
    - `skillbill.application.orchestratedStartedSkippedPayload`
    - `skillbill.application.orchestratedPayload`
    - `skillbill.application.LifecycleTelemetryService.featureImplementStarted`
    - `skillbill.application.LifecycleTelemetryService.featureImplementFinished`
    - `skillbill.application.LifecycleTelemetryService.qualityCheckStarted`
    - `skillbill.application.LifecycleTelemetryService.qualityCheckFinished`
    - `skillbill.application.LifecycleTelemetryService.featureVerifyStarted`
    - `skillbill.application.LifecycleTelemetryService.featureVerifyFinished`
    - `skillbill.application.LifecycleTelemetryService.prDescriptionGenerated`
    - `skillbill.application.ReviewService.previewImport`
    - `skillbill.application.ReviewService.importReview`
    - `skillbill.application.ReviewService.reviewFinishedTelemetryPayload`
    - `skillbill.application.ReviewService.recordFeedback`
    - `skillbill.application.ReviewService.telemetryPayload`
    - `skillbill.application.ReviewService.reviewStats`
    - `skillbill.application.ReviewService.featureImplementStats`
    - `skillbill.application.ReviewService.featureVerifyStats`
    - `skillbill.application.telemetryPayload`
    - `skillbill.application.TelemetryService.status`
    - `skillbill.application.TelemetryService.setLevel`
    - `skillbill.application.TelemetryService.capabilities`
    - `skillbill.application.TelemetryService.remoteStats`
    - `skillbill.telemetry.defaultLocalTelemetryConfig`
    - `skillbill.telemetry.validateRemoteStatsCapabilities`
    - `skillbill.telemetry.TelemetryConfigRuntime.defaultLocalConfig`
    - `skillbill.telemetry.TelemetryConfigRuntime.readLocalConfig`
    - `skillbill.telemetry.TelemetryConfigRuntime.ensureLocalConfig`
    - `skillbill.telemetry.TelemetryHttpRuntime.fetchProxyCapabilities`
    - `skillbill.telemetry.TelemetryHttpRuntime.fetchRemoteStats`
    - `skillbill.telemetry.TelemetrySyncRuntime.syncResultPayload`
    - `skillbill.telemetry.TelemetrySyncRuntime.telemetryStatusPayload`
    - `skillbill.ports.persistence.ReviewRepository.updateReviewFinishedTelemetryState`
    - `skillbill.ports.persistence.ReviewRepository.recordFeedback`
    - `skillbill.ports.persistence.ReviewRepository.reviewStatsPayload`
    - `skillbill.ports.persistence.ReviewRepository.featureImplementStatsPayload`
    - `skillbill.ports.persistence.ReviewRepository.featureVerifyStatsPayload`
    - `skillbill.ports.telemetry.TelemetryClient.fetchProxyCapabilities`
    - `skillbill.ports.telemetry.TelemetryClient.fetchRemoteStats`
    - `skillbill.ports.telemetry.TelemetryConfigStore.read`
    - `skillbill.ports.telemetry.TelemetryConfigStore.ensure`
    - `skillbill.ports.telemetry.TelemetryConfigStore.write`
    - `skillbill.learnings.learningPayload`
    - `skillbill.learnings.learningSummaryPayload`
    - `skillbill.learnings.scopeCounts`
    - `skillbill.learnings.learningSessionJson`
    - `skillbill.learnings.summarizeLearningReferences`
    - `skillbill.learnings.learningEntryPayload`
    - `skillbill.application.model.WorkflowUpdateRequest.stepUpdates`
    - `skillbill.application.model.WorkflowUpdateRequest.artifactsPatch`
    - `skillbill.application.model.FeatureImplementFinishedRequest.childSteps`
    - `skillbill.application.model.TelemetrySyncPayload.payload`
    - `skillbill.application.model.TriageResult.payload`
    - `skillbill.application.model.TriageResult.telemetryPayload`
    - `skillbill.application.model.DecompositionManifestWriteRequest.planningResult`
    - `skillbill.application.model.DecompositionManifestRuntimeUpdate.stepUpdates`
    - `skillbill.application.model.DecompositionManifestRuntimeUpdate.artifactsPatch`
    - `skillbill.application.model.DecompositionManifestRuntimeUpdate.existingArtifacts`
    - `skillbill.install.model.buildInstallPlanWireMap`
    - `skillbill.scaffold.model.PlatformManifest.customFields`
    - `skillbill.ports.scaffold.catalog.model.ScaffoldListResult.payload`
    - `skillbill.ports.scaffold.catalog.model.ScaffoldShowResult.payload`
    - `skillbill.ports.scaffold.catalog.model.ScaffoldExplainResult.payload`
    - `skillbill.ports.scaffold.repo.model.ScaffoldValidateResult.payload`
    - `skillbill.ports.scaffold.repo.model.ScaffoldUpgradeResult.payload`
    - `skillbill.ports.scaffold.source.model.ScaffoldFillResult.payload`
    - `skillbill.ports.scaffold.source.model.ScaffoldSaveExactContentResult.payload`
    - `skillbill.ports.scaffold.source.model.ScaffoldEditWithBodyFileResult.payload`
    - `skillbill.telemetry.model.FeatureImplementFinishedRecord.childSteps`
    - `skillbill.workflow.model.WorkflowSnapshotView.artifacts`
    - `skillbill.workflow.model.WorkflowContinueView.stepArtifacts`
    - `skillbill.workflow.model.WorkflowContinueView.extraFields`
    - `skillbill.workflow.model.WorkflowContinueView.sessionSummary`
    - `skillbill.workflow.model.WorkflowUpdateInput.stepUpdates`
    - `skillbill.workflow.model.WorkflowUpdateInput.artifactsPatch`
    - `skillbill.workflow.model.DecompositionSubtask.reviewResult`
    - `skillbill.workflow.model.DecompositionSubtask.auditResult`
    - `skillbill.workflow.model.DecompositionSubtask.validationResult`
    - `skillbill.ports.validation.model.RepoValidationReport.toPayload`
    - `skillbill.ports.validation.model.ReleaseRefMetadata.toPayload`

    <!-- open-boundary-allowlist:end -->

    The allow-list grandfathers legacy raw-map surfaces. The rule
    applies prospectively: new public declarations cannot join the
    legacy raw-map surface without being added to both the allow-list
    constant and this section in the same change.
12. Public data, enum, and sealed declarations in application, domain, and port
    modules live under explicit `model` packages. Services, runtimes, and port
    interfaces import those models instead of declaring public models inline.
13. SQLite schema changes are append-only versioned migrations recorded in
    `schema_migrations`.

The subsystem package set is:

```text
skillbill.application
skillbill.boundary
skillbill.cli
skillbill.contracts
skillbill.db
skillbill.desktop
skillbill.di
skillbill.domain.skillremove
skillbill.error
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

## Runtime Contract And Schema Seams

- Runtime contract schemas live in `orchestration/contracts/`. JVM validators,
  contract-version constants, typed schema errors, and classpath resource copy
  tasks live in `runtime-contracts` unless a schema is owned by a single
  adapter surface.
- Workflow-state schema validation is owned by
  `skillbill.contracts.workflow.WorkflowStateSchemaValidator`. The owning read
  seam is `skillbill.workflow.WorkflowEngine`; durable record mapping stays
  pure and the next engine read rejects drift.
- Install-plan schema validation is owned by
  `skillbill.contracts.install.InstallPlanSchemaValidator`. The owning seams
  are install-plan building and CLI/MCP emission through the install
  application and filesystem adapter path.
- Decomposition-manifest schema validation is owned by
  `skillbill.contracts.workflow.DecompositionManifestSchemaValidator`. The
  owning parse/emission seam is
  `skillbill.application.DecompositionManifestFileWrites`, which validates
  YAML text and in-memory maps before workflow artifacts are persisted or
  returned. Repo-local manifest text persistence is owned by
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
- `runtime-core` does not re-export contract or concrete infrastructure modules
  as adapter API.
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
