---
issue_key: SKILL-136
subtask_id: 4
name: Controlled vocabularies for review-run attribution
parent_spec: .feature-specs/SKILL-136-android-native-review-specialists/spec.md
---

# Subtask 4 — Controlled vocabularies for review-run attribution

## Intended Outcome

`routed_skill`, `detected_stack`, and `detected_scope` on `review_runs` each
carry a canonical identifier resolved at ingestion, alongside the preserved raw
agent-authored text, so routing and stack analysis is possible without
hand-normalizing.

## Scope

- Review-telemetry ingestion: resolve a canonical value for `routed_skill`
  against known pack skill names (reusing
  `reviewAttributionPort.routedSkillPlatformSlugs()` in `ReviewService.kt`),
  for `detected_stack` against known platform slugs, and for `detected_scope`
  against a controlled vocabulary (working tree, staged, commit range, pull
  request, other) with free-form detail retained in a separate field.
- Schema migrations adding the canonical columns and the scope-detail field.
- Backfill of existing rows where the canonical value is unambiguous.
- Record `execution_mode` for runs that currently omit it.

## Acceptance Criteria

1. `routed_skill`, `detected_stack`, and `detected_scope` each record a
   canonical identifier alongside the preserved raw text.
2. Canonical resolution happens at ingestion, against known pack skill names,
   known platform slugs, and the controlled scope vocabulary (working tree,
   staged, commit range, pull request, other), with free-form scope detail
   retained in its own field.
3. An unresolvable value is retained and explicitly marked unresolved rather
   than silently bucketed into a default.
4. Existing rows are backfilled where the canonical value is unambiguous:
   grouping the recorded runs by canonical routed skill yields one row per pack
   instead of 24 variants, and by canonical stack yields one row per stack
   instead of 57.
5. `execution_mode` is recorded for runs that currently omit it.
6. All schema changes ship as migrations that preserve existing rows, verified
   against a copy of a real ~91.5 MB store.
7. Resolution failures surface as typed errors with parity coverage; no silent
   fallback.
8. `severity`, `confidence`, `disposition`, and `event_type` are unchanged.
9. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Populating `specialist_reviews` or per-lane finding attribution (Subtask 5).
- Outbox, `learnings`, cross-store keying, or retention work (Subtask 6).
- Changing any platform pack.
- Deleting or rewriting the preserved raw text.

## Dependencies

None. Independent of Subtasks 1–3.

## Validation Strategy

Migration tests over a copy of a real store asserting row preservation and
post-backfill cardinality; ingestion tests covering resolvable and
unresolvable values for each of the three columns. Then:

```bash
(cd runtime-kotlin && ./gradlew check)
skill-bill validate
```

## Next Path

Continue the goal; Subtasks 5 and 6 both build on these canonical fields.
