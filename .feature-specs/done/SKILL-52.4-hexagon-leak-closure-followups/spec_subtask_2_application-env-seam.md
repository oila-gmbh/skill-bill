# SKILL-52.4 · Subtask 2 — Application environment seam (Phase 1)

Parent overview: [spec.md](./spec.md)

Removes the Tier-1 application-layer environment leaks in `GoalRunner` (F1
threading/timing, F2 concrete JDK logger) behind injected ports, and lands the
arch test that forbids the banned APIs across all of `runtime-application` main.

Branch: `feat/SKILL-52.4-hexagon-leak-closure-followups` (same-branch model, one commit for this subtask).

## Dependencies

- depends_on: [1]
- dependency_reason: Subtask 1's widened scans and per-module dependency test
  must be in place so the new ports/adapters and DI wiring cannot introduce an
  undetected upward edge or raw-map leak, and so the new application-layer
  banned-API arch test composes with the Phase 0 enforcement surface.
- dependencies: [{subtask_id: 1, optional: false, skipped: false}]

## Scope (owns)

- **F1 — timing/threading leak.** Introduce a minimal timing port (bounded
  wait/retry) in `runtime-ports` (e.g. `skillbill.ports.time.*`). Inject into
  `GoalRunner` (`runtime-application/.../application/GoalRunner.kt`): replace
  `Thread.sleep(NO_TERMINAL_OUTCOME_RECHECK_DELAY_MILLIS)` at line 945 (constant
  `200L` ~line 963) inside `waitForLateTerminalOutcome` and the hand-rolled
  `Thread.currentThread().isInterrupted` retry poke. `infra-fs` provides the real
  `Thread.sleep`-backed adapter; `runtime-core` wires it. The adapter keeps the
  existing `200L`/attempt constants — no production delay change.
- **F2 — concrete logger leak.** Replace
  `private val log = java.util.logging.Logger.getLogger(...)`
  (`GoalRunner.kt:60`, used at 561/578) with a minimal diagnostics/logging port
  in `runtime-ports` (resolves open question 1: introduce a minimal diagnostics
  port so behavior is preserved). `infra-fs` provides the JDK-logger adapter;
  `runtime-core` wires it.
- **Arch test.** Extend the existing application/domain-purity scan to forbid in
  ALL `runtime-application` main source: `Thread.sleep`, `java.util.logging`,
  `*.getLogger(`, `java.util.concurrent` executors, and threading APIs. Scan
  scope is ALL of `runtime-application` main — not just `GoalRunner` — because
  `java.util.logging` also lives in `GoalRunnerProgressEventEmitter`,
  `GoalRunnerLedgerRecorder`, `FeatureTaskRuntimeLifecycleTelemetry`,
  `FeatureTaskRuntimeSpecGate`; those must move to injected diagnostics too.
- **Unit test.** Drive `waitForLateTerminalOutcome` with synthetic time via the
  fake timing port — no real wall-clock delay.

## Reusable patterns / pitfalls

- Reuse the SKILL-66 injected-`Clock` + application-seam emitter pattern: Unit
  methods + `NONE` no-op implementation. The existing injected `Clock`
  (`GoalRunner.kt` ~line 56) is the precedent for the timing/diagnostics ports.
- SKILL-63.1 precedent: "move the log into the adapter that holds the Logger; do
  NOT widen the scan into infra." Keep the JDK logger inside the infra adapter.
- `ImplementationOwnershipArchitectureTest.allowedCompositionImports` trips on
  new `@Provides` port imports — EXTEND the allow-list, never suppress
  (SKILL-65).
- Write the application-layer banned-API arch test first; confirm it is red
  against the unfixed tree (it should flag the existing `Thread.sleep` /
  `java.util.logging`), then fix to green.
- No explanatory comments.

## Acceptance Criteria

1. AC6: `runtime-application` main source contains no `Thread.sleep`,
   `java.util.logging`, `*.getLogger(`, executor/threading APIs; arch test
   enforces it across ALL runtime-application main (including the four named
   emitter/telemetry/gate classes).
2. AC7: minimal timing port AND minimal diagnostics/logging port introduced in
   runtime-ports; both injected into GoalRunner; infra-fs provides real adapters;
   runtime-core wires them; `waitForLateTerminalOutcome` unit-tested with
   synthetic time and no real delay.
3. AC14 (this subtask's slice): all four canonical gates pass.
4. AC15 (this subtask's slice): golden/wire outputs (CLI JSON, MCP payloads,
   install-plan, workflow snapshots) byte-identical to pre-change.

## Non-goals

- No god-object decomposition (GoalRunner stays one class; that is F13, not in
  scope here).
- No desktop changes.
- No behavioral change; production delay constants preserved.

## Validation strategy

`bill-code-check` (Kotlin/Gradle → `./gradlew check`). Run all four canonical
gates. Snapshot golden outputs before, assert byte-equality after.

## Handoff prompt

Run bill-feature-task on
`.feature-specs/SKILL-52.4-hexagon-leak-closure-followups/spec_subtask_2_application-env-seam.md`.
