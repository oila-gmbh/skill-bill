# SKILL-69 - review and feature-implement telemetry health

Created: 2026-06-06
Status: Draft
Issue key: SKILL-69
Mode: decomposed
Parent: follow-up from 2026-06-06 PostHog review-health analysis of
`bill-code-review` standalone telemetry plus `bill-feature-task`
feature-implement telemetry and embedded child-step review telemetry.

## Decomposition

This feature is decomposed because it spans four distinct seams that should
land in dependency order:

1. review telemetry contract and payload hardening;
2. feature-implement lifecycle telemetry and child-step contract hardening;
3. stats/reporting/docs integration across review and feature-implement health;
4. review-rubric noise tuning plus the final validation gate.

Implement on one branch with a commit per subtask:

1. [Review Telemetry Contract and Categories](./spec_subtask_1_review-telemetry-contract-and-categories.md)
2. [Feature-implement Lifecycle Telemetry Health](./spec_subtask_2_feature-implement-lifecycle-telemetry-health.md)
3. [Stats, Remote Query Guidance, and Docs](./spec_subtask_3_stats-remote-query-guidance-and-docs.md)
4. [Review Noise Tuning and Validation Gate](./spec_subtask_4_review-noise-tuning-and-validation-gate.md)

## Sources

PostHog analysis on 2026-06-06 against the `SkillBill` project showed:

Sampling note: use the last 60 days as the measurement window because earlier
telemetry was not stable enough to compare. In practice, the observed
feature-implement finished-event window begins on 2026-04-07, so the 60-day
filter covers the stable sample without including older history.

- Standalone `skillbill_review_finished` events:
  - 603 review runs from 2026-04-04 through 2026-06-05.
  - 1,311 total findings.
  - 2.17 average findings per review; median 2; p90 2.
  - 831 accepted findings.
  - 63.4% aggregate accept/fix rate.
  - 36.5% aggregate reject/decline rate by complement.
  - 1 unresolved finding total; this is expected to be near zero because the
    finished event emits only after the review lifecycle resolves.
- Embedded `bill-code-review` child steps inside
  `skillbill_feature_implement_finished`:
  - 167 review-like payloads.
  - 465 total findings.
  - 3.14 average findings per review; median 0.5; p90 10.
  - 327 accepted findings.
  - 70.3% aggregate accept/fix rate.
  - 0 unresolved findings.
- Combined standalone plus embedded review payloads:
  - 770 review payloads.
  - 751 had numeric finding counts; 19 embedded child-step payloads had missing
    numeric finding counts.
  - 1,776 total findings.
  - 2.36 average findings per review; median 2; p90 2.
  - 65.2% aggregate accept/fix rate.
  - 34.7% aggregate reject/decline rate.
  - 1 unresolved finding total.
- Detailed finding arrays showed a strong value signal for high-severity
  findings:
  - `Major` / `High` / `fix_applied`: 441 detailed findings.
  - `Blocker` / `High` / `fix_applied`: 4 detailed findings.
  - false positives were low in detailed rows.
  - the largest rejection cluster was `Minor` / `Medium` / `fix_rejected`
    with 364 detailed findings.
- Platform and route labels were not normalized. Examples included `kmp`,
  `KMP`, `KMP/Android`, `KMP (Compose Desktop, JVM)`, long prose labels, and
  `readian-kmp-code-review`.
- `readian-kmp-code-review` looked materially different from the baseline:
  22 runs, 6.86 average findings per run, and only 17.9% aggregate accept rate.
- Issue-type breakdown required keyword inference from full-detail finding
  descriptions because telemetry does not carry a first-class category. The
  inferred themes were behavioral correctness, data/persistence correctness,
  concurrency/lifecycle, UX/accessibility, testing/quality gates,
  security/privacy, and docs/contracts.
- Raw feature-implement lifecycle telemetry in the last 60 days showed:
  - 1,174 `skillbill_feature_implement_started` events and 780
    `skillbill_feature_implement_finished` events.
  - 1,173 distinct started sessions and 698 distinct finished sessions.
  - Finished events were polluted by test/synthetic identifiers:
    `install_id = test-install-id` had 464 finished events;
    `session_id = fis-00000000-000000-test` had 73 duplicate completed
    zero-duration finished events; malformed/blank session ids also appeared.
  - Raw duration metrics were not trustworthy: median finished duration was
    1 second while average duration was 34,333 seconds, because zero-duration
    test events and very long wall-clock spans were mixed together.
- A production-like last-60-days slice, excluding known test/malformed session
  ids and `test-install-id`, showed:
  - 220 finished events across 217 distinct finished sessions.
  - 257 distinct started sessions and 217 distinct finished sessions.
  - 3,016 second average duration, 2,144.5 second median duration, and
    5,576.2 second p90 duration.
  - 7.36 average tasks completed, 10.20 average files modified, 5.16 average
    files created, 1.44 average review iterations, and 0.93 average audit
    iterations.
  - Completion statuses: 166 completed (75.5%), 36 abandoned at planning
    (16.4%), 8 error (3.6%), 7 abandoned at review (3.2%), and 3 abandoned at
    implementation (1.4%).
  - Validation results: 174 pass (79.1%), 41 skipped (18.6%), and 5 fail
    (2.3%).
  - Audit results: 169 all_pass (76.8%), 40 skipped (18.2%), and 11 had_gaps
    (5.0%).
  - Completed runs had 121 PRs created (72.9%), 163 boundary-history writes
    (98.2%), 5 rollout-needed flags (3.0%), and 4 feature-flag-used runs
    (2.4%).
  - Large features were the weakest segment: 36 large runs with 36.1%
    completion, compared to 82.5% for medium and 85.4% for small.
- Feature-implement child-step telemetry in the production-like slice showed:
  - all 220 finished events carried `child_steps`;
  - recognizable child steps included 151 `bill-code-review`, 147
    `bill-quality-check`, 125 `bill-pr-description`, and 33 blank-skill steps;
  - only 127 runs had a recognizable review step and 127 had a recognizable
    PR-description step by string search, despite 166 completed runs;
  - quality child steps had 142 pass and 5 fail outcomes.

Relevant current contracts:

- `docs/review-telemetry.md` states that `skillbill_review_finished` carries
  total/accepted/unresolved finding counts, accepted/rejected finding details,
  learnings, routed skill, review platform, normalized review scope type,
  execution mode, specialist reviews, and `review_session_id`.
- `runtime-kotlin/runtime-ports/.../ReviewFinishedTelemetryPayload.kt` currently
  emits `total_findings`, `accepted_findings`, `unresolved_findings`,
  `accepted_rate`, `accepted_finding_details`, and `rejected_finding_details`;
  it does not emit top-level `rejected_findings`, `rejected_rate`,
  `platform_slug`, `scope_type`, or first-class `issue_category`.
- `runtime-kotlin/runtime-application/.../ReviewStatsContractMappers.kt` already
  has richer local stats concepts including `rejected_findings`,
  `rejected_rate`, latest outcome counts, and severity counts, but the remote
  finished-event payload does not expose all of those fields at top level.
- `docs/review-telemetry.md` defines parent-owned telemetry: standalone
  code-review emits `skillbill_review_finished`; orchestrated review work
  returns payloads that parent events embed in `child_steps`.
- `skillbill_feature_implement_started` / `_finished` carry feature-task
  lifecycle metrics including completion status, validation/audit result,
  duration, task/file counts, feature size, issue-key type, feature-flag
  fields, PR creation, boundary-history write status, and `child_steps`.

## Problem

The code-review telemetry is useful enough to support product judgment, but it
has measurement gaps that make operational health harder than it should be.
Feature-implement telemetry has the same class of problem: useful signals are
present, but lifecycle accounting and reporting dimensions are not trustworthy
until test pollution, duplicate finish events, and child-step contract gaps are
handled explicitly.

1. **Reject/decline rate is implicit.** The remote event exposes accepted and
   unresolved counts, but not top-level rejected count/rate. Because
   `unresolved_findings` should be zero at finish time, consumers have to infer
   rejected findings as
   `total_findings - accepted_findings - unresolved_findings`. That is brittle
   and easy to misread as "unresolved" rather than "declined."
2. **Embedded review payloads can be incomplete.** Feature-task child steps
   produced 19 review-like payloads with missing numeric finding counts. That
   means combined review-health reporting can undercount or produce `None`
   buckets.
3. **Platform and scope labels are noisy.** Free-form `review_platform` and
   `review_scope` strings are useful for humans but poor dimensions for
   dashboards, comparisons, and thresholds.
4. **Issue categories are not first class.** The most important product
   question is not just "how many findings were accepted?" but "what kinds of
   defects does review catch?" Today this requires ad hoc keyword inference over
   full-detail descriptions, which is unavailable at anonymous telemetry level
   and unreliable even at full level.
5. **Review tuning lacks a stable measurement loop.** The data suggests
   `Major` / `High` findings are high-value while `Minor` / `Medium` findings
   carry most rejection noise, but the telemetry cannot cleanly show whether a
   prompt/rubric change improves that without also degrading high-value finding
   detection.
6. **Feature-implement health metrics are polluted by test data.** Synthetic
   `install_id` / `session_id` values are present in remote telemetry and
   dominate raw counts. Health reports need a first-class production-like
   filter or a telemetry source marker so test events do not masquerade as real
   workflow outcomes.
7. **Started/finished accounting is ambiguous.** There are more start events
   than finish sessions, duplicate finish events for the same session, and
   malformed session ids. Consumers cannot distinguish active/incomplete runs,
   duplicate terminal emissions, test runs, and real abandonment without custom
   SQL.
8. **Duration semantics are unclear.** Raw duration mixes zero-duration
   synthetic events, normal foreground work, and long wall-clock spans. The
   workflow needs trustworthy elapsed-time fields or reporting rules so median,
   p90, and average duration are meaningful.
9. **Feature-implement child steps are not consistently shaped.** Blank-skill
   child steps and missing recognizable review/PR steps make it hard to measure
   the actual feature-task phase loop from the parent event.
10. **Large-feature failures are visible but under-instrumented.** Large
    features complete at only 36.1% in the cleaned sample, but telemetry does
    not explain enough about why they are abandoned, blocked, or better suited
    for decomposition.

## Goals

1. Make reject/decline metrics explicit in every review telemetry payload:
   `rejected_findings` and `rejected_rate` are first-class numeric fields for
   standalone and orchestrated review payloads.
2. Normalize reporting dimensions while preserving human-readable labels:
   `platform_slug` and `scope_type` are stable machine dimensions derived from
   `review_platform` and `review_scope`.
3. Add a first-class issue-category taxonomy to finding details so anonymous
   telemetry can answer what the reviewer usually catches without shipping
   descriptions.
4. Ensure embedded feature-task review child steps carry the same numeric review
   counts and finding-detail arrays as standalone review-finished payloads when
   telemetry level allows.
5. Add local/remote stats and documentation so maintainers can report review
   health: average findings, accept/reject rate, severity/confidence mix,
   category mix, platform comparison, and standalone-vs-embedded comparison.
6. Use the new measurement fields to make a narrow, validated reduction in
   low-value `Minor` / `Medium` review noise without reducing high-severity
   finding coverage.
7. Add a feature-implement health-reporting contract for the last-60-days
   stable sample: production-like filtering, deduped session accounting,
   completion/abandonment/error rates, duration distribution, validation/audit
   rates, PR/history/flag rates, feature-size segmentation, and child-step
   coverage.
8. Mark or filter test/synthetic telemetry so remote and local stats can exclude
   it by default without hard-coded PostHog query hacks.
9. Enforce feature-implement session and child-step payload completeness:
   valid session id format, one terminal finished event per session, no blank
   child-step skill names, and complete child-step payloads for review,
   quality-check, and PR-description phases when those phases run.
10. Surface large-feature risk clearly enough that maintainers can decide
    whether to decompose, block earlier, or adjust feature-size routing.

## Non-Goals

- Do not build or commit a PostHog dashboard in this repo. Provide queryable
  fields, docs, and local/remote stats support; dashboard creation can happen
  outside the source tree.
- Do not backfill historical PostHog events.
- Do not include full finding descriptions, file paths, or rejection notes at
  anonymous telemetry level; the existing telemetry privacy boundary remains.
- Do not redesign the review triage UX or learnings system.
- Do not change parent-owned telemetry semantics. Standalone reviews still emit
  `skillbill_review_finished`; orchestrated reviews still return payloads for
  parent `child_steps`.
- Do not make broad prompt/rubric rewrites whose impact cannot be measured by
  the new fields. Large specialist-review tuning is a follow-up once category
  telemetry identifies the real noisy areas.
- Do not backfill historical feature-implement PostHog events.
- Do not reinterpret every started-but-not-finished session as a failure. The
  spec should distinguish active, abandoned, terminal, duplicate, and test
  telemetry states.
- Do not redesign the feature-task runtime loop; this pass hardens telemetry
  and reporting semantics around the existing loop.

## Target User Experience

A maintainer can run the existing local stats command and inspect review health
without reconstructing metrics by hand:

```bash
skill-bill stats --format json
```

The output includes explicit reject metrics and grouped dimensions such as:

```json
{
  "total_runs": 770,
  "average_findings": 2.36,
  "accepted_rate": 0.652,
  "rejected_rate": 0.347,
  "severity_counts": {"Blocker": 4, "Major": 467, "Minor": 567},
  "category_counts": {
    "behavior_correctness": 42,
    "data_persistence": 31,
    "concurrency_lifecycle": 24,
    "ux_accessibility": 21,
    "testing_quality_gate": 18,
    "security_privacy": 6,
    "docs_contract": 5,
    "other": 17
  },
  "platforms": {
    "agent-config": {"runs": 519, "accepted_rate": 0.672},
    "kmp": {"runs": 43, "accepted_rate": 0.357}
  },
  "sources": {
    "standalone": {"runs": 603, "accepted_rate": 0.634},
    "embedded_feature_task": {"runs": 167, "accepted_rate": 0.703}
  }
}
```

Remote telemetry consumers see the same dimensions directly in PostHog:

- top-level `rejected_findings`, `rejected_rate`, `platform_slug`, `scope_type`;
- finding detail entries with `issue_category`, `severity`, `confidence`, and
  `outcome_type`;
- embedded `child_steps` review payloads shaped consistently with standalone
  review payloads.

After implementation, a maintainer can answer:

- What is the average number of review findings?
- What percentage of findings are fixed vs declined?
- Which severities and confidence levels are accepted most often?
- Which issue categories are most common?
- Which platforms or routed skills are noisy?
- Are embedded feature-task reviews healthier than standalone reviews?
- For feature-implement runs in the last stable 60-day sample, what percentage
  completed, abandoned, or errored?
- How long do production-like feature-implement runs actually take, excluding
  synthetic/test telemetry?
- Which feature sizes are least healthy?
- How often do completed runs create PRs, write boundary history, run
  validation/audit, and carry review/quality/PR child-step payloads?

## Acceptance Criteria

1. `ReviewFinishedTelemetryPayload` emits top-level
   `rejected_findings` and `rejected_rate` for standalone and orchestrated
   review payloads. The values are derived from terminal latest outcomes and
   are not inferred by remote consumers from accepted/unresolved counts.
2. The review telemetry model emits stable `platform_slug` and `scope_type`
   fields while preserving the current human-readable `review_platform` and
   `review_scope` fields. Normalization covers existing observed values:
   `agent-config`, `kmp`, `kotlin`, `backend-kotlin`, `android`, `unknown`,
   plus an explicit fallback for custom/free-form labels.
3. Every review finding detail can carry `issue_category` at both anonymous and
   full telemetry levels. The initial taxonomy is:
   `behavior_correctness`, `data_persistence`, `concurrency_lifecycle`,
   `ux_accessibility`, `testing_quality_gate`, `security_privacy`,
   `docs_contract`, and `other`.
4. Review import/parsing assigns an `issue_category` deterministically:
   - prefer an explicit category emitted by the review output if present;
   - otherwise derive from the routed specialist/area when possible;
   - otherwise use a conservative local classifier with `other` as fallback.
   The classifier is tested and does not require full-detail telemetry to be
   enabled remotely.
5. Orchestrated review payloads returned to `bill-feature-task` include the
   same required numeric count fields as standalone review payloads:
   `total_findings`, `accepted_findings`, `rejected_findings`,
   `unresolved_findings`, `accepted_rate`, and `rejected_rate`. Missing numeric
   counts in review-like `child_steps` are treated as a contract failure in
   tests, not as acceptable `null` values.
6. Embedded `child_steps` review payloads include accepted/rejected finding
   detail arrays when telemetry level allows, with `issue_category`, `severity`,
   `confidence`, and `outcome_type` present on each detail entry.
7. The local stats surface (`skill-bill stats` and matching MCP stats tool, if
   applicable for the current stats family) reports:
   - average, median, and p90 findings per review;
   - accepted and rejected counts/rates;
   - severity/confidence/outcome breakdown;
   - issue-category breakdown;
   - normalized platform breakdown;
   - standalone vs embedded review-source breakdown when embedded payloads are
     included in the queried dataset.
8. Remote-stats or telemetry documentation includes PostHog-ready query guidance
   for the same health questions, including the distinction between standalone
   `skillbill_review_finished` and embedded `child_steps` review payloads.
9. Review guidance is adjusted narrowly so low-value `Minor` / `Medium` findings
   are de-emphasized unless tied to an explicit contract, user-visible bug,
   regression risk, quality gate failure, or persisted learning. The change must
   not weaken `Blocker` / `Major` finding requirements.
10. Regression tests cover:
    - rejected count/rate computation for all terminal outcome types;
    - zero-finding reviews;
    - one unresolved finding before finish-state handling;
    - platform/scope normalization for observed labels and unknown labels;
    - category assignment from explicit category, routed specialist, and
      fallback classifier;
    - orchestrated child-step payload completeness;
    - stats aggregation over standalone plus embedded review payloads.
11. The review telemetry docs are updated to describe the new fields, category
    taxonomy, privacy-level behavior, and the reject-vs-unresolved distinction.
12. Feature-implement stats and remote query guidance default to a documented
    stable-window and production-like filter:
    - last 60 days unless the caller asks otherwise;
    - exclude telemetry explicitly marked test/synthetic;
    - exclude malformed session ids from health rates while reporting their
      count as data-quality debt.
13. Feature-implement telemetry emits or persists a source marker sufficient to
    identify test/synthetic events without relying on hard-coded values such as
    `test-install-id` or `fis-00000000-000000-test`.
14. Feature-implement lifecycle accounting dedupes by `session_id` for health
    reporting and flags duplicate terminal finished events. A session has at
    most one authoritative terminal finished event in stats output.
15. Feature-implement duration reporting is split into trustworthy fields or
    clearly named metrics so stats can distinguish actual run duration from
    synthetic zero-duration events and long wall-clock spans. Tests cover
    zero-duration test events, normal runs, and long-running/resumed runs.
16. Feature-implement child steps have a complete contract:
    - every child step has a non-blank `skill`;
    - known child skills use canonical names;
    - review child steps include the SKILL-69 review payload fields;
    - quality-check child steps include result and iteration/failure fields;
    - PR-description child steps include PR-created state and commit/count
      fields where available.
17. Feature-implement stats report the cleaned-sample metrics needed for health
    judgment: started sessions, finished sessions, duplicate terminal count,
    completion/abandonment/error rates, duration average/median/p90, validation
    and audit rates, PR-created rate, boundary-history-write rate,
    feature-flag/rollout rates, feature-size breakdown, and child-step coverage.
18. Feature-size segmentation highlights large-feature risk. Large features
    with high abandonment/error rates are reported separately and docs recommend
    decomposition or earlier blocking when large-feature acceptance criteria or
    plan size exceed the observed healthy envelope.
19. Regression tests cover:
    - exclusion/marking of test telemetry;
    - malformed and duplicate session ids;
    - deduped terminal event selection;
    - started-without-finished accounting without treating every open session as
      failed;
    - duration aggregation over normal, test, and resumed/long-running runs;
    - child-step canonical names and required fields;
    - large/small/medium feature-size breakdown.
20. Maintainer validation passes:
    - `skill-bill validate`
    - `(cd runtime-kotlin && ./gradlew check)`
    - `npx --yes agnix --strict .`
    - `scripts/validate_agent_configs`

## Design Notes

- **Reject is terminal, unresolved is not.** `unresolved_findings` is a lifecycle
  health field, not a decline metric. Keep it, but add explicit rejected
  metrics so dashboards do not encode contract knowledge in every query.
- **Normalize without deleting raw labels.** Raw labels are useful for
  debugging. Stable slugs are useful for reporting. Emit both.
- **Category belongs on findings, not only runs.** A review can catch multiple
  issue types. Put `issue_category` on each finding detail and aggregate from
  there.
- **Anonymous telemetry must still be useful.** Category, severity, confidence,
  outcome type, and finding id are safe at anonymous level; descriptions,
  locations, notes, and learning content remain full-only.
- **Do not overfit PostHog.** The implementation should improve the local
  telemetry contract and stats model. PostHog becomes easier to query because
  the payload is better, not because source code depends on PostHog concepts.
- **Tune after instrumentation, but make one narrow pass now.** The current data
  already shows `Minor` / `Medium` rejections are the largest noise cluster.
  Narrowing that class is safe if the change explicitly preserves high-severity
  review obligations and tests/docs make the rubric visible.
- **Stable sample by default.** The product judgment should use the last 60
  days because earlier telemetry was not stable. Stats may support arbitrary
  ranges, but docs and default health views should call out the stable-window
  default.
- **Health reporting must separate product behavior from telemetry quality.**
  A started/finished mismatch can mean an active run, a missing terminal event,
  a duplicate terminal event, a test event, or a real abandonment. Report these
  buckets separately rather than collapsing them into one failure rate.
- **Child steps are the feature-task phase ledger.** Blank skill names and
  partial child payloads make the parent event hard to trust. Treat the child
  step contract like a telemetry schema, with tests at the parent aggregation
  seam.

## Validation Strategy

- Unit tests for telemetry payload mapping in `runtime-ports` and stats payload
  mapping in `runtime-application`.
- Import/triage tests that seed accepted, rejected, false-positive, edited, and
  unresolved findings and assert exact count/rate/category output.
- Feature-task orchestration tests or MCP golden tests proving review child-step
  payloads include the complete numeric count contract.
- Stats CLI/MCP tests with mixed standalone and embedded review payloads.
- Feature-implement stats tests with mixed production/test telemetry, duplicate
  terminal events, malformed session ids, open started sessions, completed
  sessions, abandoned sessions, and error sessions.
- Child-step contract tests for feature-implement finished payloads, including
  canonical review, quality-check, and PR-description child steps.
- Documentation validation through `skill-bill validate` and
  `scripts/validate_agent_configs`.
- Full Gradle check and repo validation commands from AC20.

## Open Questions

- Should `finding_edited` count as accepted, rejected, or a third terminal
  bucket for remote review-health reporting? Leaning: keep it terminal and
  explicit in `latest_outcome_counts`, but exclude it from both accepted and
  rejected unless the current local stats contract already treats it as
  accepted.
- Should `platform_slug` use only manifest-declared platform pack slugs, with
  custom labels mapped to `custom`, or should it preserve sanitized custom
  slugs? Leaning: manifest-known slugs plus `custom` to keep dashboards stable.
- Should category be manually emitted by specialist prompts in the finding line
  format, or derived entirely by runtime import? Leaning: allow explicit
  category in the finding format for future precision, but keep runtime fallback
  mandatory so older review outputs still classify.

Run bill-feature-task on .feature-specs/SKILL-69-code-review-telemetry-health/spec.md
