---
status: Complete
---

# SKILL-104 Subtask 3 - Docs, surface reconciliation, verification, records

Parent spec: [spec.md](spec.md)
Issue key: SKILL-104

Read the parent spec's **Pinned Decisions** before starting. PD3, PD5, PD7, and PD8 shape the documentation; parent criteria 11–14 are owned here.

## Scope

Document the pack extension of the internal-skill contract, reconcile every user-facing surface, capture end-to-end verification evidence, and write the records.

### Touch points (exact files)

- `docs/skill-source-generation.md` (`## Internal Skills`) — extend the normative contract: pack skills may declare `internal-for`; parent must still be a listed base skill; selection-aware sidecar rendering and hash folding (PD3); the PD8 baseline co-presence loud-fail; the flatten rule for multi-level families (PD2 rationale in one paragraph).
- `docs/internal-skills-architecture.md` — add the review family as a second worked example: installed layout diagram of `bill-code-review/` with 34 sidecars, the routing walkthrough (routing signals → routed entry sidecar → specialist sidecars → native agents), and the selection-shaped variance.
- `AGENTS.md` — update the internal-skills summary if the pack extension changes its claims; stay under the agnix 12k limit (SKILL-102 hit this — keep the summary thin and push detail to docs).
- `README.md` — reconcile the catalog and any entry-point prose: the 34 are not directly invocable; `/bill-code-review` is the code-review entry point. Verify against how the README actually presents packs today (spec preparation found no per-skill pack rows — reconcile whichever way it reads).
- `docs/getting-started.md`, `docs/getting-started-for-teams.md` — re-point any direct references to stack review skills at `/bill-code-review`.
- E2e verification (parent criterion 12): from-source scratch install (0.7.x-SNAPSHOT runtime) capturing CLI evidence for both selection shapes — all packs (34 sidecars, no standalone dirs/links) and Kotlin-only (exactly 9 sidecars); PD8 negative (KMP w/o Kotlin fails with the typed error) and positive plans; an interactive `/bill-code-review` routed review on Claude Code exercising specialist spawn + rubric sidecar reads. Record honestly anything that cannot be driven from the implementing session as deferred, with what remains outstanding.
- `runtime-kotlin/agent/decisions.md` — boundary decision: flatten-vs-nest, selection-aware hashing, PD8 loud-fail over auto-include.
- `runtime-kotlin/agent/history.md` — feature history entry per the history-value rubric.
- Spec reconciliation: parent + all subtask specs to final status, open question resolved (file the quality-check follow-up issue if the maintainer agrees), Completion Corrections folded in, decomposition manifest statuses complete.

### Out of scope for this subtask

- Any mechanism or pack-content change beyond defect fixes found during verification. If verification exposes a mechanism defect, fix it in the smallest mechanism-adjacent way (SKILL-102 precedent: the README-validation defect) and record it under Completion Corrections — do not invent new mechanism.

## Acceptance Criteria

1. `docs/skill-source-generation.md` normatively covers pack internal skills: relaxed rule, unchanged parent rules, selection-aware sidecars and hashing, PD8 guard, flatten rule.
2. `docs/internal-skills-architecture.md` documents the review family end-to-end with the installed-layout and routing walkthrough.
3. README, getting-started docs, and AGENTS.md present only `bill-code-review` (plus `bill-code-review-parallel`, `bill-code-check`) as code-review entry points; no user-facing surface advertises the 34 as invocable; AGENTS.md stays within the agnix limit.
4. E2e evidence captured for: all-packs layout, Kotlin-only layout, PD8 negative and positive plans, and an interactive routed review on Claude Code — or each honestly recorded as deferred with the outstanding check named.
5. Boundary decision and history entries recorded.
6. Parent spec, subtask specs, and decomposition manifest reconciled to final state, including Completion Corrections and the resolved open question.
7. Maintainer validation passes: `./gradlew check`, `skill-bill validate`, `agnix --strict`, `scripts/validate_agent_configs`.

## Non-Goals

New mechanism; quality-check family migration (file the follow-up instead, PD7).

## Dependency Notes

Depends on subtask 2 (documents and verifies the final shape).

## Validation Strategy

The e2e evidence in criterion 4 is the validation; run the maintainer validation set last, after all doc edits (agnix limit check matters here).

## Next Path

Feature complete — reconcile and close per criterion 6.
