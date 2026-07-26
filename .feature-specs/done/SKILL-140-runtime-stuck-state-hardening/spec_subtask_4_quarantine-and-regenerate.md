# SKILL-140 Subtask 4 - Durable-Record Quarantine And Regenerate

## Scope

Replace "block durably and tell the operator which row to migrate or delete" with an in-band recovery edge: when a launch seam rejects a durable upstream record, quarantine the record and re-run the phase that produced it under a bounded cap.

- In `FeatureTaskRuntimeRunLoop.launchAndCapture`, the catches for `InvalidFeatureTaskRuntimePlanningProjectionSchemaError`, `InvalidWorkflowStateSchemaError` on handoff envelopes, and unprojectable handoff records no longer settle as a terminal `projectionRejected` block on the first occurrence. Instead they:
  1. preserve the rejected record as private quarantined evidence (durable, never prompt-visible, never deleted by the runtime);
  2. invalidate the producing phase's settled `completed` status through the existing phase-record machinery;
  3. take a typed backward edge re-entering the producing phase, under a new bounded regeneration cap wired through the existing loop-id/edge-iteration/watermark mechanics (mirroring the implement-fix cap semantics, including crash-resume without cap reset).
- Cap exhaustion, an invalidatable-phase-not-in-pipeline condition (for example a goal-continuation truncation dropped the producer), or a record the runtime cannot attribute to a producing phase settles as today's durable block, with a reason naming the quarantined record, the attempts taken, and the recovery options.
- Static declaration/config drift (`InvalidFeatureTaskRuntimeHandoffProjectionError`) and briefing byte-ceiling overflow keep their current durable-block behavior: re-running the producer cannot fix a wrong declaration.
- Update AGENTS.md's durable-record recovery paragraph: out-of-band row deletion/migration becomes the corruption fallback, not the primary recovery path.

## Acceptance Criteria (this subtask)

1. A durable plan record that fails launch-seam projection validation (for example a pre-SKILL-137 row without `projection_kind`) triggers quarantine and re-entry of the plan phase; a subsequently valid plan lets the run advance without operator intervention.
2. The rejected record survives as private quarantined evidence retrievable for diagnostics; it is never delivered to any agent prompt and never deleted by the runtime.
3. Regeneration attempts are bounded by a pinned cap; crash and resume mid-regeneration continue the same cap sequence without reset, proven by a crash-injection test mirroring the existing implement-fix cap test.
4. Cap exhaustion blocks durably with a reason naming the quarantined record, the producing phase, and the attempt count; declaration/config drift and byte-ceiling overflow block durably on first occurrence unchanged.
5. A producing phase absent from the resolved pipeline (goal-continuation truncation) blocks durably with an actionable reason instead of attempting an impossible re-entry.
6. Regeneration telemetry records activations, attempt counts, and outcomes per run without recording record contents.
7. AGENTS.md and runtime docs describe quarantine-and-regenerate as the primary recovery path and out-of-band row surgery as the corruption fallback.

## Non-Goals

- Automatic migration or reinterpretation of legacy record fields under new semantics; regeneration re-runs the producer, it never rewrites the old record.
- Recovery for external blocks (session limits, provider errors, git divergence).
- Changing producer-gate behavior (Subtask 1) or validation strictness (Subtask 2).

## Dependency Notes

Depends on Subtask 1: with producer-side gating in place, launch-seam rejections are reduced to legacy/drift records, which is precisely the population this edge recovers. Independent of Subtasks 2-3 functionally, but ordered after 3 so the integration suite exists to extend.

## Validation Strategy

- Runner tests: legacy-record quarantine and successful regeneration; cap exhaustion; crash-resume cap continuity; declaration-drift unchanged terminal block; truncated-pipeline terminal block.
- Extend the Subtask 3 integration suite with a real-validator legacy-record scenario.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 5 closes the last stuck-state class: child processes that die before writing a terminal workflow-store outcome.
