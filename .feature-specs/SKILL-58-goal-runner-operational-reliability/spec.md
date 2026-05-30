# SKILL-58 - bill-goal operational reliability and operator-grade progress UX

Created: 2026-05-30
Status: Draft
Issue key: SKILL-58
Parent: none (top-level follow-up to SKILL-57 live production run)

## Decomposition

This feature is decomposed because the fixes span four boundaries that should be
verified independently:

1. goal lifecycle controls and reset/recovery semantics;
2. durable-state reconciliation and stale-runtime hygiene;
3. foreground progress UX and completion signaling;
4. binary/runtime consistency and release-surface guardrails.

Implement on one branch with a commit per subtask:

1. [Goal Reset + Recovery Contract](./spec_subtask_1_goal-reset-recovery.md)
2. [Durable-State Reconciliation + Stale-Row Hygiene](./spec_subtask_2_durable-reconciliation.md)
3. [Operator Progress UX + Completion Confirmation](./spec_subtask_3_progress-and-confirmation.md)
4. [Runtime/Binary Consistency + Contract Tests](./spec_subtask_4_runtime-consistency-and-validation.md)

## Sources

- SKILL-57 live run on 2026-05-30, including multiple resume attempts.
- Observed runtime symptoms during SKILL-57:
  - blocked continuation due to missing or unreconcilable terminal child state;
  - no first-class reset command for decomposed goals;
  - status drift between checked-in projection and workflow-store runtime;
  - weak/noisy progress visibility in long-running foreground sessions;
  - missing explicit completion confirmation despite eventual terminal success;
  - stale `running` workflow rows left behind after goal completion;
  - effective runtime binary path ambiguity (`~/.skill-bill/runtime/...` symlink
    versus local repo build output).

## Problem

`bill-goal` can complete work end-to-end, but operators lack reliable
operational controls and trustworthy foreground observability when runs become
long or need recovery. The current system allows technically-successful runs
that still feel ambiguous to humans:

- reset/restart requires manual DB and manifest surgery;
- durable runtime and projection can temporarily disagree;
- progress updates can be either too sparse or too noisy;
- completion can happen without a clear, authoritative terminal confirmation in
  the operator stream;
- stale workflow rows can remain `running` after the goal is done;
- runtime behavior can differ depending on which installed binary path is used.

## Goals

1. Provide a first-class goal reset/restart flow that does not require manual
   DB edits.
2. Reconcile durable runtime, status projection, and checked-in manifest so
   operator status is monotonic and trustworthy.
3. Provide predictable foreground progress updates at bounded intervals with
   structured liveness signal quality.
4. Emit an unambiguous terminal completion confirmation when a goal reaches
   `complete`.
5. Prevent stale `running` child-workflow rows from persisting silently after
   terminal parent outcomes.
6. Make runtime binary provenance explicit so operators know exactly which
   `skill-bill` runtime is executing.

## Non-Goals

- No background daemon or detached supervisor.
- No rewrite of workflow-state architecture.
- No schema-version migration for historical workflows beyond current loud-fail
  behavior.
- No change to feature decomposition semantics outside reliability and
  observability behavior.

## Acceptance Criteria

1. `skill-bill goal reset <issue_key>` exists and is safe by default.
   It must:
   - reset decomposition runtime state to a clean restart point;
   - clear blocked/in-progress subtask runtime pointers for the selected issue;
   - preserve completed subtask outcomes unless `--hard` is explicitly provided;
   - print the exact before/after state summary.
2. Reset behavior has a `--hard` mode that clears all subtask runtime progress
   and child workflow linkage for the issue key, with explicit confirmation gate
   (or explicit non-interactive force flag).
3. `goal status` reflects authoritative runtime state and cannot report stale
   blocked/in-progress projection when authoritative child terminal outcome is
   already complete.
4. Goal foreground run emits structured periodic heartbeat lines by default at a
   bounded interval (default 90 seconds) containing at least:
   - issue key;
   - subtask id;
   - current workflow step;
   - latest liveness signal class (`durable_progress`, `file_activity`,
     `output_only`, `idle`).
5. Goal foreground run default output is structured and low-noise: child raw
   stdout/stderr is hidden unless explicit debug mode is enabled.
6. Goal terminal completion emits a single authoritative confirmation line and
   final summary payload in the operator stream, including completed/pending/
   blocked counts and final PR status.
7. On goal completion, any child workflow rows that remain `running` for the
   same issue key and are no longer active must be reconciled to a typed
   terminal status with durable diagnostic reason.
8. CLI startup output for goal runs includes runtime binary provenance:
   resolved executable path and runtime version/build id.
9. Runtime behavior must be consistent across:
   - repo-local built binary path; and
   - installed `~/.skill-bill/runtime` path.
   Contract tests must fail if they diverge on goal status/progress semantics.
10. Maintainer validation passes:
    - `skill-bill validate`
    - `(cd runtime-kotlin && ./gradlew check)`
    - `npx --yes agnix --strict .`
    - `scripts/validate_agent_configs`

## Validation Strategy

- Add focused CLI/runtime tests for:
  - reset soft/hard flows;
  - status reconciliation after interrupted and resumed runs;
  - heartbeat emission cadence and payload shape;
  - completion confirmation emission;
  - stale-running-row reconciliation behavior.
- Run a controlled end-to-end synthetic goal with forced interruption and reset,
  then re-run to completion and verify the final state is clean.
- Run the same scenario once via repo-local runtime binary and once via
  installed runtime path; verify identical status/progress contract output.

## Open Questions

- Should `goal reset` default to preserving completed subtask commit SHAs in
  projection only, or should it also persist a reset-history artifact in durable
  workflow runtime for auditability?
- Should stale child-row reconciliation happen eagerly during `goal status`, or
  only during `goal run` startup/teardown?
- Should heartbeat interval be globally configurable in config file, CLI flag
  only, or both?

