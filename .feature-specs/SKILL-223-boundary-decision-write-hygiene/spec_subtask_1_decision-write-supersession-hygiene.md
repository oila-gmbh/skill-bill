# SKILL-223 · Subtask 1 — Decision write supersession hygiene

## Scope

Harden the write path for boundary decisions so obsolete same-boundary entries
are retired when a new decision lands, without putting a date window on
`decisions.md`.

Edit:

- `skills/bill-boundary-decisions/content.md` (authoritative skill source):
  - On write, read existing governed `## [<date>] <title>` headings in the target
    `<boundary>/agent/decisions.md`.
  - Classify conflicts with the new decision.
  - Fully obsolete and no remaining useful "why" → delete the old entry.
  - Obsolete but still useful as trap / rejected-path context → keep and add
    `Superseded by: <new title> (<date>)`.
  - Document `Superseded by:` in the optional trailing lines of the entry format.
  - Output must report written titles plus pruned and/or superseded titles.
  - Explicit non-rule: do not prune decisions by age; do not apply
    `history_recency_days` thinking to decisions.
- Optional one-line clarification only where needed so operators and future
  editors do not extend verification's history recency to decisions, for
  example a description on `history_recency_days` in
  `orchestration/contracts/goal-verification-boundary-caps-schema.yaml` and/or a
  short note in `docs/capabilities.md` next to the boundary-memory paragraph.
  Do not change cap values or discovery code unless a comment-only edit is
  insufficient and a test already pins the asymmetry.

Run `./install.sh` after skill source edits so installed skill output matches
source. Leave planning catalog, body resolver, and history recency behavior
unchanged.

## Acceptance Criteria

1. `skills/bill-boundary-decisions/content.md` requires that, before or while
   appending a new decision, the writer reads the target boundary's existing
   governed decision headings and classifies any conflict with the new decision.
2. The skill states the supersession rule: if the new decision fully replaces an
   older one and the old "why" adds nothing useful, delete the old entry; if the
   old rationale still explains a trap or rejected path, keep it and add a
   `Superseded by:` line naming the new decision (title and date).
3. The skill entry format documents `Superseded by:` as an optional trailing
   line, alongside `Alternatives considered:` and `Revisit when:`.
4. The skill output reports pruned and/or superseded entries (count and titles),
   not only newly written titles.
5. Skill or adjacent contract/docs text states that `history_recency_days` applies
   only to `history.md` discovery and must not be extended to `decisions.md`;
   catalog caps and agent selection remain the bound for decisions.
6. No runtime change that auto-deletes decisions by age, or that applies the
   history recency window to decision kind entries; the existing verification
   test that decisions are not filtered by history recency remains green.
7. After skill source changes, `./install.sh` refreshes installed skill output so
   agents pick up the new write rules.

## Non-Goals

- Extending the 30-day history recency filter to `decisions.md`.
- Bulk pruning existing decisions across the repository in this subtask.
- Changes to `bill-boundary-history`, planning packet shape, or body-resolver APIs.
- Runtime code that deletes decisions without the writer naming them.

## Dependency Notes

- None. Single subtask; base branch is `main`.

## Validation Strategy

- Diff-read the skill (and any docs/schema description edits) against the
  acceptance criteria.
- Keep
  `FileSystemGoalPlanningVerificationDiscoveryTest` (decisions not filtered by
  history recency) green; do not weaken that assertion.
- `./install.sh` then `skill-bill validate`.

## Next Path

`skill-bill goal SKILL-223`
