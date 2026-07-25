# SKILL-145 Subtask 1: Lifecycle evidence and failure reproduction

## Scope

Define the observable delegated-review lifecycle and reproduce the failures seen in
SKILL-143: broad scope selection, long coordinator setup, worker fan-out, missing
durable specialist progress, incomplete aggregation, interruption, and malformed
terminal output classification.

## Acceptance Criteria

1. A lifecycle model names coordinator, worker, aggregation, and terminal states and identifies which transitions must be durable.
2. A bounded fixture reproduces a coordinator that launches workers without completing aggregation.
3. Evidence distinguishes MCP/process heartbeat from declared specialist progress.
4. A bounded fixture reproduces an interrupted or non-zero agent run and proves it must block as a process failure rather than enter schema repair.
5. A scope fixture proves the selected base/head delta and baseline-untracked set exclude unrelated historical changes.
6. The output includes exact timestamps, worker identities, routed areas, process outcomes, durable events, and rejected output evidence needed by later subtasks.
7. Every observed failure mode in the parent spec is mapped to transcript evidence, a deterministic reproduction, or a documented reason it cannot be reproduced.
8. Every known contract risk in the parent spec is mapped to an existing guard/test or an explicit missing test and proposed observation seam.

## Non-Goals

- Choosing final timeout values.
- Changing provider launch policies.
- Enabling delegated review by default.

## Dependency Notes

This is the first subtask and has no dependencies. Its evidence package is required
by provider evaluation.

## Validation Strategy

- Add deterministic lifecycle fixtures with fake clocks and launch outcomes.
- Compare persisted workflow artifacts with captured provider transcripts.
- Verify failure classification and scope digest assertions.
- Produce a failure-mode matrix keyed to all numbered entries in the parent spec with columns for status, provider, trigger, durable evidence, expected terminal class, current behavior, and owner.

## Next Path

Proceed to provider-specific reliability and capacity evaluation.
