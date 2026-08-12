# SKILL-184 · Subtask 1 — Project current-phase execution information

## Scope

Runtime-side change under `runtime-kotlin` and
`orchestration/contracts`:

- Define the smallest additive, optional IDE-status value that describes the
  current phase's execution information. It must identify the phase, the
  execution kind, the current count, and an optional meaningful total.
- Project the value from authoritative durable status projections. Do not make
  the plugin reconstruct loop state from `current_step`, feature progress, or
  display labels.
- Preserve the existing feature-level `progress.completed` and
  `progress.total` meaning. This new value occupies the current-phase
  execution slot; it is not a replacement for workflow phase progress.
- Cover semantic remediation loops and other bounded re-entry paths exposed by
  the runtime definition. Audit uses the derived audit-gap iteration, review
  uses its persisted review pass number, validation uses its gate-run count
  when active, and other phases use their durable backward-edge iteration or
  attempt only when that is the available truthful execution measure.
- Keep private phase records, raw agent output, prompts, database paths, and
  diagnostics out of the IDE wire payload.

The projection must distinguish a semantic loop/pass from a generic phase
attempt. A retry or resume may increment an attempt without creating a new
semantic loop; the wire label and kind must not claim otherwise.

## Acceptance Criteria

1. The IDE status schema and typed application model define one additive,
   optional current-phase execution value. Older producers remain valid when
   the value is absent, and malformed values fail through the existing typed
   status-diagnostic path rather than corrupting the complete snapshot.
2. The runtime status projector emits the value only for the phase that is
   current in the same snapshot. It never reports a completed neighbouring
   phase's historical loop or pass as current execution.
3. During an audit-gap loop, the value carries the authoritative audit phase
   and loop iteration. The first audit pass is not misreported as loop 1 when
   no audit-gap edge has fired; the representation must make the first pass
   distinguishable from later loop iterations.
4. During review remediation, the value carries the authoritative review pass
   number. Resuming or retrying the same durable pass does not reset the pass
   number or make a stale review record appear current after the workflow has
   advanced.
5. During validation, the value carries the current validation gate run count
   when the gate has begun. A phase with only a generic attempt count is
   labelled as an attempt, not as a semantic loop, and no unbounded total is
   invented.
6. Backward-edge and bounded regeneration phases expose their durable edge
   iteration or attempt where that is the current execution fact. The
   projection is extensible through the runtime phase/loop definition rather
   than a list of UI-only string special cases.
7. Planning remains represented by the existing `planning` object and is not
   duplicated into the current-phase execution value. The existing planning
   counts and feature progress fields remain byte- and meaning-compatible.
8. Runtime projector and wire-contract tests catch the realistic regressions:
   audit loop count resets to zero, review pass count is read from the wrong
   attempt, a completed phase leaks its last loop into the current phase, an
   absent optional counter breaks old snapshots, or a generic retry is labelled
   as a semantic loop.
9. The runtime check suite passes from `runtime-kotlin`.

## Non-Goals

- No change to loop selection, retry policy, repair convergence, gate
  execution, persistence, or phase transition behavior.
- No new CLI command and no duplicate status-polling implementation.
- No contract-version bump when the additive optional field remains compatible.
- No raw output, prompt, repository content, or private diagnostic exposure.

## Dependency Notes

This subtask is the producer contract for subtask 2. The plugin must not be
implemented against an inferred shape or test-only fixture field.

## Validation Strategy

Add focused behavior and schema-parity tests at the existing runtime seams:

- IDE status schema/model tests for optional-field compatibility and strict
  malformed-value handling.
- Runtime status projector tests for audit-gap iteration, review pass,
  validation gate run, generic attempt/edge iteration, phase ownership, and
  first-pass semantics.
- Existing runtime status and CLI wire tests to prove the old progress and
  planning fields remain unchanged.

Run:

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check
```

## Next Path

After this subtask is complete, subtask 2 can map the typed optional value into
the IntelliJ status bar, tooltip, accessibility description, and popup without
reimplementing runtime loop accounting.
