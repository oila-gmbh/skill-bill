# SKILL-89 Per-Subtask Agent Attribution Rollup

## Status

Complete

## Problem Statement

Each decomposed subtask runs as a feature-task child workflow that already records a
durable, append-only per-phase/attempt ledger with the resolved agent on every entry:

- `FeatureTaskRuntimePhaseLedgerEntry` carries `resolvedAgentId` on each
  `START / RESUME / RETRY / FIX_LOOP_ITERATION / LOOP_EDGE / BLOCKED / COMPLETE` action.
- `FeatureTaskRuntimePhaseRecord` carries a required `resolvedAgentId` per phase.

This attribution never escapes the child workflow. The handback the child gives the
goal — `FeatureTaskRuntimeGoalContinuationOutcome` (written by
`FeatureTaskRuntimeGoalContinuationRecorder`) — carries status, commit SHA, blocked
reason, and resumable step, but **no agent attribution**. Consequently:

1. `DecompositionSubtask` (in `decomposition-manifest.yaml`) has no agent field, so an
   operator cannot see "subtask 3 → completed by claude" without cracking open each
   child workflow's `artifacts_json`.
2. Per-subtask goal telemetry (`GoalTelemetryRecordMappers` / `GoalRunnerTelemetryEmitter`)
   carries subtask id, name, and counts but no agent attribution, so the telemetry loop
   cannot aggregate agent × subtask × outcome or compute per-agent recovery rate.
3. A single-spec feature-task run (the common, non-decomposed path) never surfaces the
   executing agent anywhere an operator reads. The prose orchestrator reconciles the tracked
   `spec.md` to `Status: Complete` but records no agent; the runtime resolves a per-phase
   agent in its ledger but never writes it back to `spec.md` (and does not reconcile the
   `## Status` block at all today). A finished single-spec `spec.md` therefore does not say
   which agent finalized it.

Because the ledger records `RESUME` entries with a possibly different `resolvedAgentId`
than the original `START`, the data needed to measure durable-recovery handoffs
(codex starts subtask 4, hits a limit, claude resumes and finishes) is already captured —
it just is not rolled up or surfaced.

## Desired End State

- The child→goal handback carries a rollup derived from the child's existing phase
  ledger: a `finalizingAgentId` and the distinct set of `participatingAgentIds`.
- The decomposition manifest's `DecompositionSubtask` exposes `finalizingAgentId` and
  `participatingAgentIds` as additive-optional fields, written when the goal runner
  applies the subtask outcome.
- Per-subtask goal telemetry includes agent attribution, enabling remote aggregation of
  agent × subtask × outcome and per-agent recovery rate.
- A finished single-spec feature-task run records the finalizing agent as an `Agent:` line
  in the tracked `spec.md` `## Status` block, adjacent to `Status: Complete`, in both prose
  mode (the resolved invoking agent) and runtime mode (the ledger-derived
  `finalizingAgentId`). The line is written only at completion reconciliation and is
  additive — a `spec.md` without it still loads and reconciles.
- No durable-record contract is broken: in-flight goal manifests and workflow records
  written before this change continue to decode without loud-fail.

## Implementation Map (grounding for the implementer)

Seam A — child→goal rollup:
- `runtime-domain/.../workflow/taskruntime/model/FeatureTaskRuntimePersistenceModels.kt`
  (`FeatureTaskRuntimeGoalContinuationOutcome` + its `toArtifactMap` / strict decode).
- `runtime-application/.../featuretask/FeatureTaskRuntimeGoalContinuationRecorder.kt`
  and `FeatureTaskRuntimeRunner.kt` (where the outcome is built; derive the rollup from
  the existing phase ledger / phase records).

Seam B — manifest surfacing:
- `runtime-domain/.../workflow/model/DecompositionManifestModels.kt` (`DecompositionSubtask`).
- `runtime-application/.../decomposition/DecompositionManifestTransitionSupport.kt`
  (the `subtask.copy(...)` write seam) and the manifest codec / wire map / coherence
  validator / schema that govern the manifest contract.

Seam C — telemetry (moat feed):
- `runtime-application/.../goalrunner/GoalTelemetryRecordMappers.kt` and
  `GoalRunnerTelemetryEmitter.kt`; the goal telemetry model and DB save support; the
  telemetry event schema validator.

Seam D — single-spec `spec.md` agent line (completion-time reconciliation):
- Prose mode: `~/.claude/skills/bill-feature-task-prose/content.md` (and the mirrored
  `SKILL.md`), at the reconciliation step that sets `Status: Complete`. Add the `Agent:`
  line resolving the running agent through the existing, governed order documented on the
  feature-task CLI — `--agent` → `SKILL_BILL_AGENT` → detected invoking-agent context →
  documented default (`DEFAULT_RUNTIME_AGENT`, currently `codex`). Do not invent a new
  agent source.
- Runtime mode: expose the single-spec `finalizingAgentId` — the same Seam A derivation,
  computed even when `goalContinuation == null` (where the goal-continuation outcome is not
  persisted) — on a runtime status/outcome surface
  (`FeatureTaskRuntimeStatusService` / `FeatureTaskRuntimeStatusModels`), and ensure a
  deterministic writer stamps the `Agent:` line alongside `Status: Complete` in the
  reconciled `spec.md`. The runtime does not reconcile the `## Status` block today, so the
  implementer picks the writer (runtime-CLI finalize effect or a new
  `bill-feature-task-runtime` reconciliation step); the acceptance criteria assert the
  end-state line, not the mechanism. Reuse the Seam A rollup helper; do not re-resolve.
- Both modes: the `Agent:` line lives only under `## Status`, must not perturb the runtime
  acceptance-criteria reader (`FileSystemFeatureTaskRuntimeRunInvariantsSource`, which keys
  off `## Acceptance Criteria`), and is written idempotently (updated in place, never
  duplicated on re-run).

## Acceptance Criteria

1. `FeatureTaskRuntimeGoalContinuationOutcome` carries a `finalizingAgentId` (the
   `resolvedAgentId` of the terminal `COMPLETE` or `BLOCKED` ledger entry) and a
   `participatingAgentIds` list (the distinct `resolvedAgentId` values across the
   subtask's phase ledger, order-stable), both derived from the child's existing phase
   ledger / phase records — no new agent value is invented or re-resolved.
2. The rollup uses the resolved (actually executed) agent, never the assigned or
   override agent, and a recovery handoff that ran phases on more than one agent yields
   more than one entry in `participatingAgentIds`.
3. `FeatureTaskRuntimeGoalContinuationOutcome.toArtifactMap` writes the new fields and
   its strict decode reads them as optional; an artifact map written before this change
   (missing both fields) decodes successfully with `finalizingAgentId = null` and
   `participatingAgentIds = empty`.
4. `DecompositionSubtask` gains `finalizingAgentId: String? = null` and
   `participatingAgentIds: List<String> = emptyList()`, populated at the
   `DecompositionManifestTransitionSupport` write seam from the outcome rollup when a
   subtask reaches a terminal (complete or blocked) state.
5. `DECOMPOSITION_MANIFEST_CONTRACT_VERSION` is NOT bumped; the manifest schema, codec,
   wire map, and coherence validator treat the new fields as additive-optional, and a
   `decomposition-manifest.yaml` written before this change (without the new fields)
   continues to load and round-trip without loud-fail.
6. Per-subtask goal telemetry emitted by `GoalRunnerTelemetryEmitter` /
   `GoalTelemetryRecordMappers` includes the subtask's `finalizingAgentId` and
   `participatingAgentIds`, and the telemetry event schema validator accepts the enriched
   payload while still accepting payloads without the fields.
7. The enriched telemetry is sufficient to aggregate, per agent, the count of subtasks
   finalized and the count of subtasks where that agent appears in `participatingAgentIds`
   but is not the finalizer (a recovery-handoff signal), demonstrated by a test that
   reads back emitted telemetry.
8. No agent-identity branching is introduced in supervision/spawn code; this change adds
   data fields and rollup derivation only, leaving the injectable-strategy runtime path
   untouched.
9. (Prose mode) A single-spec `bill-feature-task` prose run that reconciles a tracked
   `spec.md` to `Status: Complete` also writes an `Agent:` line in the `## Status` block
   recording the resolved invoking agent, resolved via the existing
   `--agent` → `SKILL_BILL_AGENT` → detected-context → documented-default order — no
   re-invented source. A SMALL run with no spec on disk writes nothing; when a spec exists
   it gets the line.
10. (Runtime mode) A single-spec runtime feature-task run surfaces the ledger-derived
    `finalizingAgentId` (the Seam A derivation, computed even when `goalContinuation == null`)
    on a runtime status/outcome surface, and the `Agent:` line written into the reconciled
    `spec.md` carries that `finalizingAgentId` — the resolved, actually-executed terminal
    agent, reflecting an in-run recovery handoff when one occurred.
11. The single-spec `Agent:` line is additive and idempotent: a `spec.md` without it loads
    and reconciles without loud-fail; re-running or re-reconciling updates the line in place
    rather than duplicating it; and its presence under `## Status` does not break the runtime
    acceptance-criteria reader.
12. `(cd runtime-kotlin && ./gradlew check)` passes, including new acceptance and rejection
    tests for additive-optional decode of legacy records on the three durable-record seams
    (A/B/C) and for the single-spec `finalizingAgentId` surfacing (Seam D runtime side); and
    `npx --yes agnix --strict .`, `scripts/validate_agent_configs`, and
    `skill-bill validate-agent-configs` pass for the prose/runtime skill changes (Seam D
    skill side).

## Constraints

- Additive-optional everywhere: every new field is nullable or defaults to empty, with
  tolerant decode that returns the default only when the key is absent and still
  loud-fails on a present-but-malformed value (matching the existing
  `optionalStringField` decode discipline).
- No contract version bump on the decomposition manifest; legacy durable records
  (manifests, goal-continuation outcome artifacts, telemetry payloads) must not
  loud-fail.
- Rollup derivation is effect-free and reads only already-persisted ledger/record state;
  it mints no timestamps and re-resolves no agent. Writing the single-spec `spec.md`
  `Agent:` line is a distinct completion-time reconciliation effect that consumes the
  already-derived agent value; the derivation itself stays pure.
- The single-spec `Agent:` line is additive to the `## Status` block and idempotent: never
  authored up-front, never duplicated on re-run, and never required for a `spec.md` to load.

## Non-Goals

- Changing runtime supervision, spawn, idle-policy, or PTY behavior (the injectable
  strategy path stays as-is).
- Adding an `executing_agent` field to the authored *input* of `content.md` / the authored
  spec template — the executing agent is a runtime outcome. The single-spec `Agent:` line
  this change adds is written only at completion reconciliation into the `## Status` block;
  it is not part of the template the author fills in.
- Building a new ledger or duplicating the per-phase ledger that already exists in the
  child workflow's artifacts.
- Syncing the full per-phase ledger to the goal/telemetry levels; v1 rolls up only
  `finalizingAgentId` + `participatingAgentIds`. The full ledger remains available in
  child workflow artifacts for deep dives.

## Dependency Notes

- Depends only on existing structures: `FeatureTaskRuntimePhaseLedgerEntry` /
  `FeatureTaskRuntimePhaseRecord` (source of truth) and the governed decomposition
  manifest and goal telemetry contracts. No new external dependency.

## Validation Strategy

- Unit tests for the rollup derivation: terminal-complete finalizer, terminal-blocked
  finalizer, single-agent run (participating == [finalizer]), and a multi-agent recovery
  run (START on agent A, RESUME+COMPLETE on agent B) yielding
  `participatingAgentIds = [A, B]` and `finalizingAgentId = B`.
- Round-trip acceptance/rejection tests on all three seams: legacy artifact/manifest/
  telemetry payloads missing the new fields decode to defaults; present-but-malformed
  values loud-fail.
- A telemetry read-back test proving per-agent finalized vs handoff-participant
  aggregation is computable from emitted events.
- Seam D (runtime): a test that a single-spec run with `goalContinuation == null` still
  surfaces `finalizingAgentId` on the status/outcome surface; a multi-agent in-run handoff
  yields the terminal resolved agent; and the reconciled `spec.md` carries the matching
  `Agent:` line. Seam D (prose): the reconciliation step writes `Agent:` from the resolved
  agent order and is idempotent on re-run; a legacy `spec.md` lacking the line reconciles
  cleanly and the acceptance-criteria reader is unaffected.
- `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
  `npx --yes agnix --strict .`, `scripts/validate_agent_configs`, and
  `skill-bill validate-agent-configs`.

## Next Path

```bash
Run bill-feature-task on .feature-specs/SKILL-89-subtask-agent-attribution/spec.md
```
