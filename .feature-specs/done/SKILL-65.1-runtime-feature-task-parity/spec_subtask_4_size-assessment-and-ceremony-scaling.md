# SKILL-65.1 · Subtask 4 — Size Assessment and Ceremony Scaling

Parent: [SKILL-65.1 full parity](./spec.md)
Issue key: SKILL-65.1
Status: Draft

## Scope

Bring `bill-feature-task`'s SMALL/MEDIUM/LARGE ceremony scaling
(`SKILL.md` Step 1 `assess`, plus the size-conditional behavior threaded through
preplan/plan/implement/audit/review scope) to the runtime, which today treats
every run uniformly. The run resolves a feature size and the runtime scales phase
ceremony accordingly (e.g. lighter preplan/audit for SMALL, branch-diff scope and
full per-criterion audit for MEDIUM/LARGE).

## Acceptance Criteria

1. The run resolves a feature size (`SMALL` | `MEDIUM` | `LARGE`) as a
   run-invariant, sourced deterministically (from the spec/governed inputs, with
   a documented default) — not re-derived per phase.
2. The resolved size is part of the durable run state and is carried into every
   phase briefing (so each phase agent knows the size), matching how
   `bill-feature-task` passes `feature_size` into each subagent briefing.
3. Ceremony scales by size in the runtime, at minimum mirroring the standard
   flow's documented differences: SMALL uses lighter preplan/audit and
   current-unit-of-work scope; MEDIUM/LARGE use full preplan, branch-diff review
   scope, and full per-criterion audit. The exact scaling matrix is encoded in
   the runtime (definition/composer/runner), not left to agent discretion.
4. No review/audit/validation gate is *weakened* by scaling — SMALL runs a
   lighter-ceremony but still-real gate; scaling changes scope/verbosity, not
   gate integrity.
5. Size is reported in `status` and `--monitor` output.
6. Resume preserves the resolved size from durable state (never re-resolves to a
   different size mid-run).

## Non-Goals

- No decomposition behavior (subtask 5) — LARGE here only scales ceremony; the
  decision to stop-and-decompose is subtask 5.
- No new user-interaction gate beyond the single confirmation the
  `bill-feature-task-runtime` front door already presents.

## Dependency Notes

- Depends on: subtask 2 (preplan phase to scale) and subtask 3 (terminal phases
  exist so the full loop can be scaled coherently).
- Downstream: subtask 5 (LARGE feeds the decompose decision); subtask 6
  (telemetry records the resolved size).

## Validation Strategy

- Unit tests for the size resolver (each size + default) and for the
  size-conditional briefing/ceremony selection per phase.
- Tests asserting gates remain real at SMALL (no gate disabled, only scoped).
- Update status/monitor tests to assert the reported size.
- `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate` pass.

## Next path

Proceed to [subtask 5 — Decomposition Mode and Planning Stop](./spec_subtask_5_decomposition-mode-and-planning-stop.md),
or run `skill-bill goal SKILL-65.1`.
