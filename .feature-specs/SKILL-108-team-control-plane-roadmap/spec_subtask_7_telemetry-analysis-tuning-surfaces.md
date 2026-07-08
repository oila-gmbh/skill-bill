# SKILL-108 Subtask 7: Telemetry Analysis and Tuning Surfaces

## Scope

Build the Phase 3 measurement loop that lets admins identify weak workflows,
tune governed source, publish a new bundle, and compare outcomes against the
previous version.

Surfaces may be CLI first, then desktop if the runtime service is ready. They
should summarize:

- usage by skill, platform pack, team, channel, and bundle version
- review finding accepted/rejected rates by routed skill and bundle version
- quality-check pass/fail loops and iteration counts
- feature-task completion, abandonment, retry, duration, review-fix loops, and
  audit-gap loops
- before/after comparison across two bundle versions or channels
- data-quality warnings for malformed, excluded, or missing telemetry

The analysis must use the same definitions as local stats and remote stats. Do
not invent a second analytics vocabulary.

## Acceptance Criteria

1. `skill-bill telemetry stats` or a team-specific stats command can compare
   two bundle versions or channels for feature-task, feature-verify,
   code-review, and code-check outcomes where data exists.
2. Stats can be grouped by routed skill, platform pack, team, channel, bundle
   version, day, and week.
3. Review accepted/rejected rates and quality-check pass/fail loops use the
   same definitions as existing local and remote stats contracts.
4. Feature-task metrics include completed, in-progress estimate, abandoned,
   error, retry/review-fix/audit-gap iterations, duration, and boundary-history
   usefulness where present.
5. Desktop or CLI admin output highlights one or more weak workflow candidates
   without automatically editing skills or packs.
6. Output clearly labels privacy tier, source counts, malformed/excluded
   records, and whether data came from local SQLite or a remote proxy.
7. Tests cover before/after comparison math, empty data, malformed payloads,
   proxy unsupported capabilities, privacy labels, and grouping by bundle
   version/channel.
8. Docs add a "tune from evidence" workflow that starts from stats, edits
   governed source, validates, publishes a new bundle, and compares results.

## Non-goals

- No automatic skill rewrites.
- No ML-driven recommendation engine.
- No hosted dashboard UI unless hosted contracts from subtask 8 have landed.
- No collection of code, prompts, file contents, or prose at anonymous level.

## Dependency Notes

Depends on subtask 6. It consumes team/bundle telemetry attribution and remote
stats grouping.

## Validation Strategy

Run stats and telemetry tests plus:

```bash
(cd runtime-kotlin && ./gradlew :runtime-application:test :runtime-infra-sqlite:test :runtime-cli:test)
npx --yes agnix --strict .
```

Manual smoke: seed or collect two bundle-version event sets, compare them via
CLI, and verify the output identifies outcome differences without exposing
prose content at anonymous level.

## Next Path

After this lands, run subtask 8 to define hosted org, registry, membership, and
audit contracts.
