---
status: Complete
---

# SKILL-69 Subtask 3 - Stats, Remote Query Guidance, and Docs

Parent spec: [.feature-specs/SKILL-69-code-review-telemetry-health/spec.md](./spec.md)
Issue key: SKILL-69

## Scope

Expose the hardened review and feature-implement telemetry as useful health
reporting. This subtask owns local stats output, matching MCP stats behavior
where applicable, and documentation for PostHog/remote queries.

- Extend local stats surfaces to report review health:
  average/median/p90 findings, accepted/rejected counts and rates,
  severity/confidence/outcome breakdown, issue-category breakdown, normalized
  platform breakdown, and standalone-vs-embedded review-source breakdown.
- Extend feature-implement stats to report the stable last-60-days
  production-like health view:
  started sessions, finished sessions, duplicate terminal count, malformed
  session count, completion/abandonment/error rates, duration average/median/p90,
  validation/audit rates, PR-created rate, boundary-history rate,
  feature-flag/rollout rates, feature-size breakdown, and child-step coverage.
- Default health-oriented docs and examples to the last 60 days because earlier
  telemetry was unstable; still allow explicit caller-selected ranges when the
  existing stats surface supports them.
- Document the distinction between standalone `skillbill_review_finished`
  events and review payloads embedded in `skillbill_feature_implement_finished`
  `child_steps`.
- Update `docs/review-telemetry.md` and related telemetry docs with the new
  fields, category taxonomy, privacy-level behavior, reject-vs-unresolved
  distinction, production-like filtering, and large-feature risk reporting.

## Acceptance Criteria

1. Review stats report all subtask 1 health fields and correctly aggregate mixed
   standalone plus embedded review payloads.
2. Feature-implement stats report deduped health metrics over the subtask 2
   source classification/session contract.
3. Health views exclude test/synthetic telemetry by default and report excluded
   or malformed counts as data-quality debt.
4. Feature-size segmentation reports large-feature completion/abandonment/error
   separately and recommends decomposition or earlier blocking for unhealthy
   large-feature envelopes.
5. Documentation includes PostHog-ready query guidance for review and
   feature-implement health without requiring copied ad hoc SQL from the
   exploratory analysis.
6. Empty-store and no-matching-range behavior remains clear and consistent with
   existing stats commands.
7. CLI/MCP tests cover the new stats fields and JSON shape.

## Non-Goals

- Do not create a PostHog dashboard in source.
- Do not change the telemetry payload contracts owned by subtasks 1 and 2 except
  for integration fixes discovered while reporting.
- Do not tune review guidance; subtask 4 owns that.

## Dependency Notes

Depends on subtasks 1 and 2. It consumes their payload and session contracts and
must not reimplement alternate inference rules in the reporting layer.

## Validation Strategy

- Stats model tests over fixture datasets combining standalone review events,
  embedded review child steps, feature-implement normal/test/duplicate records,
  and large/small/medium feature sizes.
- CLI JSON output tests and MCP golden tests where stats tools expose these
  fields.
- Documentation validation through `skill-bill validate` and
  `scripts/validate_agent_configs`.

## Next Path

Subtask 4 applies the narrow review noise-reduction guidance and runs the final
validation gate over the decomposed feature.

## Spec Path

.feature-specs/SKILL-69-code-review-telemetry-health/spec_subtask_3_stats-remote-query-guidance-and-docs.md
