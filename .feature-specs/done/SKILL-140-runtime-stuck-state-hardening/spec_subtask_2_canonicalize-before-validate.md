# SKILL-140 Subtask 2 - Canonicalize Before Validate

## Scope

Absorb lexical trivia in agent-produced planning projections through a deterministic canonicalization step that runs immediately before strict schema validation in the shared validation path, so the bounded fix loop's attempts are spent on structural problems only.

- Add a canonicalization function in the domain model layer (`FeatureTaskRuntimePlanningProjectionModels.kt` or a sibling) applied inside `featureTaskRuntimePlanningProjectionFromEnvelope` before `schemaValidator.validatePlanningProjection`:
  - task ids (`tasks[].task_id`, `tasks[].depends_on[]`, `completed_task_ids[]`, `task_commitments[].task_id`): lowercase, replace `_`/whitespace runs with `-`, strip characters outside `[a-z0-9-]`, collapse repeated `-`; a value that canonicalizes to empty or still fails the pattern rejects.
  - compact-summary fields (`description`, `deviations[].note`): replace tab/CR/LF with a single space, strip backticks, trim; anti-paste patterns (JSON, diff markers) still reject.
  - all `$defs/nonBlank` string fields: trim surrounding whitespace.
- Canonicalization maps referentially: when a `task_id` is rewritten, every reference to it (`depends_on`, `completed_task_ids`, `task_commitments`) is rewritten identically, preserving dependency integrity.
- Because canonicalization lives inside the single shared function, the producer gate (Subtask 1), the launch seam, and every test path see identical behavior with no per-seam drift.
- Emit a typed, bounded record of applied canonicalizations (field, from, to) into the run's diagnostics for telemetry pickup in later work; never into prompt context.

## Acceptance Criteria (this subtask)

1. A plan output with `task_id: "T1"` and a matching `depends_on: ["T1"]` reference canonicalizes to `t1` in both positions, passes validation, and advances; the persisted projection carries the canonical ids.
2. A description containing backticks or tab characters is normalized and accepted; a description consisting of a pasted JSON body or diff hunk still rejects on the anti-paste pattern.
3. Structural violations — missing required fields, unknown keys under `additionalProperties: false`, budget overflow, dependency cycles, empty-after-canonicalization ids — reject exactly as before, with unchanged error typing.
4. Canonicalization is deterministic and idempotent: applying it twice yields the same result, proven by a property-style test over the fixture corpus.
5. Producer gate and launch seam observe identical canonicalization because it lives inside the shared function; a parity test proves an envelope canonicalized at the gate parses identically at the seam.
6. Applied canonicalizations are recorded as bounded typed diagnostics without plan bodies or prompt text.

## Non-Goals

- Synthesizing missing fields, reordering collections, dropping entries, or type coercion.
- Relaxing any structural rule in the schema YAML; the contract file's patterns stay authoritative for post-canonicalization values.
- Telemetry aggregation surfaces (parent spec criterion 12 lands with the subtasks that own those counters).

## Dependency Notes

Depends on Subtask 1: the producer gate must exist so canonicalization takes effect at both seams through the shared function.

## Validation Strategy

- Acceptance and rejection tests per field class (task ids, summaries, nonBlank trims) at the domain model level.
- Idempotence test across all canned projection fixtures.
- `(cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-application:test)` then full `./gradlew check`.

## Next Path

Subtask 3 wires the real schema validator into the run-loop tests so this behavior is proven against the enforced contract, not a Noop stand-in.
