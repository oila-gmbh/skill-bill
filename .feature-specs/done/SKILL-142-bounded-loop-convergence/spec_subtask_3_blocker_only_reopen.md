# SKILL-142 Subtask 3 — Blocker-only reopen

Parent: `.feature-specs/SKILL-142-bounded-loop-convergence/spec.md` (unit 2)

## Scope

Make governed prose agree with the runtime's own advancement semantics: only an
unresolved Blocker reopens `implement_fix`. Major joins Minor and Nit in the
unaddressed-findings ledger.

`skills/bill-feature-goal/content.md:507` currently says:

> A Blocker or Major finding reopens `implement_fix` ... After the bounded
> remediation, only an unresolved Blocker stops advancement — a surviving Major
> moves on and is recorded in the ledger.

Major can *consume* the single remediation iteration but never *stop* the run.
The runtime already agrees Major is non-blocking: `GoalSubtaskReviewState.blocksAdvance`
is Blocker-only. The reopen rule is governed prose contradicting the runtime.

This is a governed-prose plus parity-test change. No Kotlin behavior change is
expected.

## Evidence

Local telemetry, 154 review runs / 987 findings (`~/.skill-bill/review-metrics.db`):

| mode | runs | findings | per run | blockers | blockers/run |
|---|---|---|---|---|---|
| delegated | 91 | 695 | 7.6 | 26 | 0.29 |
| inline | 43 | 131 | 3.0 | 8 | 0.19 |

Delegated review emits 408 Majors across 91 runs — 4.5 per run — each able to
reopen `implement_fix`, against 0.29 Blockers per run that can actually stop the
run.

Evidence sites:

- `skills/bill-feature-goal/content.md:507` — the "Blocker or Major finding
  reopens `implement_fix`" rule.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/GoalSubtaskReviewState.kt:84-89`
  — `blocksAdvance` is already Blocker-only; no runtime change needed.

## Acceptance Criteria

1. Only an unresolved Blocker reopens `implement_fix`. Major, Minor, and Nit
   advance and are recorded in the goal-wide unaddressed-findings ledger.
2. `skills/bill-feature-goal/content.md` states this without contradiction, and
   no governed surface still claims Major reopens the loop.
3. The runtime's Blocker-only `blocksAdvance` semantics are unchanged.
4. A test asserts governed prose and runtime advancement semantics agree, so the
   contradiction cannot silently reappear.
5. A review pass emitting only Major findings advances to `validate` with the
   Majors in the ledger and zero `implement_fix` iterations consumed.
6. A review pass emitting one Blocker plus several Majors consumes exactly one
   `implement_fix` iteration.
7. Goal-facing output obeys the bounded-output contract: subtask id, pass,
   per-finding verdict, counts, severity, and class/symbol-or-sanitized-stem
   label only. No path, line number, diff hunk, or raw child output.
8. Repository validation gates pass:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Non-Goals

- Changing `blocksAdvance` or any runtime advancement semantics.
- Changing the severity vocabulary itself — that is subtask 4's calibration work.
- Suppressing Major findings or weakening the SKILL-115 admission gate. Majors
  are still emitted and still recorded; they simply stop reopening a loop they
  cannot terminate.
- Reconciling `capExhaustionBehavior` with the Blocker disposition — that belongs
  to subtask 5.

## Dependency Notes

Independent of SKILL-141 and of every other SKILL-142 subtask.

Subtask 5 builds on this unit's Blocker-only semantics when reconciling cap
exhaustion with the remediation disposition.

## Validation Strategy

- Prose/runtime parity test asserting governed reopen semantics match
  `blocksAdvance`.
- Loop test: Major-only pass consumes zero `implement_fix` iterations.
- Loop test: Blocker plus Majors consumes exactly one iteration.
- Ledger test asserting surviving Majors are recorded in the goal-wide
  unaddressed-findings ledger.
- Output test asserting no path or line number reaches goal-facing surfaces.
- Focused Gradle tests for changed modules, then the full repository gates.

## Next Path

On completion, proceed to subtask 4 (lane severity calibration).
