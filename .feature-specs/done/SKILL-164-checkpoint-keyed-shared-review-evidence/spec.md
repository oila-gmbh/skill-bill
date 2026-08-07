# SKILL-164: Checkpoint-keyed shared review evidence

Status: Prepared

## Intended Outcome

Derive branch/commit review evidence **once per repository checkpoint** into a durable
on-disk artifact, and deliver it by reference to both the `audit` and `review` phases of the
`bill-feature-task` runtime — replacing today's instruction that each phase, each review lane,
and each loop re-entry re-read the diff for itself.

Today `FeatureTaskRuntimePhaseBriefingAssembler.DERIVED_CONTEXT_INSTRUCTIONS` materializes
nothing. It literally instructs:

- `diff` → *"read the branch diff yourself; it is not delivered in this briefing"*
- `current_unit_of_work` → *"read the current unit of work yourself; it is not delivered in
  this briefing"*
- `scoped_repository_state` → *"read the repository at the resolved checkpoint above …"*

So the same `git` traversal and the same file reads are paid for by `audit`, by `review`, by
**every specialist lane inside review**, and again on every `audit_gap` / `review_fix`
iteration. The duplication *within* review across lanes is larger than the duplication between
audit and review.

The win is not only tokens. Two phases that each independently "read the diff" at different
wall-clock moments can silently observe **different trees**, with nothing in durable state
recording the divergence. Keying the artifact on
`FeatureTaskRuntimeRepositoryCheckpoint.fingerprint` makes that class of silent skew
structurally impossible: same fingerprint reuses the artifact, changed fingerprint re-derives
and is recorded as a re-derivation.

## Background

`FeatureTaskRuntimeRepositoryCheckpoint` (`FeatureTaskRuntimeHandoffProjectionModels.kt:223`)
already carries `fingerprint`, `baseRef`, `headRef`, and `workingTreeOwnedPaths` — the exact
cache key this work needs. `FeatureTaskRuntimeRepositoryCheckpointPolicy`
(`NOT_REQUIRED` / `REFRESH_FROM_REPOSITORY` / `MUST_MATCH`) already models the invalidation
semantics; no new invalidation concept is introduced.

SKILL-158 built a commit-focused evidence model — `ReviewCommitUnit`, `ReviewChangedHunk`,
`ReviewEvidenceTarget`, `ReviewLaneBundleAssembly` in
`runtime-domain/src/main/kotlin/skillbill/review/context/model/` — but scoped it to review
only. This feature hoists that assembly to a phase-neutral derived artifact so audit can
consume the same bytes.

Repo-local `.skill-bill/` is already gitignored via the anchored `/.skill-bill/` entry written
by `InstallApplyRepoLocalConfig`, so the on-disk store requires no new ignore rule.

## Acceptance Criteria

1. A phase-neutral derived evidence artifact is materialized exactly once per
   `FeatureTaskRuntimeRepositoryCheckpoint.fingerprint`; a second consumer requesting evidence
   at an unchanged fingerprint reuses the stored artifact and performs no repository traversal.
2. The artifact is persisted on disk under the repo-local run store, addressed by workflow id
   and checkpoint fingerprint, and survives process restart so a resumed run reuses it rather
   than re-deriving.
3. A checkpoint fingerprint that differs from the stored artifact's fingerprint triggers a
   fresh derivation and records the re-derivation; a stale artifact is never served for a
   fingerprint it was not derived at.
4. Both the `audit` and `review` phases receive the evidence through the existing bounded
   projection mechanism as a **reference** — store path plus file/hunk index — and never as
   inlined diff bytes, so the planning-projection budget is independent of branch diff size.
5. The `audit` phase consumes the shared evidence as an **additional** projection alongside
   `scoped_repository_state`; the shared evidence is a floor for audit and never replaces its
   scoped repository read, because audit's highest-value finding is a criterion with no code
   behind it and therefore no diff.
6. Every review specialist lane consumes the shared artifact rather than independently
   re-deriving the diff, and lane bundle assembly reads from the same stored evidence.
7. `DERIVED_CONTEXT_INSTRUCTIONS` no longer tells a phase to read the diff itself for any key
   backed by the shared artifact; the instruction names the delivered reference instead.
8. Re-entry through the `audit_gap` and `review_fix` backward edges reuses the stored artifact
   when the checkpoint is unchanged and re-derives when the remediation moved the tree.
9. Both review scopes remain correct under the shared artifact: `BRANCH_DIFF` (MEDIUM/LARGE)
   and `CURRENT_UNIT_OF_WORK` (SMALL) each resolve to the evidence their `ceremonyScaling`
   declares.
10. The existing SKILL-158 review behaviour is preserved: commit-focused sparse lane routing,
    synthetic unit handling, and lane bundle identities are unchanged by the hoist.
11. Telemetry records evidence derivations and reuses so the token saving is measurable rather
    than assumed.
12. Documentation in `ARCHITECTURE.md` describes the shared evidence artifact, its checkpoint
    keying, and the audit-floor rule.

## Non-Goals

- **Merging the `audit` and `review` phases into a single pass.** Explicitly rejected and not
  to be re-litigated by any downstream agent. The phases read different evidence sets (audit
  needs unchanged files that produce no diff), they settle into different backward edges
  (`audit_gap` → `implement` with the immutable plan and repair batches, versus `review_fix` →
  `implement_fix` with a `MUST_MATCH` checkpoint), the audit-first entry gate is itself the
  cost optimization (it keeps the expensive specialist fan-out off trees that are about to be
  rewritten), and one agent holding both evidence bars reliably degrades the audit.
- Changing the phase topology, entry gates, or backward edges in
  `FeatureTaskRuntimePhaseWorkflowDefinition`.
- Changing `ceremonyScaling` sizing policy or the SMALL/MEDIUM/LARGE review-scope mapping.
- Changing what audit or review *conclude* — verdict semantics, finding severity, and the
  criterion evidence bar are untouched.
- Sharing evidence across separate workflow runs or caching globally outside the run store.
- Moving builds or tests into audit/review; validation ownership stays with `validate`.

## Constraints

- The artifact is a derived cache, never a source of truth. A missing or unreadable artifact
  must re-derive, not fail the run.
- Never inline diff bytes into a projection. The projection budget must stay bounded
  independent of diff size.
- Loud-fail on a served artifact whose fingerprint does not match the requested checkpoint;
  silently serving stale evidence is the failure this feature exists to prevent.
- No new gitignore entry: the store lives under the already-ignored repo-local `.skill-bill/`.
- Preserve SKILL-158 canonical identities (`commitUnitId`, packet digests, lane bundle
  identities) byte-for-byte where inputs are unchanged.

## Dependencies

SKILL-158 has landed on `main` (merged as `SKILL-158: commit-focused-sparse-specialist-review
(#263)`, spec retired to `.feature-specs/done/`). This work builds directly on the commit
evidence model it introduced, so the dependency is satisfied and SKILL-164 is ready to start.

## Subtasks

1. Checkpoint-keyed evidence store and deriver
2. Phase-neutral evidence assembly hoist
3. Audit and review projection delivery
4. Integration contracts, telemetry, and validation

## Next Path

```bash
skill-bill goal SKILL-164
```
