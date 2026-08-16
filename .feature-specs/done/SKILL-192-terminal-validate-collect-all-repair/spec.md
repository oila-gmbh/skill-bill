# SKILL-192 — Last-subtask validate collects every failure, then repairs once

## Intended Outcome

The last goal child's `validate` phase (and any standalone `ValidationDepth.FULL`
validate) stops fail-fast stair-stepping through compile errors. The runtime runs
**one collect-all full check**, persists the **complete** finding set, has the
agent produce a repair plan and fix **every** finding in one repair pass with
per-finding substantiation, then runs **one** confirming collect-all check.

Intermediate goal children stay `ValidationDepth.BUILD_ONLY`: compile/build
failures only. They do not become full-suite green-makers.

## Background

SKILL-173 already stamps `build_only` on every non-last goal child and `full` on
the last non-skipped child. SKILL-180 moved gate execution into the runtime so
the agent does not invoke the gate. That combination is the right split. The
FULL cycle is the part that is wrong.

Today `FeatureTaskRuntimeValidationGateCoordinator` loops `while (true)`:

1. Run pack `full_gate_command` (`./gradlew check`) cache-eligible, **without**
   continue-on-failure.
2. Parse **JUnit XML only**.
3. Hand whatever that run could see to a repair agent.
4. Repeat until a cache-eligible run passes, then force a cache-bypassing run.

Gradle stops the task graph on the first failed task. A compile error prevents
later modules and test suites from running. Compiler diagnostics never appear in
JUnit XML, so the agent often receives a single synthetic
`unparseable_gate_failure` instead of the compiler list. After compile is fixed,
the next run is the first time tests exist as findings. That is a new loop by
construction.

Measured on SKILL-191 subtask 9 validate (2026-08-15), four consecutive
cache-eligible failures:

| Run | Executed work units | Duration |
| --- | --- | --- |
| 1 | 3 | 10.5s |
| 2 | 10 | 6.1s |
| 3 | 33 | 4.4 min |
| 4 | 4 | 3.7s |

Run 3 is the only substantial check. The short runs are fail-fast. Wall-clock
in the phase is hours; Gradle work is minutes. The SKILL-180 cap was later
removed (`gate keeps repairing beyond the previous cap`), so this loop has no
bound.

The SKILL-180 directive already *asks* for "complete finding set from one gate
run, fix every finding, rerun to verify." Fail-fast Gradle plus JUnit-only
parsing make that physically false for FULL validate.

## Design

### Two depths, two cycles

`BUILD_ONLY` (intermediate goal children) keeps today's compile/build gate,
findings, and repair cycle. This feature does not retune it into a full check.

`FULL` (last non-skipped goal child, and standalone feature-task validate at
full depth) uses a **collect-all cycle**:

1. **Discovery.** Runtime runs the pack-declared collect-all full gate
   (cache-eligible). Continue-on-failure is pack argv, never a hardcoded
   Gradle flag. Parse the union of compiler diagnostics and JUnit (or pack-
   equivalent) artifacts. Persist the complete finding set. If that set is
   empty and the run passed, skip to confirmation.
2. **Plan.** Runtime (or one agent launch that does not invoke the gate)
   produces a durable repair plan covering every discovery finding identity.
   Findings that share a root cause are one plan item, not several.
3. **Repair pass.** One pass attempts every plan item. The agent must not
   invoke the gate or any quality-check skill. Each finding gets a
   substantiation receipt (root cause, changed paths, why the finding is gone).
   If the complete set does not fit one prompt budget, the runtime pages the
   plan across multiple agent launches **inside this pass** without rerunning
   the gate.
4. **Confirmation.** Runtime runs the pack-declared **cache-bypassing**
   collect-all full gate. A zero-work result never satisfies. Every discovery
   finding identity must be absent. New identities are remaining work, not a
   fail-fast compile loop.

Happy path is two collect-all gate runs: discovery and confirmation. A failed
confirmation is itself the next complete finding set; the runtime does not
insert a third discovery run. A small explicit cap bounds confirmation-retry
repair passes. Exhaustion is a durable blocked state carrying remaining
findings, never a silent pass.

### Collect-all is "everything this invocation can observe"

`--continue` (or pack equivalent) still cannot run tests of a module that failed
to compile. Collect-all means: every compiler diagnostic the continued graph
emits, plus every test/static finding from modules that did compile, in **one**
invocation. It is not "tests of code that does not compile."

### Substantiation vs confirmation

Per-finding receipts are the repair contract. The confirmation collect-all is
the only suite-level proof. The runtime must not run the full gate per finding,
and must not accept an agent-reported `gate_run_count` as evidence. A
confirmation run that still contains a discovery identity disproves that
finding's substantiation.

### Pack-owned argv and parsers

SKILL-180 forbids hardcoded stack flags. Collect-all argv and compiler-
diagnostic parsing are platform-pack declarations, schema-bumped, loud-fail
when malformed. Shipped `kotlin` and `kmp` packs declare them.

## Acceptance Criteria

1. Last non-skipped goal-child validate (`ValidationDepth.FULL`) and standalone
   full validate run a collect-all discovery gate, persist the complete finding
   set from that one run, then enter one repair pass over every finding before
   any confirming gate run.
2. Intermediate goal-child validate stays `ValidationDepth.BUILD_ONLY` and keeps
   compile/build-only gate argv and today's BUILD_ONLY repair cycle.
3. The collect-all full-gate argv and its cache-bypassing confirmation variant
   are pack-declared; the runtime never hardcodes `--continue`, `--rerun-tasks`,
   or `--no-build-cache`.
4. Discovery findings are the union of pack-declared compiler diagnostics and
   test/static artifacts from that one run, not JUnit XML alone, and not a
   synthetic `unparseable_gate_failure` when compiler diagnostics parsed.
5. The repair pass fixes every discovery finding; findings that share one root
   cause are one fix. The agent does not invoke the gate or any quality-check
   skill.
6. Each finding in the discovery set has a substantiation receipt before
   confirmation is allowed to run.
7. Confirmation is one cache-bypassing collect-all run. A zero-work result never
   satisfies. Every discovery finding identity must be absent for a green
   outcome.
8. Happy-path FULL validate measures exactly two collect-all gate runs
   (discovery, confirmation) when discovery is dirty and confirmation is green;
   a clean discovery still requires the confirming cache-bypassing run.
9. A failed confirmation does not revert to fail-fast `./gradlew check` loops;
   remaining identities become the next complete set for one additional repair
   pass, bounded by an explicit cap whose exhaustion is a durable blocked
   state carrying remaining findings.
10. Handoff budget may page the plan across agent launches inside one repair
    pass; it must not drop discovery findings silently, and it must not rerun
    the gate to "rediscover" already persisted findings.
11. The SKILL-180 suppression gate still runs only after a green confirmation.
12. `validation_result` stays a bounded projection: measured `gate_run_count` /
    `gate_runs`, no raw stdout, no transcripts, no agent-invented run counts.
13. `(cd runtime-kotlin && ./gradlew check)` passes with no new suppressions
    introduced by this feature's own implementation.

## Constraints

- Pack commands stay pack-owned. Schema changes land in
  `orchestration/contracts/platform-pack-schema.yaml` first, with a shell-contract
  bump and Kotlin/kmp pack updates in the same feature.
- Validation still owns build and test execution. Audit and repair evidence stay
  read-only repository facts except for the validate agent's code edits.
- Existing SKILL-173 last-vs-intermediate depth stamping stays the selector;
  this feature changes the FULL cycle, not who is FULL.
- SKILL-191 may still be in flight on this repository; this spec must not
  require stopping or rewriting that goal.

## Non-Goals

- Changing BUILD_ONLY argv, BUILD_ONLY finding parsers, or intermediate-child
  depth assignment.
- Per-finding full-gate or filtered `gradlew test --tests` loops (the SKILL-176
  39-invocation failure mode).
- Agent-run validate fallback for packs that declare no `validation_gate`.
- Making every subtask's validate a full-suite green-maker.
- Reintroducing an unbounded `while (true)` FULL cycle.
- Teaching Gradle to test modules that failed to compile.

## Subtasks

1. `spec_subtask_1_collect_all_gate_and_findings.md` — pack schema, shipped
   pack argv, compiler-plus-test finding extraction, collect-all runner modes.
2. `spec_subtask_2_full_validate_discover_plan_repair_confirm.md` — FULL cycle
   replaces fail-fast `while (true)`; BUILD_ONLY unchanged; paging without
   extra gate runs; confirmation cap.
3. `spec_subtask_3_substantiation_and_confirmation_closure.md` — repair plan,
   per-finding receipts, confirmation identity closure, prompts, docs, tests.

## Next Path

```bash
skill-bill goal SKILL-192
```

Do not launch that goal in this repository while SKILL-191 still holds the
worktree. Wait until SKILL-191 is terminal or paused, then start SKILL-192 on a
tree the new goal may own.
