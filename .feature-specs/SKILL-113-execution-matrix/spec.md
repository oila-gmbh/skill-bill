# SKILL-113 — Execution Matrix: Per-Phase Model And Effort Tiers

## Outcome

The feature-task runtime can run its high-leverage phases (plan, code review, completeness audit, quality validation) on a stronger model and its mechanical phases (implement, fix loops, history, commit, PR) on a cheaper one, driven by a declarative `execution_matrix` in the repo-local `.skill-bill/config.yaml`. The matrix is keyed by agent id and named tier, carries `model` and `effort` as separate fields, resolves per phase with the precedence `CLI arg > config > none`, and loud-fails on any combination an agent's command builder cannot honor.

Example target config:

```yaml
execution_matrix:
  phase_tiers:                       # optional; built-in default in Scope §1
    plan: reasoning
    review: reasoning
  agents:
    claude:
      reasoning:
        model: claude-opus-4-8
        effort: high
      implementation:
        model: claude-sonnet-5
        effort: high
    codex:
      reasoning:
        model: gpt-sol
        effort: high
      implementation:
        model: gpt-terra
        effort: high
```

## Background / Motivation

The runtime pipeline (`preplan → plan → implement → implement_fix → review → audit → validate → write_history → commit_push → pr`) launches every phase agent with the invoking agent's default model. Reasoning-heavy and mechanical phases have very different quality/cost profiles, but there is no way to split them today:

- `SkillRunRequest.modelOverride` exists and the Claude and Codex command builders already render it as `--model`, but `FeatureTaskRuntimeRunner.launchAndCapture` never sets it — the only consumer is the parallel code-review alt lane.
- Per-phase **agent** assignment already exists end-to-end (`--phase-agent`, `FeatureTaskRuntimeAgentAssignment`, `FeatureTaskRuntimeAgentResolver`); per-phase **model** assignment is its missing sibling.
- There is no effort plumbing at all, even though both supported CLIs expose it per invocation (`claude --effort low|medium|high|xhigh|max`; `codex --config model_reasoning_effort=<level>`).

Design decisions locked during preparation (do not revisit them during implementation):

- **Named tiers, not positional pairs.** `agents.<id>.<tier>.{model, effort}` with a global `phase_tiers` phase→tier map.
- **Keyed by agent id, not provider.** `InstallAgent` ids are the keys. GLM-style setups that front a model through an Anthropic-compatible endpoint configure their model ids under the agent that fronts them (e.g. `claude`).
- **Effort is a separate field.** `opus-4.8-high` is not a valid model id anywhere; each command builder renders `model` and `effort` with its own CLI's mechanism.
- **Model ids and effort values stay free strings** (existing convention — no catalog); structure (tiers, phases, agent ids, field names) is validated loudly.
- **`execution_matrix` does NOT join the `RepoLocalConfigKey` enum.** That enum is `(key: String, builtinDefault: String)` and its `parseKnownKey` path stringifies values — it structurally fits flat string keys only. The matrix is parsed beside it (Decided Architecture §A).
- **Capability is declared in the domain, next to `RUNTIME_REFUSED_AGENTS`.** The fail-early gate lives at the CLI boundary mirroring `refuseRuntimeRefusedAgents`; builders keep a defensive backstop. No new port, no branching in the runner (Decided Architecture §B).

---

## Implementation Map (where the code lives)

All paths repo-relative. Read these before coding.

**Repo-local config (the home of the new key):**
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/config/model/RepoLocalConfigModels.kt` — `RepoLocalConfigKey` enum (L23, flat-string keys ONLY — leave untouched), `RepoLocalConfig` typed view + `defaults()` (L41), `RepoLocalConfigResolution.resolve` (L72).
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/FileSystemRepoLocalConfig.kt` — reads `<repoRoot>/.skill-bill/config.yaml`; `buildConfig` (L33); `parseKnownKey` stringifies via `rawValue?.toString()` (L49) and must not be used for the matrix; `parseConfigMap` (L58) already yields the full `Map<String, Any?>` the matrix parser consumes.
- Typed errors (module `runtime-contracts`): `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/ShellContentContractErrors.kt` — `MalformedRepoLocalConfigError(path, key, value, reason)` (L264). Reuse it for every matrix parse failure; do NOT create a new error class for parsing.
- Resolution service: `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/config/ConfigResolutionService.kt` — add `resolveExecutionMatrix` here beside `resolveSpecType` (L22).

**Per-phase agent assignment (the structural template to clone for models):**
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/model/FeatureTaskRuntimeAgentAssignment.kt` — assignment model with `require` validation (L11–30); `FeatureTaskRuntimeResolvedPhaseAgent.resolvedAgentId` (L39) is the value the model resolver keys off.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeAgentResolver.kt` — pure resolver (L16–39), pattern for `FeatureTaskRuntimeModelResolver`.
- CLI: `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/featuretask/FeatureTaskRuntimeCliCommands.kt` — `--phase-agent` option (L77–80), `parsePhaseAgents` (L471–489, the exact template for `parsePhaseModels`), `refuseUnsupportedRuntimeAgent` (L123–132, placement template for the capability gate), `executeRuntimeRun` request assembly (L134–163), `FeatureTaskRuntimeRunDependencies` (L47–52, gains the config service).
- Gate helper template: `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/core/RuntimeAgentRefusal.kt` (whole file, 15 lines).

**Phase set (valid keys for `phase_tiers` and `--phase-model`):**
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/FeatureTaskRuntimePhaseWorkflowDefinition.kt` — phase constants (L26–35), `definition.stepIds` (L65–77).

**Capability declaration home:**
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/install/model/InstallModels.kt` — `RUNTIME_REFUSED_AGENTS` + `isRuntimeRefusedAgent` (L38–44) are the pattern; the new capability set goes directly below them.

**The launch seam:**
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunner.kt` — `launchAndCapture` (L954–997) builds the `SkillRunRequest` (L973–988) with `run.resolvedAgent` in hand (L971).
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/model/FeatureTaskRuntimeRunModels.kt` — `FeatureTaskRuntimeRunRequest` (L14, new field beside `agentAssignment` L20); `FeatureTaskRuntimeRunEvent.PhaseStarted` (L200–206) and the monitor line renderer in `FeatureTaskRuntimeCliCommands.kt` (L457–459) for observability.

**Launcher request + command builders:**
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/agentrun/model/AgentRunLauncherModels.kt` — `SkillRunRequest.modelOverride: String?` (L23, non-blank validated L29); `effortOverride` lands beside it with identical validation.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/AgentRunCommandBuilders.kt` — Claude builder `--model` rendering (L52–55); Codex builder `--model` (L80–83) and existing `--config key=value` mechanism (L78–79); Junie builder (L92–114) ignores `modelOverride` today; `goalContinuationCommand` early-return (L120–170) spawns `skill-bill`, not an agent CLI, and must never render directives.

**Existing model-override consumer (must stay unchanged):**
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/ParallelCodeReviewRunner.kt` (modelOverride L192→L203) and `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/codereview/CodeReviewParallelCommand.kt` (`--model`, L44).

---

## Decided Architecture

Every name below is the contract, not a suggestion.

### A. Config parsing — new domain file, thin infra hook

**New file** `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/config/model/ExecutionMatrixModels.kt`:

```kotlin
package skillbill.config.model

import skillbill.install.model.InstallAgent
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

const val EXECUTION_MATRIX_KEY: String = "execution_matrix"

enum class ExecutionTier(val id: String) {
  REASONING("reasoning"),
  IMPLEMENTATION("implementation"),
  ;

  companion object {
    fun fromId(id: String): ExecutionTier? = entries.firstOrNull { it.id == id }
  }
}

val DEFAULT_PHASE_TIERS: Map<String, ExecutionTier> = mapOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to ExecutionTier.IMPLEMENTATION,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to ExecutionTier.REASONING,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to ExecutionTier.IMPLEMENTATION,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to ExecutionTier.IMPLEMENTATION,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to ExecutionTier.REASONING,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to ExecutionTier.REASONING,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to ExecutionTier.REASONING,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to ExecutionTier.IMPLEMENTATION,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to ExecutionTier.IMPLEMENTATION,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to ExecutionTier.IMPLEMENTATION,
)

data class PhaseModelDirective(
  val model: String,
  val effort: String? = null,
) {
  init {
    require(model.isNotBlank()) { "PhaseModelDirective.model must be non-blank." }
    effort?.let { require(it.isNotBlank()) { "PhaseModelDirective.effort must be non-blank when provided." } }
  }
}

data class ExecutionMatrix(
  val phaseTiers: Map<String, ExecutionTier> = emptyMap(),
  val agents: Map<InstallAgent, Map<ExecutionTier, PhaseModelDirective>> = emptyMap(),
) {
  fun tierOf(phaseId: String): ExecutionTier = phaseTiers[phaseId] ?: DEFAULT_PHASE_TIERS.getValue(phaseId)

  fun directiveFor(agentId: String, phaseId: String): PhaseModelDirective? {
    val agent = InstallAgent.entries.firstOrNull { it.id == agentId.trim().lowercase() } ?: return null
    return agents[agent]?.get(tierOf(phaseId))
  }
}

sealed interface ExecutionMatrixParse {
  data class Valid(val matrix: ExecutionMatrix) : ExecutionMatrixParse
  data class Invalid(val keyPath: String, val value: String, val reason: String) : ExecutionMatrixParse
}

fun parseExecutionMatrix(raw: Any?): ExecutionMatrixParse { /* per rules in Scope §2 */ }
```

`parseExecutionMatrix` takes the untyped YAML node (`raw[EXECUTION_MATRIX_KEY]`, a `Map<*, *>` when well-formed) and returns `Valid` or the **first** `Invalid` it encounters, with `keyPath` in dotted form (e.g. `execution_matrix.agents.claude.reasoning.model`). The domain module must not gain a Jackson dependency — the infra loader already produces the map.

**Changes to existing files:**
- `RepoLocalConfigModels.kt`: `RepoLocalConfig` gains `val executionMatrix: ExecutionMatrix? = null`; `defaults()` leaves it null. `RepoLocalConfigKey` is untouched.
- `FileSystemRepoLocalConfig.buildConfig`: after the two existing keys, when `raw.containsKey(EXECUTION_MATRIX_KEY)` call `parseExecutionMatrix(raw[EXECUTION_MATRIX_KEY])`; map `Invalid(keyPath, value, reason)` to `throw MalformedRepoLocalConfigError(path = path.toString(), key = keyPath, value = value, reason = reason)`. Keep all parse logic in the domain file so this class stays under detekt's `TooManyFunctions` limit.
- `ConfigResolutionService`: add `fun resolveExecutionMatrix(repoRoot: Path): ExecutionMatrix?` reading the port exactly as `resolveSpecType` does and returning `config.executionMatrix` (no precedence folding here — CLI-vs-config precedence is per-phase and lives in the model resolver).

### B. Capability declaration + fail-early gate

**`InstallModels.kt`** (directly below `isRuntimeRefusedAgent`, same style):

```kotlin
val MODEL_DIRECTIVE_CAPABLE_AGENTS: Set<InstallAgent> = setOf(InstallAgent.CLAUDE, InstallAgent.CODEX)

fun supportsModelDirective(agentId: String?): Boolean {
  if (agentId == null) return false
  val normalized = agentId.trim().lowercase()
  return MODEL_DIRECTIVE_CAPABLE_AGENTS.any { capable -> capable.id == normalized }
}
```

**New file** `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/core/ModelDirectiveRefusal.kt`, mirroring `RuntimeAgentRefusal.kt`: a function `refuseUnsupportedModelDirectives(directivesByPhase: Map<String, PhaseModelDirective>, resolvedAgentIdByPhase: Map<String, String>)` that throws Clikt `UsageError` naming the first offending phase, its resolved agent id, and the directive, e.g. `"--phase-model/execution_matrix: phase 'plan' resolves to agent 'junie', which cannot honor a model/effort directive (model=..., effort=...). Capable agents: claude, codex."`

**Call site:** in `FeatureTaskRuntimeCliCommands`, immediately after `refuseUnsupportedRuntimeAgent(...)` on both the run and resume paths — before any workflow is opened or branch resolved. The CLI computes, for every phase in `definition.stepIds`, the resolved agent via the existing pure `FeatureTaskRuntimeAgentResolver.resolve(phaseId, assignment, invokedAgentId).resolvedAgentId` and the directive via `FeatureTaskRuntimeModelResolver.resolve(...)` (§C), then passes only phases whose directive is non-null.

**Builder backstop:** the Junie builder gains, at the top of its non-continuation branch, `require(request.modelOverride == null && request.effortOverride == null) { "junie cannot honor a model/effort directive; remove its execution_matrix entry or --phase-model assignment." }`. This is defense in depth for non-feature-task callers; the CLI gate is the primary failure point.

### C. Assignment model + pure resolver (application layer)

**New file** `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/model/FeatureTaskRuntimeModelAssignment.kt`:

```kotlin
data class FeatureTaskRuntimeModelAssignment(
  val perPhaseDirectives: Map<String, PhaseModelDirective> = emptyMap(),
  val matrix: ExecutionMatrix? = null,
)
```

(with `require` non-blank phase ids, mirroring `FeatureTaskRuntimeAgentAssignment` L15–23).

**New file** `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeModelResolver.kt`:

```kotlin
object FeatureTaskRuntimeModelResolver {
  fun resolve(
    phaseId: String,
    resolvedAgentId: String,
    assignment: FeatureTaskRuntimeModelAssignment,
  ): PhaseModelDirective? =
    assignment.perPhaseDirectives[phaseId]
      ?: assignment.matrix?.directiveFor(resolvedAgentId, phaseId)
}
```

`FeatureTaskRuntimeRunRequest` gains `val modelAssignment: FeatureTaskRuntimeModelAssignment = FeatureTaskRuntimeModelAssignment()` directly after `agentAssignment`.

### D. CLI option, config read, request assembly

- `FeatureTaskRuntimeRunDependencies` gains `val configResolutionService: ConfigResolutionService` (it is `@Inject`; kotlin-inject wires it — no manual DI edits).
- New option beside `--phase-agent`:

```kotlin
protected val phaseModels by option(
  "--phase-model",
  help = "Per-phase model directive as phase=model or phase=model@effort " +
    "(e.g. --phase-model plan=claude-opus-4-8@high). Wins over the config execution_matrix. Repeatable.",
).multiple()
```

- New `parsePhaseModels(rawAssignments: List<String>): Map<String, PhaseModelDirective>` next to `parsePhaseAgents`, mechanically: split at the **first** `=` (`UsageError` if absent, at index 0, or last char — same guard as `parsePhaseAgents` L474–477); validate the phase id against `definition.stepIds` with the same error shape (L480–485); split the value at `@` — zero `@` means model-only, exactly one means `model@effort`, more than one is a `UsageError`; blank model or blank effort segment is a `UsageError`.
- `executeRuntimeRun` builds `modelAssignment = FeatureTaskRuntimeModelAssignment(perPhaseDirectives = parsePhaseModels(phaseModels), matrix = deps.configResolutionService.resolveExecutionMatrix(repoRoot...))` and passes it into `FeatureTaskRuntimeRunRequest`. The same assembly applies on the resume path.

### E. Launch plumbing, builder rendering, observability

- `SkillRunRequest` gains `val effortOverride: String? = null` beside `modelOverride`, with the same non-blank `require`.
- In `launchAndCapture`, before constructing the `SkillRunRequest`:

```kotlin
val directive = FeatureTaskRuntimeModelResolver.resolve(
  phaseId = run.phaseId,
  resolvedAgentId = run.resolvedAgent.resolvedAgentId,
  assignment = run.request.modelAssignment,
)
```

then pass `modelOverride = directive?.model, effortOverride = directive?.effort`. No other runner change; no agent-identity branching anywhere.

- Claude builder, directly after its `--model` block: `request.effortOverride?.let { add("--effort"); add(it) }`.
- Codex builder, directly after its `--model` block: `request.effortOverride?.let { add("--config"); add("model_reasoning_effort=$it") }`.
- `goalContinuationCommand` is untouched: feature-task phase launches always carry `promptOverride`, so the early-return never applies to a directive-bearing request; goal-runner wrapper requests never carry directives.
- `PhaseStarted` gains `val model: String? = null` and `val effort: String? = null`; the runner fills them from the same resolved directive; the monitor line (`FeatureTaskRuntimeCliCommands.kt` L457–459) appends ` model=$model` and ` effort=$effort` only when non-null.

---

## Scope

### 1. Config surface

`.skill-bill/config.yaml` gains an optional `execution_matrix` key:

- `agents.<agent-id>.<tier>` — `<agent-id>` must be in `InstallAgent.supportedIds`; `<tier>` is `reasoning` or `implementation`. Each tier entry is a mapping with required non-blank `model` and optional non-blank `effort`; no other fields.
- `phase_tiers.<phase-id>: <tier>` — optional per-phase override; `<phase-id>` must be in `definition.stepIds`.
- Built-in default tiers are `DEFAULT_PHASE_TIERS` (§A): `plan`, `review`, `audit`, `validate` → `reasoning`; the other six phases → `implementation`.
- An agent entry may define one tier only; a phase whose tier has no entry for its resolved agent launches with no override.
- Absent `execution_matrix` ⇒ behavior observably identical to today. Runtime-refused agents (`opencode`, `zcode`) may appear in config (entries are inert); the capability gate only fires for phases that would actually launch.

### 2. Parse rules (exhaustive loud-fail list)

`parseExecutionMatrix` returns `Invalid` (→ `MalformedRepoLocalConfigError` with the dotted key path) on, in checking order:

1. `execution_matrix` is not a mapping.
2. A top-level field under it other than `phase_tiers` or `agents`.
3. `phase_tiers` is not a mapping; a phase id not in `definition.stepIds`; a tier value that is not `reasoning`/`implementation`.
4. `agents` is not a mapping; an agent id not in `InstallAgent.supportedIds`.
5. An agent value that is not a mapping; a tier key that is not `reasoning`/`implementation`.
6. A tier entry that is not a mapping; missing `model`; blank `model` or blank `effort`; any field other than `model`/`effort`.

Model and effort **values** are otherwise free strings. Unknown top-level config keys outside `execution_matrix` remain ignored, as today. The two existing flat keys parse exactly as before.

### 3. Per-phase resolution

Precedence per phase (implemented in `FeatureTaskRuntimeModelResolver`, §C): CLI `--phase-model` entry → `matrix.agents[resolved agent][tierOf(phase)]` → none. Resolution keys off `FeatureTaskRuntimeResolvedPhaseAgent.resolvedAgentId`, so mixed `--phase-agent` runs and `--agent-override` runs pick each phase's actual agent entry.

### 4. Fail-early gate

Both run and resume CLI paths call `refuseUnsupportedModelDirectives` (§B) after `refuseUnsupportedRuntimeAgent`, covering all phases in `definition.stepIds` (including loop-only `implement_fix`). The parallel code-review lane's explicit `--model` keeps its current meaning and is out of the gate's scope.

### 5. Goal-continuation runs

Goal-continuation children re-enter through `skill-bill feature-task run` in the same repo, re-read `.skill-bill/config.yaml`, and pick up the matrix with zero new plumbing. Directives apply where the child launches its own phase agents, never on the `skill-bill` wrapper process. One test asserts a goal-continuation-shaped run resolves the matrix identically to a direct run.

## Acceptance Criteria

1. `.skill-bill/config.yaml` supports an optional `execution_matrix` key with `agents.<agent-id>.<tier>.{model, effort}` (tiers exactly `reasoning` and `implementation`; `effort` optional) and an optional `phase_tiers` map; when the key is absent, runtime behavior is observably identical to today and every phase launch command is byte-identical to the current baseline.
2. `parseExecutionMatrix` in `ExecutionMatrixModels.kt` (runtime-domain, `skillbill.config.model`) rejects every case in Scope §2 via `ExecutionMatrixParse.Invalid`, and `FileSystemRepoLocalConfig` maps each rejection to `MalformedRepoLocalConfigError` whose `key` is the dotted path (e.g. `execution_matrix.agents.claude.reasoning.model`); model/effort values are otherwise free strings; `RepoLocalConfigKey` is unchanged and the two existing flat keys parse as before.
3. `DEFAULT_PHASE_TIERS` maps `plan`, `review`, `audit`, `validate` to `reasoning` and `preplan`, `implement`, `implement_fix`, `write_history`, `commit_push`, `pr` to `implementation`; `phase_tiers` entries override individual phases without restating the map; `ExecutionMatrix.tierOf` implements exactly this fallback.
4. `FeatureTaskRuntimeModelResolver` (runtime-application, mirroring `FeatureTaskRuntimeAgentResolver`) resolves each phase's directive as CLI `--phase-model` entry, else the matrix entry for the phase's `resolvedAgentId` and tier, else null; mixed `--phase-agent` and `--agent-override` runs resolve against each phase's actual agent.
5. A repeatable `--phase-model phase=model[@effort]` option exists on the runtime run and resume commands; `parsePhaseModels` validates phase ids against `definition.stepIds` with the same `UsageError` shape as `parsePhaseAgents` and rejects a missing/misplaced `=`, more than one `@`, and blank model or effort segments.
6. `SkillRunRequest` gains non-blank-validated `effortOverride`; `launchAndCapture` sets `modelOverride`/`effortOverride` from the resolved directive; the Claude builder renders `--effort <value>` and the Codex builder renders `--config model_reasoning_effort=<value>` after their existing `--model` rendering; `goalContinuationCommand` output is unchanged; no agent-identity branching is added to the runner or wait loop.
7. `MODEL_DIRECTIVE_CAPABLE_AGENTS = {CLAUDE, CODEX}` and `supportsModelDirective` live in `InstallModels.kt` beside `RUNTIME_REFUSED_AGENTS`; `refuseUnsupportedModelDirectives` (new `cli/core/ModelDirectiveRefusal.kt`) throws a `UsageError` naming the phase, resolved agent, and directive, and both run and resume paths call it before any workflow, branch, or subprocess; the Junie builder additionally `require`s that no directive reaches it.
8. Goal-continuation child runs resolve the matrix from the same repo-local config identically to direct runs, with directives applied to the child's phase-agent launches and never to the `skill-bill` wrapper command.
9. The parallel code-review lane's explicit `--model` behavior and the existing `spec_type`/`code_review_parallel_agent` keys are unchanged; with no `execution_matrix` configured, existing tests pass without modification.
10. `PhaseStarted` carries optional `model`/`effort`, and a `--monitor` run's phase-started line appends `model=...`/`effort=...` exactly when a directive was applied.
11. New behavior is covered by tests at the level the surrounding code is tested (Validation Strategy pins the locations): one acceptance case per matrix shape variant, one rejection case per Scope §2 rule asserting the dotted key path, resolver precedence cases, CLI option parse/validation cases, builder rendering cases per agent including the Junie `require`, the fail-early gate on run and resume, and a capability/builder parity test.

## Non-Goals

- Prose mode (`bill-feature-task-prose`) and in-session skills: they run in the invoking session's model; the matrix governs only runtime-launched phase agents.
- A provider abstraction or named endpoint profiles (e.g. Anthropic-claude vs z.ai-claude coexisting in one run); GLM-style setups configure model ids under the fronting agent's entry.
- Validating model ids or effort values against a catalog; both stay free strings.
- More than the two fixed tiers; a third tier can be added later without reshaping the config.
- An `orchestration/contracts/` YAML schema for the repo-local config; the matrix parses via the new domain parser beside the existing enum recipe, not the runtime-contract recipe.
- Changing the parallel code-review lane, `--agent-override`/`--phase-agent` semantics, or opencode/zcode's runtime-refused status.
- Per-run cost/token accounting by tier (telemetry already records per-phase tokens; attribution by model is deferred).
- Adding `execution_matrix` to `RepoLocalConfigKey` or generalizing that enum to nested values.

## Constraints

- **Strategy objects, not branching:** agent-specific rendering lives on the command builders; `ProcessWaitLoop` and `FeatureTaskRuntimeRunner` never branch on agent identity. The capability check is data-driven off `MODEL_DIRECTIVE_CAPABLE_AGENTS`, at the CLI boundary.
- **Loud-fail ethos:** malformed matrix structure and unsupported agent/directive combinations are typed, actionable errors — never silent fallbacks or silent no-ops.
- **Module boundaries:** runtime-domain gains no Jackson dependency (the parser consumes an already-parsed `Map`); runtime-application never sees command builders (capability is a domain declaration); the CLI performs no file IO beyond the existing injected ports.
- **Detekt:** keep `FileSystemRepoLocalConfig` under its function-count limit by putting all matrix parse logic in the domain file; if `parseExecutionMatrix` itself grows past complexity limits, split it into private top-level helpers in the same file rather than suppressing.
- **Comments policy:** per CLAUDE.md, no narrating comments; the sketches above intentionally contain none.
- **Zero-config parity:** no `execution_matrix` ⇒ byte-identical launch commands to today.
- **Resolution discipline:** `explicit arg > config > none` only; do not invent additional precedence layers (no env-var layer, no run-wide model override).

## Validation Strategy

Test locations (create or extend; follow the sibling test style in each directory):

- **Domain parse:** `runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/config/model/ExecutionMatrixModelsTest.kt` — acceptance: full matrix, single-agent single-tier, `phase_tiers` present/absent, effort omitted; rejection: one test per Scope §2 rule asserting `Invalid.keyPath`; `tierOf` defaulting for all ten phases.
- **Loader:** extend the existing `FileSystemRepoLocalConfig` test class under `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/infrastructure/fs/` — well-formed matrix round-trips into `RepoLocalConfig.executionMatrix`; a malformed matrix raises `MalformedRepoLocalConfigError` with the dotted key; a config with only flat keys is unaffected.
- **Resolver:** `runtime-kotlin/runtime-application/src/test/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeModelResolverTest.kt` — CLI beats matrix beats none; per-phase agent variation changes the selected agent entry; null matrix and unknown agent id resolve to null.
- **CLI:** a runtime test under `runtime-kotlin/runtime-cli/src/test/kotlin/skillbill/cli/` following the `CliConfigResolveSpecTypeRuntimeTest.kt` / `CliRuntime.run(...)` pattern — `--phase-model` acceptance (`plan=m`, `plan=m@high`) and each rejection in AC5; the fail-early gate refusing a Junie-resolved phase with a directive on both run and resume before any workflow is opened.
- **Builders:** extend the existing builder tests under `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/launcher/` — Claude and Codex command lists for model+effort, model-only, and neither; the Junie `require` failure; goal-continuation command unaffected by a directive-free request. Add a parity test asserting every `MODEL_DIRECTIVE_CAPABLE_AGENTS` member has a builder that renders both flags and every other builder rejects or ignores nothing silently (Junie `require`s).
- **Runner integration:** extend the `FeatureTaskRuntimeRunner` tests in `runtime-kotlin/runtime-application/src/test/kotlin/` — a configured matrix produces the expected `modelOverride`/`effortOverride` per phase on the captured launch requests; a zero-config run produces launch requests with both fields null; `PhaseStarted` carries the directive fields.
- **Regression:** full `(cd runtime-kotlin && ./gradlew check)` plus `skill-bill validate` green.

## Next Path

```bash
Run bill-feature on .feature-specs/SKILL-113-execution-matrix/spec.md
```

## Status

Complete
