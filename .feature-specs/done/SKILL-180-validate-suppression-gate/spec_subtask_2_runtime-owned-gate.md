# SKILL-180 · Subtask 2: Runtime-owned gate execution

## Scope

Move validation-gate execution out of the agent and into the runtime, so batched
repair is structural instead of requested and the run count is measured instead of
claimed.

Measured motivation: one validate phase issued **39** Gradle invocations against a
contract allowing 2, costing ~74 minutes of which only ~2 were compilation. See
the parent spec.

**A. Declared gate command**

The runtime needs to know what "the gate" is. Resolve it from the platform pack —
the same declaration seam `bill-code-check` already uses to auto-route to a
stack-specific quality skill. Kotlin declares its Gradle invocation, PHP its
Composer/PHPStan invocation, and so on.

- No gate command is hardcoded in the runtime.
- A repository whose pack declares no gate command falls back to today's
  agent-run behavior rather than silently validating nothing. That fallback is a
  documented, surfaced degradation, not a silent one.
- A malformed declaration loud-fails.

**B. Runtime-driven repair cycle**

Replace "agent runs the gate" with a runtime-owned cycle:

1. Runtime runs the gate once and captures the complete finding set.
2. Runtime hands the agent a **bounded projection** of that finding set. The agent
   fixes findings at their root cause and returns; it does not run the gate.
3. Runtime reruns the gate to verify.
4. If findings remain, repeat from step 2 — bounded by an explicit iteration cap.

The cap makes exhaustion a durable, resumable terminal state carrying the
remaining findings, mirroring how the `review_fix` loop caps its own iterations
rather than looping unbounded. Cap exhaustion is not a silent pass.

**C. Bounded finding-set projection**

Gate output is large — a failing `check` on this repository emits far more than
any handoff budget allows. The projection carries what a repair needs (module,
test or rule identity, message, location) and excludes raw transcripts, stack
noise, and telemetry, consistent with existing handoff budgets and the
`validation_result` bounded-projection rule.

Truncation must be explicit: if findings are dropped to fit the budget, the
projection says how many and the cycle does not report success while unreported
findings remain.

**D. Measured run count and timing**

Because the runtime now owns invocation, the gate-run count is a measurement, not
a claim. Persist per-phase: the run count, per-run duration, and per-run outcome.
Surface the count in goal status so a phase that needed many cycles is visible
while it is happening rather than only in hindsight.

This replaces the agent-reported count considered earlier and rejected: an agent
running the gate 39 times against a limit of 2 is exactly the agent that would
report 2.

**E. Gate results must be attributable to the current tree**

Observed on 2026-08-10: immediately after a run whose full gate reported 44 test
failures in 4m23s, a repeat `./gradlew check` returned `BUILD SUCCESSFUL in 3s`
with 158 of 159 tasks `UP-TO-DATE` and one executed. The second result was not
wrong about its own inputs — it was simply not evidence about the tree being
validated, and nothing checked the difference. A validate phase reading that
3-second green would have recorded a satisfied outcome over a failing tree. This
repository has prior history of the same class of defect: the build cache serving
stale test classes, which `clean` did not clear and `--no-build-cache` did.

The fix is not to disable caching globally — that would make every gate run pay a
full recompile and directly worsen the cost this subtask exists to reduce. Bind
trust to execution instead:

- Intermediate repair-cycle runs MAY use the build cache and up-to-date checks.
  A stale intermediate result costs one extra iteration, nothing more.
- The **terminal verifying run** — the one whose outcome produces a satisfied
  `validation_status` — MUST execute for real, with the build cache and
  up-to-date short-circuiting bypassed.
- A gate result that reports zero executed work is never sufficient evidence for
  a satisfied terminal outcome.
- The runtime records, per run, whether it was cache-eligible or a forced full
  execution, so a green outcome can be audited back to a run that actually ran.

The declared gate command therefore needs a declared cache-bypassing variant per
platform pack, not a hardcoded `--no-build-cache`, which is Gradle-specific.

**F. Depth handling**

Under `ValidationDepth.BUILD_ONLY` the runtime runs the declared compile/build
command only — no tests, detekt, spotless, lint, or dependency scanners — and the
same cycle, cap, and measurement apply.

**G. Supersede subtask 1's gate-invocation clause**

Subtask 1 instructs the validate agent to invoke `bill-code-check`, which is
correct while the agent still runs the gate. This subtask replaces that clause
with the runtime-owned contract: the agent receives a finding set and returns
fixes, and does not invoke the gate or any quality-check skill. This is a planned
handoff, not a contradiction — subtask 1 records it as transitional.

The no-suppression clause subtask 1 adds is permanent and must survive this
rewrite unmodified.

**H. Ownership boundary**

This moves build and test execution from the agent to the runtime. The repo's
existing boundary — validation owns execution while audit and repair evidence stay
read-only repository facts — must be restated coherently in `AGENTS.md` and the
affected `agent/decisions.md`, not silently broken.

## Acceptance Criteria (this subtask)

1. The runtime executes the validation gate; the validate agent does not invoke it.
2. The gate command is resolved from platform-pack declarations, with no gate
   command hardcoded in the runtime.
3. A repository whose pack declares no gate command falls back to agent-run
   behavior, and that degradation is surfaced rather than silent.
4. A malformed gate declaration loud-fails instead of degrading to "no gate".
5. The runtime hands the agent a bounded finding-set projection carrying module,
   rule or test identity, message, and location, and excluding raw command
   output, transcripts, and telemetry.
6. When the projection truncates findings to fit its budget, it reports how many
   were dropped, and the cycle never reports success while unreported findings
   remain.
7. The repair cycle is bounded by an explicit iteration cap.
8. Exhausting the cap produces a durable, resumable terminal state carrying the
   remaining findings, and never a silent pass.
9. The gate-run count, per-run duration, and per-run outcome are measured by the
   runtime and durably persisted with the phase output.
10. The measured gate-run count is surfaced in goal status while the phase is
    running.
11. No code path accepts an agent-reported gate-run count as evidence.
12. The terminal verifying gate run — the one producing a satisfied
    `validation_status` — executes with the build cache and up-to-date
    short-circuiting bypassed, via a platform-pack-declared cache-bypassing
    variant rather than a hardcoded Gradle flag.
13. Intermediate repair-cycle runs remain cache-eligible.
14. A gate result reporting zero executed work never satisfies a terminal
    outcome.
15. Each run records whether it was cache-eligible or a forced full execution,
    and that is durably persisted so a green outcome is auditable back to a run
    that actually executed.
16. Under `ValidationDepth.BUILD_ONLY` the runtime runs the declared compile/build
    command only, and the same cycle, cap, and measurement apply.
17. The validate directive instructs the agent that it receives a finding set and
    does not invoke the gate or any quality-check skill, replacing subtask 1's
    transitional `bill-code-check` invocation clause.
18. The no-suppression clause added in subtask 1 survives that rewrite unmodified.
19. `AGENTS.md` and the affected `agent/decisions.md` state the revised ownership
    boundary for build and test execution.
20. Regression coverage proves: single-pass clean, multi-iteration convergence,
    cap exhaustion, truncated projection, missing gate declaration, malformed
    declaration, a cache-served zero-work result rejected as terminal evidence,
    and the BUILD_ONLY path.

## Non-Goals

- Suppression measurement and its justification contract; that is subtask 3.
- Any directive change beyond the gate-invocation clause named in section G. The
  no-suppression clause and the `agent/history.md` correction stay subtask 1's.
- Restructuring any phase other than validate.
- Introducing a backward edge for validate in the phase graph. The repair cycle is
  internal to the phase; adding a graph-level loop is a larger change than this
  feature needs.

## Dependency Notes

Depends on subtask 1, which establishes the permanent no-suppression clause this
subtask must preserve, and whose transitional `bill-code-check` invocation clause
this subtask replaces (section F). Landing subtask 1 first keeps every intermediate
state coherent: while the agent still runs the gate it is told to route through
`bill-code-check`, and once the runtime owns execution it is told it receives
findings instead.

## Validation Strategy

- Unit coverage for gate-command resolution: declared, absent, malformed.
- Cycle coverage: clean first pass, convergence across iterations, cap exhaustion
  producing a resumable terminal state.
- Projection coverage: budget enforcement and explicit truncation reporting.
- Measurement coverage: run count, durations, and outcomes persisted and surfaced.
- Cache-attribution coverage: a zero-work cache-served result must not satisfy a
  terminal outcome, and the terminal run must be a forced execution.
- Depth coverage for `BUILD_ONLY`.
- `(cd runtime-kotlin && ./gradlew check)` with no new suppressions.

## Next Path

Proceed to `spec_subtask_3_suppression-diff-gate.md`.
