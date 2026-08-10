# SKILL-180: Runtime-owned validation gate

## Intended Outcome

The `validate` phase stops being a set of polite requests the runtime cannot
verify. Today the phase depends entirely on agent compliance for two contracts —
run the gate in one batch, and never silence a finding with a suppression — and
the runtime has no way to observe, measure, or enforce either. This feature moves
gate execution into the runtime, so batching is structural rather than requested,
and adds a runtime-measured suppression delta, so "green" means the gate actually
passed rather than that the agent said it did.

## Problem Evidence

### Measured: the batching contract is ignored, at ~35× cost

The validate directive mandates batched repair — one gate run to collect the
complete finding set, fix every finding, one rerun to verify — and explicitly
forbids rerunning after individual fixes.

A goal run on 2026-08-10 (SKILL-176 subtask 6) was measured against that contract
from the Gradle daemon logs:

| Signal | Value |
|---|---|
| Gradle invocations during one validate phase | **39** |
| Duration of each invocation | 1–6 seconds |
| Total Gradle work | ~2 minutes |
| Wall-clock elapsed in the phase | ~74 minutes |
| Time not spent building | ~72 minutes (agent round-trips) |
| Contract allowance | 2 runs |

Each invocation was a single-test-filtered run (`:runtime-application:test`
failing in 1–3 s, against ~1m07s for the real task), separated by 30 s–4 min of
model latency. The phase was doing fix-one → rerun → fix-one → rerun, 39 times.
An operator running the full gate by hand at the same time completed it in
**4m23s**.

The cost is not compilation. The gate is cheap when run once. The cost is paying
a model round-trip per finding.

### The runtime cannot see any of it

`validate` has **no backward edge** in the phase graph — backward edges exist only
from `review` (`review_fix`), `audit` (`audit_gap`), and the preplan/plan/implement
regeneration loops. The runtime launches the validate agent exactly once and
receives one `validation_result`. Every gate run happens inside a single agent
session the runtime does not observe. `validation_result` carries
`validation_status`, `checks`, and `repository_checkpoint` — no run count, no
timing. 39 runs and 2 runs are indistinguishable.

A self-reported run count does not fix this: an agent that runs the gate 39 times
against a stated limit of 2 is exactly the agent that will report 2.

### The suppression rule has the same shape

1. The rule text exists only in the quality-check packs —
   `platform-packs/kotlin/quality-check/bill-kotlin-code-check/content.md:36`,
   *"Never suppress a failure with annotations, baselines, disabled rules, or
   skipped tests"* — with equivalents in the ios, php, go, and rust packs.
2. The validate directive
   (`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimePhasePromptDirectives.kt:346`)
   never invokes `bill-code-check` and never mentions suppressions, so the rule
   never reaches the agent that runs the gate. Compare
   `skills/bill-pr-review-fix/content.md:114`, which does both.
3. Nothing enforces it: no detekt rule, no baseline diff, no runtime inspection of
   the changed set.
4. Injected repo memory prescribes the opposite.
   `runtime-kotlin/runtime-infra-fs/agent/history.md:181` and `:204` name
   `@Suppress` as the established pattern; `:204` says *"resolve with
   `@Suppress("TooManyFunctions")` + one-line rationale (established 67-file
   pattern), **not a refactor**"*. `runtime-application/src/main` carries
   suppressions in 36 files.

Both problems are one problem: correct policy, stated in a prompt, with no
verification behind it.

## Acceptance Criteria

1. The runtime executes the repository validation gate itself; the validate agent
   does not invoke the gate.
2. The runtime hands the agent a bounded projection of the complete finding set
   from one gate run, and the agent returns fixes without running the gate.
3. The runtime reruns the gate to verify, and the number of gate runs per validate
   phase is measured by the runtime rather than reported by the agent.
4. The repair cycle is bounded by an explicit iteration cap; exhausting it is a
   durable, resumable terminal state, not an unbounded loop.
5. The gate command is resolved from platform-pack declarations, so a non-Kotlin
   repository runs its own gate and no gate command is hardcoded.
6. The measured gate-run count and per-run timing are durably persisted with the
   phase output and surfaced in goal status.
7. The terminal verifying gate run executes with build-cache and up-to-date
   short-circuiting bypassed, intermediate repair runs stay cache-eligible, and a
   gate result reporting zero executed work never satisfies a terminal outcome.
8. The runtime measures new suppression-marker occurrences in the changed paths
   against the run's base ref, from repository evidence it reads itself.
9. Suppression markers are resolved from platform-pack declarations, not
   hardcoded Kotlin syntax, and suppressions present at the base ref are never
   counted as introduced.
10. A validate phase that introduces suppressions without a per-suppression
   justification in `validation_result` does not reach a satisfied terminal
   state, and the blocked reason names the offending paths.
11. A validate phase that introduces no suppressions is unaffected: no new
    required field and no new failure mode.
12. `validation_result` remains a bounded projection: no raw command output, no
    transcripts, no telemetry.
13. `ValidationDepth.BUILD_ONLY` runs runtime-owned compile/build only, with no
    tests, detekt, spotless, lint, or dependency scanners.
14. `(cd runtime-kotlin && ./gradlew check)` passes with no new suppressions
    introduced by this feature's own implementation.

## Constraints

- The validate directive text is shared across platform packs; every clause added
  must stay stack-agnostic and name `bill-code-check`, never a stack-specific
  quality skill.
- Gate output is large. The runtime must bound the finding-set projection before
  it reaches the agent, consistent with existing handoff budgets.
- Existing suppressions are legitimate history. The gate measures the delta
  against the base ref only.
- `BUILD_ONLY` deliberately forbids the full gate. Its suppression clause is about
  not *introducing* suppressions while repairing compile errors.
- Moving gate execution into the runtime changes which component owns build and
  test execution. The existing boundary — validation owns execution, audit and
  repair evidence stay read-only — must be restated coherently, not silently
  broken.

## Non-Goals

- A detekt rule banning `@Suppress`. Considered and rejected: Kotlin-only, and it
  offers no justification path for a legitimate suppression.
- Removing or refactoring the 36 existing suppressions in
  `runtime-application/src/main`.
- Gating or restructuring any phase other than validate.
- Changing the quality-check pack rule text, which is already correct.
- An agent-reported gate-run count. Explicitly rejected: the measured evidence
  above shows self-reporting cannot detect the failure it would exist to detect.

## Subtasks

1. `spec_subtask_1_directive-and-history.md` — reach the agent: validate directive
   clause, BUILD_ONLY variant, `bill-code-check` invocation, and the
   `agent/history.md` correction.
2. `spec_subtask_2_runtime-owned-gate.md` — the runtime runs the gate, bounds the
   finding set, drives a capped repair cycle, and measures the run count.
3. `spec_subtask_3_suppression-diff-gate.md` — runtime-measured suppression delta
   against the base ref, with the justification contract and the block.

## Next Path

```bash
skill-bill goal SKILL-180
```
