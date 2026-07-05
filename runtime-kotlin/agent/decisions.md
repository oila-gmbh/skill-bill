# runtime-kotlin/ boundary decisions

This file records architectural and implementation decisions that span the
`runtime-kotlin/` boundary. Each entry is dated and explains the trade-off,
not the implementation detail.

## 2026-07-04 — internal skills are file-read sidecars; repo paths did not move (SKILL-102)

Context: Five feature-execution skills (`bill-feature-task`, `-runtime`, `-prose`,
`-subtask-runner`, `bill-feature-goal`) needed to stop appearing in every agent's
skill list because they are dispatch targets selected by `bill-feature`, not user
entry points. The install pipeline derived listing from the same `content.md`
discovery that drives staging, so hiding a skill required a new internal-skill
classification.

Decision: An internal skill is declared by one optional frontmatter key
(`internal-for: <parent>`). Install renders its governed content as a
`<skill-name>.md` sidecar inside the parent's staged directory (no `skills_dir`
entry, no `SKILL.md` of its own), and the parent invokes it by reading that
sibling sidecar file and executing it in-session — never via the Skill tool.

Reason: The Skill tool on every supported agent resolves only listed skills;
there is no invocable-but-hidden state, so the invocation contract for internal
skills is necessarily a file read. The file-read pattern was already established
for other sibling sidecars (`shell-ceremony.md`, `compose-guidelines.md`) and is
more portable across agents than Skill-tool mechanics. Repo source directories
did not move or rename (PD3) because `WorkflowEngine.CONTINUATION_CONTENT_PATHS`
and `RepoValidationRuntime` content-marker checks bind to the existing repo
paths (`skills/bill-feature-task/content.md`,
`skills/bill-feature-task-prose/content.md`); moving them would have changed
runtime path bindings, workflow identity, the DB `workflow_name` CHECK
constraint, and telemetry constants (PD4) for no listing benefit.

Alternatives considered: (1) A separate `config.yaml` visibility switch per
skill — rejected as a per-skill preference system, the opposite of a repo-level
authored classification. (2) Moving internal skills' source directories under
the parent (`skills/bill-feature/bill-feature-task/`) — rejected because it
breaks runtime path bindings and identity strings (PD3/PD4). (3) Trimming the
sidecar to a token-light format — rejected by PD6 (behavior parity over token
savings).

Revisit when: A supported agent gains a first-class invocable-but-hidden skill
state, or when internal skills need to be surfaced in a maintainer-only listing
view (the parent spec's deferred open question).

## 2026-06-27 — opencode is prose-only: runtime mode refuses whenever the resolved agent is opencode

Context: A real run (NEWS-141, workflow `wftr-20260626-193556-a4lk`) proved the
runtime-driven phase loop is non-viable under opencode for two independent
reasons: (A) the Kotlin runtime driver runs the whole phase loop synchronously in
one foreground process, but opencode's Bash tool hard-kills foreground commands
at 120 000 ms — a single phase (preplan) took ~241 s, so the driver is guillotined
before even one phase completes; and (B) even with a longer budget, the nested
`opencode run` emits valid contract JSON that the runtime never captures back (the
opencode builder used `usePtyStdio=true` + opencode's formatted/TUI output, so the
phase-output JSON cannot be parsed out of the ANSI stream), leaving the phase
`running` and wedging the loop. opencode is the highest-churn, lowest-usage
runtime target, so fixing either bug is not worth it.

Decision: opencode is prose-only. Runtime mode refuses, loudly and at the
boundary, whenever the resolved runtime agent is opencode by ANY route — host-agent
detection, `SKILL_BILL_AGENT=opencode`, `--agent opencode`,
`--phase-agent plan=opencode`, `--agent-override opencode`, and
`--parallel-review-agent opencode` on the feature-task CLI, plus the invoked agent and `--agent-override`
on the goal CLI — failing fast before opening a workflow, resolving a branch, or
spawning a phase. The single source of truth is one domain set,
`skillbill.install.model.RUNTIME_REFUSED_AGENTS` (`{OPENCODE}`), with the predicate
`isRuntimeRefusedAgent` and `OPENCODE_RUNTIME_REFUSAL_MESSAGE` derived from it; every
layer consumes that set so re-enabling an agent's runtime path is a one-line change
rather than scattered edits that drift. Enforcement is defense-in-depth over two
layers: (L1) the runtime CLI preflights — feature-task, goal, and
`code-review-parallel` — all funnel their reachable agent ids through one shared gate
`skillbill.cli.core.refuseRuntimeRefusedAgents`, which throws a `UsageError` with the
actionable message naming both prose alternatives (`bill-feature-task-prose` /
`bill-feature-goal mode:prose`); (L2) the launcher source-disablement —
`OpencodeAgentRunCommandBuilder` is removed and `headlessAgentRunAdapters` filters out
`RUNTIME_REFUSED_AGENTS`, so `FileSystemAgentRunLauncher` yields
`UnsupportedAgentRunLaunch` for opencode (mirroring copilot) as an unbypassable
backstop even if a CLI guard is bypassed, and that deep path carries the same
actionable `OPENCODE_RUNTIME_REFUSAL_MESSAGE` (not a generic reason) so it is as
legible as the preflight. The message is centralized in `runtime-domain` (a plain
const is inert data, allowed by the 2026-05-24 boundary decision) and consumed by
both the CLI and the launcher, so every refusal emits byte-identical wording.

Reason: opencode stays fully usable in prose mode, which runs the identical
governed phase loop in-session with none of the 120s-kill / PTY-harvest problems.
Failing loudly with an actionable message (instead of silently degrading or
wedging) tells the user exactly which path to take. The host-agent DETECTION
(`InvokingAgentContextResolver`) and the `InstallAgent` enum are intentionally
retained: opencode must stay detectable (to refuse) and installable/scaffoldable
(MCP into the user-level opencode config directory, generated opencode agents); only the runtime
LAUNCH path is disabled. No app-layer guard is added (`AgentRunService` /
`FeatureTaskRuntimeRunner` / `GoalRunner` are unguarded) so their
resolution/recording tests stay green and the launcher backstop remains the single
spawner chokepoint. `bill-code-review-parallel` runtime is disabled for opencode the
same way (a parallel-review subprocess hits the same 120s-kill/PTY-harvest wall): its
command now runs the shared preflight on both resolved lanes, so an opencode lane
refuses upfront with the actionable message instead of degrading to a silent one-lane
review.

Non-goals: no change to opencode install/scaffold/MCP, prose orchestration, or
telemetry (all byte-for-byte unchanged); no change to runtime support for claude,
codex, or junie; no removal of opencode from detection or the install enum; no
schema/data migration and no feature flag (refusal-only).

Revisit when: opencode gains a non-TTY harvestable headless mode and a foreground
budget longer than 120s, at which point re-registering an opencode runtime adapter
and dropping the preflights becomes viable.

## 2026-06-26 — SQLite runs in WAL with a busy_timeout for concurrent runs

Context: All runtimes share one global review metrics SQLite file in the user skill-bill state directory,
and nothing prevents concurrent runs (e.g. two goals for two projects at once).
`ensureDatabase` previously set only `PRAGMA foreign_keys = ON`, leaving SQLite on
its rollback-journal default with no busy timeout, so a write that collided with a
concurrent writer failed immediately with `SQLITE_BUSY` ("database is locked") and
aborted the operation — there is no DB-level retry/backoff anywhere in the adapter.

Decision: `ensureDatabase` now sets `PRAGMA busy_timeout = 5000` and
`PRAGMA journal_mode = WAL` (in that order) on every connection, alongside the
existing `foreign_keys` pragma. busy_timeout makes a blocked writer wait-and-retry
inside SQLite instead of erroring; WAL lets readers run concurrently with the single
writer. busy_timeout is set before the journal_mode switch so the WAL transition
itself tolerates a concurrent writer.

Reason: This is the standard low-risk hardening for a shared local SQLite file and
closes the only practical sharp edge of concurrent runs without introducing an
application-level lock or per-project DB files (both rejected: a lock would remove
the ability to run concurrent goals, per-project files would fork the cross-project
review-metrics aggregation the single DB enables). WAL is a persistent property of
the DB file and creates `-wal`/`-shm` sidecars next to the DB — acceptable for a
local user-state database. Re-applying the pragmas per connection is
idempotent.

Revisit when: the DB is moved off a local filesystem (WAL needs shared-memory
support), or measured contention shows 5s is the wrong timeout.

## 2026-06-12 — Retain split `skillbill.contracts.*` package for validator moves

Context: SKILL-52.4 F16 leaves contract DTOs/constants/helpers in
`runtime-contracts` while concrete schema/coherence validators compile from
`runtime-infra-fs` under the existing `skillbill.contracts.*` packages.
Decision: Keep the split package and guard against adding new concrete
`*SchemaValidator` / `*CoherenceValidator` declarations to `runtime-contracts`
main source.
Reason: The package name preserves classpath resource paths and import
compatibility while keeping validator dependencies behind the infra/domain-port
ownership pattern.
Revisit when: Resource paths/import compatibility can be migrated cleanly, or
JPMS/module packaging becomes an active target.

## 2026-06-12 — Keep `runtime-infra-fs` as one adapter module

Context: SKILL-52.4 F17 considered splitting `runtime-infra-fs` into smaller
Gradle modules after validator and filesystem/process ownership moved behind
ports.
Decision: Do not split `runtime-infra-fs` now; keep the filesystem, process,
schema-validation, rendering, git, and staging adapters in the current adapter
module.
Reason: The current module keeps cohesive adapter ownership without adding
premature Gradle/module overhead or new cross-module seams.
Revisit when: Infra-fs package ownership, file count, or build/runtime ownership
pressure makes module-level separation cheaper than the current single adapter
module.

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

## 2026-06-05 — Goal runtime telemetry: loud-fail, per-segment run-session id, and resume dedup (SKILL-66 Subtask 3)

**Context.** SKILL-66 Subtask 3 wires goal lifecycle emission
(`goal_started`/`goal_subtask_finished`/`goal_finished`) into `GoalRunner`. Four
decisions had to be settled: how the runtime distinguishes per-segment run
sessions from stable per-subtask children, how a resumed run avoids
double-counting, what `attempt_count` means, and how a telemetry write failure is
handled relative to the best-effort observability/ledger writes that surround it.

**Decisions.**

1. **Loud-fail, NOT best-effort.** Goal telemetry flows through a new
   application seam `GoalLifecycleTelemetryEmitter`, implemented by
   `LifecycleTelemetryService` via the existing `enabledStandaloneResult ->
   database.transaction` path. When telemetry is **enabled**, a repository write
   that throws propagates out of the emitter and out of `GoalRunner.run`, failing
   the run (AC4, parent AC5). It is deliberately NOT wrapped in `runCatching`
   like `GoalRunnerObservabilityEmitter`/`GoalRunnerLedgerRecorder`, whose writes
   are best-effort by design. When telemetry is **disabled** the seam is a silent
   no-op (no write, no throw), preserving the disabled-vs-enabled-failure
   distinction. The default `GoalLifecycleTelemetryEmitter.NONE` keeps emission
   purely additive so non-telemetry runs stay byte-equivalent (parent AC8).

2. **(D1) Per-segment run-session `workflow_id`.** `goal_started`/`goal_finished`
   carry `"<parentWorkflowId>:seg:<segmentStartedAt>"`, where `segmentStartedAt`
   is captured once at loop start from the injected clock. It is deterministic
   under a fake clock, unique per segment (the clock advances between resume
   segments), and can never collide with the stable child `wfl-N` ids (which
   never contain `:seg:`). This is what makes "exactly one per run segment" hold
   across resumes.

3. **(D2 + resume dedup) `goal_subtask_finished.workflow_id` = stable child id.**
   Each `goal_subtask_finished` carries the subtask's durable child workflow id
   (`wfl-N`); a never-launched terminal (a projection-driven skip) falls back to
   a stable `"<issueKey>:subtask:<id>"`. Combined with the persistence-layer
   dedup key `(issue_key, subtask_id, workflow_id)` (Subtask 2's
   `ON CONFLICT DO NOTHING`), a subtask contributes at most one terminal event
   across all segments. The runtime also snapshots `priorTerminal` (ids already
   terminal at loop start) and only emits for subtasks reaching terminal status
   *within the current segment*, so a resumed run never re-emits earlier
   segments' work even before the DB dedup applies.

4. **(D4) `attempt_count` is runtime-owned and per-segment.** It is the number of
   times the subtask id appears in the runner-owned in-memory `attempted` list,
   coerced to at least 1. Under the current one-attempt-per-subtask-per-segment
   loop it resolves to 1; cross-segment accumulation is out of scope and the
   dedup above prevents inflation. The child-progress `attemptCount` (reflects
   child *step* retries, nullable, costs an extra read) and the durable ledger
   (no per-subtask attempt count) were both rejected.

5. **(D5) Centralized transition-detector for terminal emission.** A single
   `sweepTerminal` pass over the manifest after each iteration (and once before
   `goal_finished`) emits for each newly-terminal subtask. This uniformly covers
   `complete`, `blocked`, AND `skipped` — the last is set only by external
   manifest projection, never by the loop, so no per-emit-site hook could catch
   it. `goal_finished` subtask counts are computed independently from the final
   manifest (`count { status == ... }`), not from any merged report field.

**Reason.** Telemetry that silently drops writes would make the goal stats
surface (Subtask 4) untrustworthy, so the write failure is loud; the
observability/ledger streams remain best-effort because they are diagnostic, not
the metric of record. Splitting the per-segment session id from the per-subtask
child id is what lets "exactly one per segment" and "never double-count on
resume" both hold without a stateful cross-segment counter.

**Consumers.** Subtask 4 stats expectations: `goal_subtask_finished` dedupes by
`(issue_key, subtask_id, child workflow_id)`; `goal_started`/`goal_finished` are
per-segment (distinct `:seg:` ids) and stats group by `issue_key`;
`attempt_count` is per-segment (1 today).

## 2026-07-05 — pack skills internalize by flattening into one parent; baseline co-presence is loud-fail (SKILL-104)

Context: SKILL-102's internal-skill mechanism deliberately loud-failed `internal-for` on
platform-pack skills. The code-review family (34 stack skills across ios/kotlin/kmp/python) needs
the same hiding treatment, but pack skills are discovered, selected, staged, and hashed through a
selection-gated pipeline distinct from base skills.

Decision: Three Pinned Decisions shape the extension. **PD1** keeps the single shared evaluator
(`InternalSkillClassification.kt`) and relaxes ONLY the base-skill-only rule — every other rule
(blank value, self parent, unknown parent, parent must be a listed base skill, depth is 1) is
byte-for-byte unchanged; the `isBaseSkill` flag now feeds only the parent-side rule. **PD2**
flattens: all 34 sidecars are siblings inside `bill-code-review`'s staged directory (depth stays
1; nesting would require a sidecar-hosting-sidecar the staging model cannot express). **PD3**
makes sidecar discovery selection-aware: `discoverInternalSidecarTargets` accepts the plan's
selected pack skills and unions them with the skills-root scan, so an unselected pack contributes
no sidecar and no hash bytes (inertness — a repo with no opted-in pack skill stages
byte-identically). **PD8** adds a plan-time guard (`MissingBaselinePlatformSelectionError`) that
loud-fails when a selected pack declares a required `baseline_layers` entry in an unselected
pack; the shell never silently auto-includes a baseline.

Reason: A `platform.yaml`-level "internal" flag would fork a second classification source and
desynchronize the three seams (authoring, install-plan, validate); PD1's whole point is one
evaluator. Selection-aware staging is the only way to honor pack selection (hidden skills from
unselected packs must not ship) without breaking cache reuse — the parent's content hash folds
only the selected sidecars, so changing selection re-stages. The PD8 guard is pinned here, not
deferred, because today's behavior (selecting KMP alone silently installs a review whose baseline
is absent) becomes load-bearing once the baseline is a sidecar.

Trade-off: Pack sidecar discovery is source-aware (it consults `InstallPlanSkill.sourceDir` from
the plan, not an independent re-scan of `platform-packs/`), so the three staging seams (plan
builder, apply, link-skill fallback) each thread the selected pack skills. The link-skill flow
refuses internal skills upstream and never reaches the pack-sidecar path.
