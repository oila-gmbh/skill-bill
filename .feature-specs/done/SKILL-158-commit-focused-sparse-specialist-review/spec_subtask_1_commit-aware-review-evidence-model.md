# SKILL-158 Subtask 1 - Commit-Aware Review Evidence Model

Parent spec: [.feature-specs/SKILL-158-commit-focused-sparse-specialist-review/spec.md](spec.md)
Issue key: SKILL-158

## Scope

Introduce the authoritative ordered commit and incremental-diff model used by
delegated review preparation. Preserve the existing bounded hunk evidence
surface while adding enough identity and ordering to distinguish commit-local
changes from the final accumulated tree.

In scope:

- Resolve ordered commits for PR diff and explicit commit-range scopes, with
  stable SHA, parent SHA, subject, and position metadata.
- Parse each commit's incremental diff into attributable changed hunks and
  preserve a final base-to-head equivalence fact for the review packet.
- Extend review context, assignment, wire serialization, digest, and validation
  models with commit identity/order and per-commit hunk ownership.
- Model a per-lane bundle as an ordered set of assigned hunk bodies carrying
  commit identity, order, and per-hunk attribution as readable metadata.
- Represent staged, unstaged, combined working-tree, and file scopes as one
  synthetic review unit without fabricating commit history.
- Keep Git discovery parent-owned. Workers receive projected commit units and
  may not recompute scope, commit order, or broad diffs.

## Acceptance Criteria

1. A PR or commit-range review packet contains the ordered commit sequence,
   each commit's parent/subject metadata, and the exact incremental hunk set
   attributable to that commit.
2. Commit and hunk identities are deterministic and included in packet and
   assignment digests; changing commit order, parent, or hunk content changes
   the relevant digest.
3. Packet validation rejects missing, duplicate, out-of-order, or unowned
   commit/hunk references and rejects an assignment that claims a commit unit
   outside its packet.
4. The packet exposes an aggregate base-to-head equivalence fact or validation
   result proving that the ordered commit units cover the authoritative review
   delta without silent omission or duplication.
5. Non-commit scopes produce exactly one synthetic review unit with explicit
   source metadata and retain their existing staged/unstaged boundaries.
6. Launch serialization projects only the assigned ordered commit units and
   their hunk bodies; complete-diff artifacts are not part of the worker
   projection.
7. Focused tests cover new files, renames, deletions, merge-base/parent
   handling, empty commits where the scope permits them, and malformed Git
   records.
8. A per-lane bundle carries its assigned hunk bodies with commit identity and
   order attached, so a consumer can attribute any hunk to its commit without
   further Git access. Validation rejects a bundle referencing a hunk or commit
   absent from the packet, and bundle composition contributes to the assignment
   digest.

## Non-Goals

- Commit-to-lane routing; Subtask 2 owns relevance and sparse assignment.
- Worker prompt behavior and single-pass bundle execution; Subtask 3 owns those
  concerns.
- Changing Git history, merge strategy, or the existing diff source semantics.

## Dependency Notes

Depends on: none

This unit establishes the typed evidence and serialization contract consumed by
all later routing and execution work.

## Validation Strategy

Add model, schema, digest, parser, and wire round-trip tests using deterministic
multi-commit fixtures and synthetic non-commit fixtures. Verify aggregate
coverage, ordering, renames, deletions, and strict rejection cases. Run
`(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 2 - sparse commit-to-lane routing and relevance decisions.

## Spec Path

.feature-specs/SKILL-158-commit-focused-sparse-specialist-review/spec_subtask_1_commit-aware-review-evidence-model.md
