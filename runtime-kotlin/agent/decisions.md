# runtime-kotlin/ boundary decisions

This file records architectural and implementation decisions that span the
`runtime-kotlin/` boundary. Each entry is dated and explains the trade-off,
not the implementation detail.

## 2026-05-29 — Ship desktop installers UNSIGNED for v1

**Context.** SKILL-55 subtask 2 produces native desktop installers (`.dmg`,
`.msi`, `.deb`, `.rpm`) via Compose's jpackage integration, each bundling its own
JRE. macOS Gatekeeper and Windows SmartScreen both warn on, or block, software
that is not signed with an Apple Developer ID (notarized) certificate or a
Windows Authenticode code-signing certificate respectively. We do not hold either
certificate for v1.

**Decision.** **SHIP UNSIGNED FOR V1.** We ship the installers unsigned and defer
code signing + Apple notarization to a later release. End users open the app
through the OS "open anyway" escape hatch; the exact steps below are recorded
verbatim so subtask 4 (post-install hint) and subtask 6 (launch FAQ) reuse the
same wording without re-deriving it.

**End-user open-anyway steps (verbatim, reuse these).**

- **macOS (Gatekeeper).** Right-click (or Control-click) the app in Finder ->
  **Open** -> **Open** in the confirmation dialog. Alternatively: **System
  Settings -> Privacy & Security -> Open Anyway**.
- **Windows (SmartScreen).** On the "Windows protected your PC" dialog, click
  **More info** -> **Run anyway**.

**Reason.** Acquiring and provisioning an Apple Developer ID certificate (+
notarization pipeline) and a Windows Authenticode certificate is cost and
process overhead not justified for a v1 launch. Unsigned distribution with
documented open-anyway steps unblocks shipping now; signing/notarization is a
tracked follow-up. The `.deb` / `.rpm` Linux packages have no equivalent
OS-level signing gate for local installs, so this trade-off is macOS/Windows
specific.

**Consumers.** Subtask 4 surfaces a post-install hint pointing at these steps;
subtask 6 embeds them in the launch FAQ. Keep the wording above as the single
source of truth.

## 2026-05-29 — Artifact FILENAME, not embedded version, is the source of truth (macOS diverges)

**Context.** SKILL-55 subtask 2 derives the embedded jpackage `--app-version` from
`project.version` (`0.1.0-SNAPSHOT`). jpackage requires a strict numeric
`MAJOR.MINOR.PATCH`, and macOS jpackage + the Compose Dmg validator additionally
require `MAJOR >= 1`. So `toMacAppVersion` bumps a zero major (`0.1.0` -> `1.1.0`)
for the macOS `.dmg` embedded version ONLY; Linux `.deb`/`.rpm` and Windows `.msi`
keep the honest `toJpackageVersion` (`0.1.0`). The embedded version therefore
deliberately DIVERGES across operating systems for the same build. Separately, the
canonical artifact FILENAME (`SkillBill-<project.version>-<os>-<arch>.<ext>`) uses
the full, un-stripped `project.version` uniformly across all operating systems.

**Decision.** The artifact **FILENAME** is the single source of truth for an
installer's version and for artifact resolution in subtask 3/4. Verifiers and
release tooling MUST resolve on the filename, never on the embedded installer
metadata — the embedded `--app-version` deliberately diverges on macOS
(`1.1.0` vs the filename's `0.1.0-SNAPSHOT`) and must not be treated as
authoritative.

**Reason.** macOS's `MAJOR >= 1` constraint forces a per-OS embedded-version
bump that the honest project version cannot satisfy, so embedded metadata is not
a stable cross-OS key. The full `project.version` in the filename is identical
across operating systems and carries the un-stripped qualifier (`-SNAPSHOT`),
making it the only consistent, honest resolution key.

**Consumers.** Subtask 3/4 artifact resolution/verification keys on the filename
token (`SkillBill-<project.version>-<os>-<arch>.<ext>`); do NOT parse the embedded
installer version.

## 2026-05-29 — Non-modular jlink images via Badass Runtime, not Badass JLink

**Context.** SKILL-55 subtask 1 needs self-contained, per-OS runtime images of
`runtime-cli` / `runtime-mcp` that run with no system JDK. The runtime modules are
plain non-modular Kotlin apps (no `module-info.java`), pulling in automatic
modules (kotlin-inject, kotlinx.serialization, jackson, networknt, sqlite-jdbc).

**Decision.** Use the Badass **Runtime** plugin (`org.beryx.runtime` 2.0.1), not
Badass **JLink** (`org.beryx.jlink`). Badass JLink requires a modular app and a
`module-info.java` — it has no non-modular path and loud-fails with "Cannot find
module-info.java". Badass Runtime is the Beryx plugin built for non-modular apps:
it links a trimmed JDK runtime with `jlink` and wraps the existing `application`
distribution, keeping the `bin/runtime-cli` / `bin/runtime-mcp` launchers. We pin
the link toolchain to Java 17 (matching `build-logic` `Jvm.kt` `JDK_VERSION`), set
an explicit `additive` module set (java.base/logging/management/naming/net.http/
sql/xml/desktop, jdk.crypto.ec, jdk.unsupported) instead of relying on jdeps (which
cannot resolve the automatic modules cleanly), and trim with `--strip-debug
--no-header-files --no-man-pages --compress 2`. `java.net.http` is required by the
telemetry HTTP client (`runtime-infra-http`), which the version/stdio smoke test
does not exercise. Image name/zip derive from `project.version` + a canonical
`<os>-<arch>` host token defined once, as a typed contract, in the
`skillbill.runtime-image` convention plugin
(`build-logic/convention/.../buildlogic/RuntimeTargets.kt`). The Badass Runtime tasks are not
configuration-cache compatible, so they opt out per-task via
`notCompatibleWithConfigurationCache`; the global config cache stays warm for
`check` / `installDist`.

**Reason.** GraalVM `native-image` was rejected: the reflection/serialization
surface of kotlin-inject + kotlinx.serialization + jackson + sqlite-jdbc would
require extensive reachability metadata and per-OS native toolchains for little
payoff over a trimmed jlink image. A hand-rolled `jlink`+`jpackage` script was
rejected to avoid re-implementing module resolution, launcher generation, and
per-OS zipping that Badass Runtime already provides. Badass JLink (the plan's
first choice) was rejected because it fundamentally cannot link a non-modular app.

## 2026-05-24 — Runtime paths stay inert outside adapters and composition

**Context.** SKILL-52.1 tightened hexagonal boundaries while several public
application/domain/port models still need to carry `java.nio.file.Path` values
for caller-provided homes, repo roots, and generated plan locations.

**Decision.** Keep `Path` legal as inert data in application/domain/port public
models, but ban filesystem IO, home expansion, process environment reads, and
system-property reads outside adapters or composition.

**Reason.** Replacing every path with strings would make typed runtime contracts
weaker, while allowing `Path` operations that touch the host would leak adapter
responsibilities back into domain and port code.

## 2026-05-24 — Preserve dual install-plan validation after policy extraction

**Context.** SKILL-52.1 moved install planning toward typed policy and
capability ports, but install-plan wire maps still cross two independent
emission seams: builder output and CLI JSON emission.

**Decision.** Keep the shared install-plan wire-snapshot validator at both the
builder seam and CLI emission seam after the refactor.

**Reason.** The builder proves the pure plan shape, while CLI emission can still
assemble or project a payload after planning; validating both seams preserves
the existing loud-fail contract instead of relying on one earlier check.

## 2026-05-24 — Runtime-core retains only generated DI public ABI edges

**Context.** The runtime-core shrink makes the module a composition root rather
than an implementation umbrella, but Kotlin-Inject generated components expose
some application service and port types in the public `RuntimeComponent` ABI.

**Decision.** Retain only the generated Kotlin-Inject public ABI edges required
by `RuntimeComponent`: direct API edges to runtime-application services and
runtime-ports context/port types, with the documented transitive domain and
contracts closure, and no infrastructure or entrypoint API edges.

**Reason.** Hiding the generated DI ABI would fight the toolchain and break
callers, but documenting and testing the narrow edge prevents runtime-core from
growing back into a compatibility umbrella.

## 2026-05-18 — Platform-pack manifest validation moves to a canonical JSON Schema

**Context.** Before SKILL-47 the rules describing
`platform-packs/<slug>/platform.yaml` lived only inside
`ShellContentLoader.buildPack` (Kotlin parser code), `ScaffoldSupport.kt`
(`SHELL_CONTRACT_VERSION`, `APPROVED_CODE_REVIEW_AREAS`, `CONTENT_BODY_FILENAME`),
and the in-memory `PlatformManifest` data class. No standalone document
described the manifest shape; new fields drifted across three files with no
mechanical link, and the desktop UI had nowhere to render a contract reference.

**Decision.** Adopt JSON Schema (Draft 2020-12) authored as YAML at
`orchestration/contracts/platform-pack-schema.yaml` as the source of truth for
the manifest shape. Validate manifests against the schema at runtime through
`com.networknt:json-schema-validator` (full Draft 2020-12 support, Apache-2.0)
bridged via Jackson `databind` (already required transitively by the validator).
The parser still produces the existing `PlatformManifest`; only the shape-rule
source moves. Cross-field coherence rules (`slug-parity`,
`areas-require-baseline`, `areas-equal-declared`,
`area-metadata-keys-subset-declared`, `pointers-unique-name-per-dir`) stay in
Kotlin because they are awkward to express in pure JSON Schema, but each is
named and documented in the schema file's `x-coherence-checks` block so the
schema document alone describes the full contract.

**Alternatives considered.**

- *Keep rules in Kotlin (status quo).* Rejected: drift across data model,
  parser, and `SHELL_CONTRACT_VERSION` is the problem this task solves.
- *Custom YAML-with-our-own-validator DSL.* Rejected: low leverage, every new
  rule needs custom validator code, no tooling ecosystem.
- *kaml + Kotlin data classes as schema.* Rejected: still couples schema to
  runtime code, no documentation surface, no UI viewer.

**Consequences.**

- Adds two runtime dependencies to `runtime-core`:
  `com.networknt:json-schema-validator` and Jackson `databind` /
  `dataformat-yaml`. Pure-JVM, no native bindings, no reflection magic.
- `SHELL_CONTRACT_VERSION` is pinned to the schema's `contract_version.const`
  via a parity test. Mismatch is a build break, not a runtime mystery.
- Desktop UI can surface the canonical schema file as a read-only viewer
  through the existing editor pane; no second copy of the schema lives in the
  UI module.
- Wrapping the validator behind `PlatformPackSchemaValidator` keeps the
  library choice local — swapping it later means rewriting one Kotlin file.

## 2026-05-19 — Install-plan validates at BOTH builder and CLI seams (diverges from 2a)

**Context.** SKILL-48 subtask 2a (workflow-state) wired schema validation at a
single seam — the canonical `Canonical*` parse path — and relied on that one
choke-point to keep the wire honest. Subtask 2b (install-plan) explicitly
specifies dual-seam validation in AC4: both `buildInstallPlan` (in
`runtime-core`'s `InstallPlanBuilder`) and `installPlanPayload` (in
`runtime-cli`'s `InstallCliPayloads.kt`) must validate the install-plan-shaped
map against the canonical schema and loud-fail via
`InvalidInstallPlanSchemaError`.

**Decision.** Keep `InstallPlanSchemaValidator.validate(...)` calls at both
seams. The CLI seam is not a redundant safety net — it covers post-build
re-assembly that the builder cannot see (the CLI may stitch additional fields
in before emission), and AC4 of subtask 2b
(`.feature-specs/SKILL-48-runtime-contracts-expansion/spec_subtask_2b_install-plan.md`)
explicitly requires both seams to loud-fail. Diverging from the 2a single-seam
pattern is intentional for install-plan.

**Consequences.**

- The CLI-side `installPlanPayload` carries a code comment naming AC4 so
  future readers do not mistake the dual validation for accidental duplication.
- Tests under `runtime-domain` exercise the validator in isolation; the
  CLI-side coverage flows through existing CLI integration tests.
- Deferred decision: the install-plan validator currently ships as a Kotlin
  `object` singleton (`InstallPlanSchemaValidator`) rather than the 2a
  `interface + Canonical*` shape. This is acceptable while the validator has a
  single in-process consumer; revisit (lift to an interface + canonical impl)
  when a second consumer needs to substitute a fake.

**Superseded by 2026-05-28 (SKILL-52.3).** The dual-seam INTENT (validate at
both the builder seam and the CLI emission seam) still holds, but the mechanics
described above are stale: neither seam may import `InstallPlanSchemaValidator`
directly, the validator no longer lives in `runtime-core`/`runtime-domain`
(it moved to `runtime-infra-fs`), and both seams now validate through the
injected domain-owned `InstallPlanWireValidator` port (the CLI seam routes via
the thin application method `InstallService.validateInstallPlanWire`). See the
2026-05-28 entry for the relocation and the 2026-05-29 external-schema entry for
the source-of-truth and parity guarantee.

## 2026-05-28 — Schema validators move from runtime-contracts to runtime-infra-fs, reached through domain ports

**Context.** SKILL-52.3 closes the runtime hexagon leak: the foundational
`runtime-contracts` leaf owned three networknt + Jackson + filesystem schema
validators (`InstallPlanSchemaValidator`, `WorkflowStateSchemaValidator` /
`CanonicalWorkflowStateSchemaValidator`, `DecompositionManifestSchemaValidator`)
plus the `DecompositionManifestCoherenceValidator`, and `runtime-domain` install
policy invoked the concrete install-plan validator at runtime. A contract leaf
and the domain should not own infrastructure-grade schema loading.

**Decision.** Move all three schema validators and the coherence validator into
`runtime-infra-fs` — the module that already owns `PlatformPackSchemaValidator`
and `NativeAgentCompositionSchemaValidator`. Reach them only through
domain-owned ports that generalize the existing `WorkflowSnapshotValidator`
pattern: `InstallPlanWireValidator` (runtime-domain `skillbill.install.model`)
and `DecompositionManifestValidator` (runtime-domain `skillbill.workflow`).
Wire each port to an infra-fs adapter through `RuntimeComponent` with
`@Provides @JvmSynthetic internal`, exactly like every other infra adapter.
The pure `*SchemaPaths` and `*_CONTRACT_VERSION` constants stay in
`runtime-contracts`; the networknt + Jackson dependencies and the three schema
`Copy` tasks move with the validators to `runtime-infra-fs`. The library choice
is unchanged.

**Reason.** Keeping `Path`-free constants in contracts preserves the single
source of truth for schema locations while removing infrastructure ownership
from the contract leaf and the domain. Routing every validator through a
domain-owned port keeps the three validators reached uniformly and lets the
composition root own the concrete wiring, so `runtime-domain`'s runtime closure
no longer pulls networknt/Jackson transitively.

**Supersedes.**

- 2026-05-24 "Preserve dual install-plan validation after policy extraction" —
  dual-seam coverage (builder + CLI emission) is preserved, but neither seam may
  live inside `runtime-domain`; both now validate through the injected
  `InstallPlanWireValidator` port.
- 2026-05-18 "Platform-pack manifest validation moves to a canonical JSON
  Schema" added the validator dependencies to `runtime-core`; they later moved
  to `runtime-contracts`. This subtask moves all schema validators to
  `runtime-infra-fs`, the module that already owns the platform-pack validator.

**Note.** The infra-side adapters live in `runtime-infra-fs`, not
`runtime-application`, because the application layer cannot depend on infra
without inverting the hexagon. The former `runtime-application`
`WorkflowSnapshotValidatorAdapter` is superseded by
`WorkflowSnapshotValidatorInfraAdapter`. Final source-of-truth wording for the
schema files themselves is recorded in the 2026-05-29 external-schema entry
below (subtask 5).

---

## 2026-05-29 — External schemas are the source of truth, copied into the runtime at build time (SKILL-52.3 subtask 5)

Context: Each runtime contract schema (`install-plan`, `workflow-state`,
`decomposition-manifest`, `platform-pack`, `native-agent-composition`,
`telemetry-event`) is authored once as Draft 2020-12 YAML under
`../orchestration/contracts/`, OUTSIDE the Gradle project, and consumed at
runtime as a classpath resource by the JVM validators.

Decision: Keep `orchestration/contracts/*.yaml` as the single canonical source
of truth. `runtime-infra-fs` copies the five schema files
(`copyInstallPlanSchema`, `copyWorkflowStateSchema`,
`copyDecompositionManifestSchema`, `copyPlatformPackSchema`,
`copyNativeAgentCompositionSchema`) and `runtime-mcp` copies the sixth
(`copyTelemetryEventSchema`) into their generated resources at build time. Each
`Copy` task is config-cache-safe: the canonical source path is captured as a
plain `String` `val` at configuration time and fed to `from(...)` /
`inputs.file(...)` (no `Project`/`Task` reference is captured), while only the
`require(File(path).exists())` existence check runs inside a `doFirst {}`
guard, loud-failing with a named message if the canonical file is missing. Parity is mechanical: every
`*_CONTRACT_VERSION` constant in `runtime-contracts` (or the domain/mcp
equivalents) is pinned to its schema's `properties.contract_version.const` by a
dedicated `*SchemaContractVersionTest`, so bumping one without the other is a
build break.

Reason: The schemas are shared with the orchestration layer (CLI/MCP tooling),
so they cannot live inside one Gradle module without forking the contract.
Copying at build time keeps the runtime self-contained (validators load a
classpath resource, not a repo-relative path) while preserving the external
file as the one place a contract change is made. The loud-fail guard turns a
missing-schema misconfiguration into an immediate, named build failure instead
of a runtime `null` resource stream.

Revisit when: a schema needs to diverge between the runtime and the
orchestration tooling, or when the runtime is published as a standalone
artifact without access to `../orchestration/contracts/`.

## 2026-05-29 — SKILL-52.3 subtask 4: application wire seam + open-boundary reconciliation

**Decisions.**

1. **Type `SystemService.doctor` / `version`.** Both now return
   `DoctorContract` / `VersionContract`; the CLI (`SystemCliCommands`) and MCP
   (`McpRuntime`) adapters own the `.toPayload()` call. Output stays
   byte-equivalent. The two FQNs were removed from the raw-map allow-list, the
   ARCHITECTURE.md open-boundary block, and the SKILL-52.2 `must_type_now`
   inventory group.

2. **Relabel lifecycle payloads + `LifecycleTelemetryService` as permanent open
   boundaries.** The 5 `LifecycleTelemetryPayloads` helpers and the 7
   `LifecycleTelemetryService` emit methods are forward-compatible MCP/CLI event
   bags with no stable per-key schema, so they are now annotated
   `@OpenBoundaryMap` and moved from the SKILL-52.2 `postponed_with_reason`
   group (gated, `[subtask 4]`) into `open_extension` (no subtask tag) rather
   than typed away. No event names, keys, shapes, or persisted payloads changed.
   All "will remove" / future-tense removal wording was deleted from
   ARCHITECTURE.md and `RuntimeArchitectureTest`.

**Encode-seam relocation rationale.** YAML serialization for the decomposition
manifest moved out of `runtime-application` (`DecompositionManifestFileWrites`)
behind a new `DecompositionManifestFileStore.encodeManifestYaml(wireMap)` port
method, implemented by the infra-fs `FileSystemDecompositionManifestFileStore`
with the same `YAMLMapper()` construction (byte-identical output). This mirrors
the subtask-1 decode seam (`DecompositionManifestValidator`): the application
layer keeps `encodeDecompositionManifestMap` (the validated-map builder) and
still calls `validator.validateYamlText` AFTER serialization, so the write path
keeps throwing `InvalidDecompositionManifestSchemaError` on invalid input.
`runtime-application` main no longer imports Jackson and its build no longer
carries the production `jackson.dataformat.yaml` dependency (relocated to
`testImplementation` for the pre-existing + new test doubles). The new port
method is `@OpenBoundaryMap`-annotated and documented in the allow-list +
`open_extension` inventory because the raw-map architecture scanner walks
`runtime-ports`.

## 2026-06-04 — Goal telemetry: writes on LifecycleTelemetryRepository, goalStats() on WorkflowStatsRepository

**Context.** SKILL-66 Subtask 2 adds persistence for the goal telemetry event
family (`goal_started`, `goal_subtask_finished`, `goal_finished`). Acceptance
criterion 1 reads literally as "`LifecycleTelemetryRepository` gains methods for
the three goal events ... plus the read/aggregate queries needed for stats", which
could be read as putting the aggregate read on the same port. But every existing
lifecycle family keeps writes on `LifecycleTelemetryRepository` (write-only:
`featureImplementStarted`, `featureVerifyStarted`, `featureTaskRuntimeStarted`,
...) and puts the aggregate read on `WorkflowStatsRepository`
(`featureImplementStats()`, `featureVerifyStats()`, `featureTaskRuntimeStats()`).

**Decision.** Goal **writes** (`goalStarted`/`goalSubtaskFinished`/`goalFinished`)
go on `LifecycleTelemetryRepository`; the aggregate **read** `goalStats()` goes on
`WorkflowStatsRepository`. AC#1's own tiebreaker clause — "*following the interface
style of the existing event methods*" — selects parity placement over literal
single-port grouping. No existing family reads through
`LifecycleTelemetryRepository`, and breaking that would split the read surface
across two ports.

**Reason.** Parity keeps the stats surface single-sourced on
`WorkflowStatsRepository` (which Subtask 4's `goal_stats` tool reads), preserves
the established write/read seam separation, and avoids leaking a read method onto
the write-only telemetry port. The cost is that AC#1's literal "on
`LifecycleTelemetryRepository`" wording is satisfied for writes only; the
read lives one port over, exactly as `featureTaskRuntimeStats()` does.

**Consumers.** Subtask 3 calls the three write methods from `GoalRunner`;
Subtask 4 reads `goalStats()` for the `goal_stats` MCP tool and `goal-stats` CLI.
