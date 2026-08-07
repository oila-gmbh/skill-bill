# SKILL-164 · Subtask 4: Integration contracts, telemetry, and validation

## Scope

Close the feature: register the contract schema, emit derivation/reuse telemetry so the saving
is measurable, document the artifact, and validate the whole path end to end.

In scope:

- A versioned contract schema for the shared evidence projection under `orchestration/contracts/`,
  validated on read as the other runtime contracts are.
- Telemetry recording, per workflow run: evidence derivations, reuses, and re-derivations
  attributed to a checkpoint change, so reuse rate is observable rather than assumed.
- An end-to-end runtime test over a full `implement → audit → review` pass asserting exactly one
  derivation is shared by both phases and all review lanes at an unchanged checkpoint.
- An end-to-end test over an `audit_gap` loop asserting re-derivation occurs when remediation
  moves the tree and reuse occurs when it does not.
- `ARCHITECTURE.md` documentation of the shared evidence artifact: its checkpoint keying, the
  reference-not-bytes delivery rule, and the audit-floor rule.
- Recording the rejected single-pass merge as a boundary decision, with its reasoning, so it is
  not re-proposed.

Out of scope: new store, assembly, or projection behaviour — subtasks 1–3 own those.

## Acceptance Criteria

1. A versioned contract schema for the shared evidence projection exists under
   `orchestration/contracts/` and is validated when the projection is read.
2. Telemetry records evidence derivations, reuses, and checkpoint-change re-derivations per
   workflow run.
3. An end-to-end `implement → audit → review` test asserts exactly one derivation is shared by
   audit, review, and every review lane at an unchanged checkpoint.
4. An end-to-end `audit_gap` loop test asserts reuse at an unchanged checkpoint and
   re-derivation after remediation moves the tree.
5. `ARCHITECTURE.md` documents the artifact's checkpoint keying, the reference-not-bytes
   delivery rule, and the audit-floor rule.
6. A boundary decision records the rejected audit/review single-pass merge together with its
   reasoning: divergent evidence sets, divergent backward edges, audit-first ordering as the
   cost optimization, and evidence-bar degradation.
7. The full `runtime-kotlin` build, lint, and test suite passes.

## Non-Goals

- Introducing new evidence, store, or projection behaviour.
- Changing phase topology or verdict semantics.
- Cross-run or global evidence caching.

## Dependency Notes

Depends on subtasks 1, 2, and 3.

## Validation Strategy

Schema round-trip and rejection tests for the new contract. Telemetry assertions over the
recorded derivation and reuse counters. The two end-to-end runtime tests named above. Full
`runtime-kotlin` build, lint, and test run as the closing gate.

## Next Path

Feature complete. Reconcile the parent spec to its final state.
