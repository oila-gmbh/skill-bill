# SKILL-223: Boundary decision write hygiene

## Intended Outcome

When an agent records a new entry in `../../../agent/decisions.md`, it also retires
obsolete same-boundary decisions so the titles-only catalog stays useful without
a date window on decisions.

Boundary memory already indexes governed `## [<date>] <title>` headings
programmatically and delivers bodies only for agent-selected ids (SKILL-174).
Verification applies a 30-day recency filter to `history.md` only; `decisions.md`
stays uncapped by date because durable "why" often outlives a month. The missing
hygiene is on the write path: today `bill-boundary-decisions` allows prune when a
decision is fully reversed or no longer applies, but does not require the writer
to scan existing titles and supersede or delete conflicts when appending.

## Acceptance Criteria

1. `../../../skills/bill-boundary-decisions/content.md` requires that, before or while
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
7. After skill source changes, `./install.sh` (or the project's governed install
   path) refreshes installed skill output so agents pick up the new write rules.

## Constraints

- Hygiene is writer-owned in `bill-boundary-decisions`; do not add a model-free
  runtime deleter that guesses obsolescence from dates or keyword overlap.
- Delete only when the writer can name the obsolete same-boundary entry; never
  wipe decisions by age.
- Preserve newest-first ordering and the governed `## [<date>] <title>` heading
  shape required by `BoundaryMemoryHeadingParser`.
- Do not change planning or verification catalog/body-resolve mechanics beyond
  documenting the history-vs-decisions recency asymmetry if a contract comment
  or schema description needs a one-line clarification.

## Non-Goals

- Extending the 30-day history recency filter to `decisions.md`.
- Automatic bulk pruning of existing `decisions.md` files across the repo.
- Changing `bill-boundary-history` write/skip rules or history recency caps.
- New runtime contracts, packet version bumps, or body-resolver API changes.
- Linear / external tracker sync for this feature.

## Validation Strategy

- Diff-read `../../../skills/bill-boundary-decisions/content.md` against the acceptance
  criteria (scan rule, delete-vs-supersede rule, format line, output fields,
  no-date-filter for decisions).
- Confirm verification discovery still filters only history by recency
  (`FileSystemGoalPlanningVerificationDiscoveryTest` or equivalent remains
  green); no new Kotlin required unless a schema description edit needs a parity
  check already covered by existing tests.
- Run `skill-bill validate` after install refresh.

## Delivery Plan

1. Single implementation pass: update the decisions skill (and any one-line
   contract/docs clarification), install, validate.
