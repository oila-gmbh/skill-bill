# SKILL-236 Subtask 3 - Review execution and generated-evidence ownership recovery

Parent spec: [spec.md](./spec.md)
Issue key: SKILL-236

## Scope

Own parent criteria 8–9 and the execution portions of 7 and 10. Reproduce verification admission/process failures and legitimate evidence-read friction; fix supported recovery without weakening the broker. Correct runtime-generated evidence ownership and preflight integration. Diagnose before policy changes and explicitly hand SKILL-235-owned publication/census/PR receipt defects to that existing spec.

## Acceptance Criteria

1. At least the reproducible verification process/admission failure classes supported by evidence are covered at the actual boundary: supported recovery succeeds or a typed actionable non-success preserves findings. Empty/unparseable output never becomes verification or NO_FINDINGS.
2. An unchanged, authorized evidence item needed again can be consumed using governed cache/retrieval semantics without a refusal loop. Stale-checkpoint, unassigned, cross-run and scope-expansion reads still reject. Any required contract adjustment is explicit, versioned and tested.
3. Runtime-produced .skill-bill/run-evidence artifacts do not alone trigger outside-owned-inventory blocks. Ownership derives from active-run provenance and checkpoint, never path prefix alone; another run artifact or forged file under the same directory receives no exemption.
4. Preflight/resume handles runtime-owned ignored/deleted evidence and restores the preexisting index after failed finalization. Unrelated staged files, another issue spec and unowned modifications remain protected and actionable; no automatic force-add, broad discard or unstaging is permitted.
5. Failure telemetry distinguishes verification execution/publication, evidence policy refusal and genuine finding disposition using subtask 2 semantics when integrated; diagnostic failure cannot fabricate clearance.
6. Record reproduction and ownership disposition for missing-terminal/PR-failure observations. Reuse SKILL-235 decision/receipt reconciliation where landed; do not create a second finalization authority or claim to repair unreproduced historical failures.

## Non-Goals

Prose/repair-receipt/disposition redesign owned by SKILL-235, arbitrary rereads, scope expansion, changing gate selection, automatically altering unrelated Git state or bypassing genuine findings.

## Dependency Notes

No intrinsic feature-local dependency; execute in manifest order and preserve earlier increments. Apply all parent constraints. Reconcile current SKILL-233/234/235 ownership before touching overlapping seams; existing fixes count only when their behavioral evidence satisfies this scope. Do not overwrite concurrent work.

## Validation Strategy

Use a real broker boundary with allowed repeat consumption and stale/unassigned/cross-run rejection; provider/verification return fixtures; temporary-repository index/ownership fixtures with generated evidence, forged same-directory evidence, another workflow, ignored/deleted files and staged unrelated work. Assert no false clearance, no unrelated index/worktree mutation, and actionable error identity. Aggregate refusal counts alone are not a regression oracle.

Follow the parent routed-gate constraints and test-value discipline. Include production integration, contracts, migration and documentation in this subtask commit; do not run a broader substitute build gate.

## Next Path

Complete SKILL-236 through the goal runtime; runtime owns final commit/push and goal finalization.

## Spec Path

.feature-specs/SKILL-236-telemetry-truth-and-runtime-reliability/spec_subtask_3_review-and-evidence-recovery.md
