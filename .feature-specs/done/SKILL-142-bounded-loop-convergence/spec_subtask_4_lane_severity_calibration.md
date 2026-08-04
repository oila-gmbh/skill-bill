# SKILL-142 Subtask 4 — Per-lane severity calibration

Parent: `.feature-specs/SKILL-142-bounded-loop-convergence/spec.md` (unit 3)

## Scope

Define severity in terms of observable consequence in the shared review contract,
have every maintained pack's specialist content inherit that definition rather
than restate it, and add validator coverage that rejects a specialist inventing
its own severity vocabulary.

The SKILL-115 admission gate governs *whether* a finding is emitted. Nothing
today governs *what severity means per lane*.

## Evidence

Major-to-Blocker ratio by category across all 987 findings:

| category | Major | Blocker | ratio |
|---|---|---|---|
| `ux_accessibility` | 151 | 4 | 38:1 |
| `data_persistence` | 205 | 10 | 20:1 |
| `concurrency_lifecycle` | 30 | 2 | 15:1 |
| `testing_quality_gate` | 66 | 5 | 13:1 |
| `behavior_correctness` | 51 | 9 | 5.7:1 |

`behavior_correctness` is the calibration reference. A lane emitting 38 Majors
per Blocker is grading observations as material defects.

Specialist routing is not the cause and must not change: observed specialist sets
vary per run from 2 areas (`architecture,platform-correctness`) to 8, so routing
already narrows to the diff.

Evidence sites:

- `orchestration/review-orchestrator/review-skill-structure-standard.md:20`
- `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md:255`

These are the shared consequence rubric every pack inherits; this unit anchors
there.

## Acceptance Criteria

1. The shared review contract defines severity in terms of observable
   consequence, with `behavior_correctness` as the calibration reference: Blocker
   means the change breaks correctness or safety; Major means the change
   materially worsens behavior for a demonstrated scenario; stylistic,
   speculative, or pre-existing observations are Minor, Nit, or suppressed by the
   SKILL-115 admission gate.
2. Every maintained pack's specialist content inherits the calibrated definition
   from the shared rubric rather than restating it.
3. A validator rejects a specialist that defines its own severity vocabulary or
   omits the consequence requirement.
4. The lanes with the widest observed Major-to-Blocker spread —
   `ux_accessibility` (38:1) and `data_persistence` (20:1) — carry lane-specific
   consequence examples distinguishing a material defect from an observation.
5. Calibration changes content and validation only. It does not remove a lane,
   narrow routing, or weaken the admission gate.
6. Severity distribution per lane stays measurable: the findings telemetry
   supports a per-category Major/Blocker ratio query so calibration can be
   re-evaluated against future runs.
7. Repository validation gates pass:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Non-Goals

- Reducing specialist coverage, removing a review area, or narrowing routing.
- Weakening the SKILL-115 admission gate or the evidence requirements for
  findings.
- Changing what reopens `implement_fix` — that is subtask 3.
- Changing review depth tiers — that is subtask 5.
- Re-grading historical findings already recorded in telemetry.

## Dependency Notes

Independent of SKILL-141 and of every other SKILL-142 subtask.

Telemetry caveat carried from the parent spec: all distributions come from 154
review runs on this repository — one stack, one author, one codebase. Enough to
act on a 38:1 intra-lane ratio, which is structural. Not enough to conclude how
the review architecture performs on other codebases. Criterion 6 exists so this
calibration can be re-evaluated once multi-repo telemetry exists.

## Validation Strategy

- Validator acceptance test: a specialist inheriting the shared calibrated
  rubric passes.
- Validator rejection tests: a specialist defining its own severity vocabulary is
  rejected; a specialist omitting the consequence requirement is rejected.
- Content test asserting `ux_accessibility` and `data_persistence` carry
  lane-specific consequence examples.
- Routing regression test asserting lane count and routing behavior are
  unchanged.
- Telemetry test asserting a per-category Major/Blocker ratio query is
  supported.
- Focused Gradle tests for changed modules, then the full repository gates.

## Next Path

On completion, proceed to subtask 5 (inline as a bounded light tier; remediation
pass bounded to its delta).
