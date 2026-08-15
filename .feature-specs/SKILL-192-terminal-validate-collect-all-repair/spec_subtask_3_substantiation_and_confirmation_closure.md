# SKILL-192 · Subtask 3: Repair plan, substantiation receipts, confirmation closure

## Intended Outcome

The FULL collect-all cycle from subtask 2 does not treat "agent returned
completed" as proof. Discovery findings become a durable repair plan. Each
finding gets a substantiation receipt. Confirmation is green only when every
discovery identity is absent from the confirmation finding set.

## Scope

- Persist a repair plan covering every discovery finding identity before the
  repair agent is allowed to finish the pass. Plan items may group findings
  that share a root cause; every identity still appears.
- Require a substantiation receipt per discovery identity before confirmation
  runs: finding identity, root cause, changed paths or symbols, and a short
  rationale that the finding is gone. Receipts are structured artifacts, not
  raw command logs.
- The runtime does **not** run the full gate or a filtered per-test Gradle
  invocation to substantiate a single finding (SKILL-176 39-run failure).
  Confirmation collect-all is the only suite proof.
- Confirmation closure: every discovery identity must be absent from
  confirmation findings. A still-present identity is a failed substantiation
  and remains in the next complete set. New confirmation identities (regressions
  or newly unmasked tests) join that set; they were not observable at discovery
  if compile had blocked them — that is expected and is not a fail-fast loop.
- A repair pass that omits a discovery identity, or emits a receipt that does
  not name one, is rejected and re-enters the same pass without a new gate run
  (producer-side gate, same validator as launch).
- FULL validate prompts: receive the complete set and plan; fix every finding
  at its root cause; emit receipts; do not invoke the gate or quality-check
  skills; do not rediscover by running checks. BUILD_ONLY prompt text stays
  compile/build-only.
- Record the decision in `runtime-kotlin/agent/decisions.md` (and AGENTS.md
  ownership sentence if the validate boundary wording must mention collect-all
  FULL vs BUILD_ONLY intermediate).
- Goal status / `gate_runs` remain measurements. Do not put location-bearing
  finding bodies on status; remaining counts are enough, detail stays behind
  existing findings retrieval if already present.
- Tests: omitted receipt blocks confirmation; leftover discovery identity on
  confirmation is not a pass; grouped root-cause plan still requires every
  identity closed; BUILD_ONLY does not require this receipt schema.

## Acceptance Criteria

1. FULL dirty discovery persists a repair plan that lists every discovery
   finding identity before confirmation is eligible.
2. Confirmation does not start unless every discovery identity has a
   substantiation receipt naming that identity, a root cause, and changed
   paths or symbols.
3. A completed repair payload that omits an identity or its receipt is
   rejected and the same repair pass relaunches without a new gate run.
4. Green confirmation requires the confirmation finding set to contain none of
   the discovery identities; measured confirmation outcome PASSED is not enough
   if identity closure is skipped.
5. A confirmation finding that matches a discovery identity is treated as
   failed substantiation for that identity and is included in the next repair
   set.
6. New confirmation identities not in discovery are included in the next
   complete set; the cycle still uses collect-all, not fail-fast check.
7. FULL validate agent prompts state that the runtime owns collect-all
   execution, that the agent fixes the complete plan, that each finding needs
   a receipt, and that the agent must not invoke the gate.
8. BUILD_ONLY prompts and BUILD_ONLY cycle do not require FULL substantiation
   receipts or collect-all confirmation closure.
9. The runtime never substantiates a finding by launching the pack full gate
   or a per-test filtered suite.
10. `AGENTS.md` / `runtime-kotlin/agent/decisions.md` state that last-subtask
    FULL validate collects all observable failures in one discovery run and
    proves repairs by confirmation identity closure.
11. Regression coverage includes: receipt omission rejected without extra gate
    run; leftover identity fails confirmation; grouped plan item still closes
    every identity; BUILD_ONLY unaffected; no per-finding Gradle launch in the
    coordinator.
12. `(cd runtime-kotlin && ./gradlew check)` passes with no new suppressions
    introduced by this subtask.

## Non-Goals

- Replacing JUnit or compiler parsers (subtask 1).
- Changing discovery/confirmation argv selection (subtask 2).
- Audit-phase repair ledgers, review remediation receipts, or unaddressed
  review findings.
- Displaying full finding locations on `skill-bill goal status`.

## Dependency Notes

Depends on subtask 2 (FULL discover/plan/repair/confirm cycle and paging).
Subtask 1's union finding identities are the closure keys.

## Validation Strategy

- Coordinator/unit tests: confirmation blocked when receipts are missing;
  relaunch has the same `gate_run_count`.
- Confirmation with a leftover discovery identity is FAILED even if a stub
  runner reports PASSED (identity closure is runtime-owned).
- Confirmation with only new identities (no leftover discovery ids) is a
  failed confirmation that feeds those new ids to the next repair pass.
- Prompt composer tests lock FULL collect-all / no-gate / complete-plan
  wording and keep BUILD_ONLY compile-only wording.
- Architecture or coordinator test: no per-finding process launch API on the
  FULL repair path.
- `runtime-kotlin` `check`.

## Next Path

After this subtask the parent feature is complete. Launch
`skill-bill goal SKILL-192` only when no other goal owns this worktree.
