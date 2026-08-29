# Subtask 2 — Briefing-only triage for unparseable gate blobs

## Scope

When collect-all parsing (including subtask 1) still produces exactly one
`unparseable_gate_failure`, insert one **triage** agent turn before the first
repair turn in validate and build gate cycles. Triage reads the blob (and repo
files as needed), emits a recommended-shape `validation_repair_plan` as phase
prose, and exits. The runtime injects that plan into the repair briefing as
optional working notes.

### Triage turn rules

- Trigger: post-gate finding set is exactly one finding with
  `ruleOrTestId == unparseable_gate_failure`.
- Triage may read/search/edit nothing that mutates the tree unless needed to
  understand failures; prefer read-only inspection of the blob and cited paths.
- Triage must **not** run gate argv, `bill-code-check`, `./gradlew check`,
  `./gradlew test`, `skill-bill validate`, or pack quality-check skills.
- Triage emits prose with recommended inner shape (example fields per parent
  spec: `item_id`, `module`, `rule_or_task`, `location`, `failure_summary`,
  `fix_intent`). Follow the existing “JSON inside `value` string” prose phase
  pattern; extra keys allowed.
- Runtime does **not** schema-validate triage output. Missing, malformed, or
  empty triage output does not block repair; repair proceeds with the raw
  unparseable finding as today.
- After triage, repair turns and post-repair gate verification are unchanged
  (three-turn cap, gate is proof).

### Prompt / briefing

- Add a triage-specific task directive and phase projection example for the
  recommended `validation_repair_plan` shape.
- Repair briefing includes a “Triage working notes” section when a plan was
  captured; omit the section when triage produced nothing usable.
- When discrete findings already exist (count ≠ 1 or sole finding is not
  unparseable), **skip triage** entirely.

## Acceptance Criteria

1. Post-gate findings of exactly one `unparseable_gate_failure` schedule a
   triage agent launch before repair turn 1 in validate and build gate cycles.
2. Post-gate findings with two or more discrete rows, or a single non-unparseable
   finding, do **not** schedule triage.
3. Triage prompts forbid gate and quality-check invocation with the same strength
   as validate repair prompts.
4. Captured triage `validation_repair_plan` prose is surfaced in the repair
   briefing; the runtime does not reject repair output based on triage shape.
5. Repair turn cap, gate verify after each repair turn, and block-after-three-
   turns behavior match pre-change semantics.
6. Substantiation receipts, repair-plan coverage gates, and confirmation identity
   closure from SKILL-192 subtask 3 are absent from code and prompts.
7. Focused tests prove: unparseable-only → triage then repair ordering;
   discrete findings → repair without triage; empty triage → repair still runs.
8. Feature-task-runtime harness or coordinator test documents that gate pass/fail
   remains the only repair proof (triage never substitutes for verify gate).

## Non-Goals

- Schema-validating triage JSON or adding a triage phase-output gate.
- Persisting triage plans as durable proof artifacts beyond optional briefing
  capture for the current repair window.
- Expanding collect-all parsers (subtask 1).
- Letting triage or repair agents run checks during an open finding set
  (SKILL-198 repair window unchanged).

## Dependency Notes

Depends on subtask 1 (discrete parsers). May land in the same release branch but
must not merge triage scheduling before parser tests exist.

## Validation Strategy

Coordinator scheduling tests with stub runner returning unparseable-only vs
discrete finding sets; prompt composer tests for triage directive text;
harness test for triage → repair → verify sequence. Full `./gradlew check
--continue` before commit.

## Next Path

None — feature complete after this subtask and parent acceptance criteria.
