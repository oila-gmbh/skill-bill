# SKILL-197 — Own the KMP-uncovered review areas with Android-appropriate content

## Context

SKILL-196 stops the fallback `generic` pack from planning lanes beside a native pack. That is the
correct routing rule, and it exposes a content gap that the fallback lane had been masking.

`kmp` declares five of the ten review areas — `platform-correctness`, `persistence`, `reliability`,
`ui`, `ux-accessibility`. The remaining five — **`architecture`, `performance`, `security`,
`testing`, `api-contracts`** — are not declared by `kmp`, so under composition they fall to `kotlin`
(`kmp/platform.yaml`, `code_review_composition.baseline_layers`). Rubric content is resolved from the
owning pack only and is not composed across the chain
(`FileSystemReviewRubricResolver.kt:20-50`), so on an Android/KMP diff those five areas are reviewed
entirely by `kotlin` content.

### The `kotlin` pack targets backend and desktop JVM, not Android

`kotlin` area content is written against server-side and desktop Kotlin. Its `area_metadata` focus
lines say so directly: persistence is *"Exposed `newSuspendedTransaction`, Spring proxy transactions,
Hibernate sessions"*; `ui` is *"Compose Desktop, Swing or JavaFX thread interop, server rendering,
CLI or TUI"*; `ux-accessibility` is *"desktop semantics and focus, terminal accessibility"*.

`kmp` shields four of those by declaring the areas itself. It does not shield the other five.

Measured skew across the five uncovered areas, counting rule lines that reference a technology
absent from an Android runtime (Exposed, Spring, Hibernate, JDBC, R2DBC, `@Transactional`,
`DataSource`, servlet request scope, Jackson, Ktor, outbox, publication/ABI):

| Area | Rules referencing backend-only technology |
|---|---|
| architecture | 7 of 15 |
| security | 4 of 16 |
| performance | 3 of 15 |
| api-contracts | 3 of 16 |
| testing | 3 of 19 |

### The observed run corroborates the skew, and correlates with it

On `rvw-20260817-183143-ilvx` (capmo-android, a five-commit PR decoding a payload and writing Room
rows), `bill-generic-code-review-architecture` produced **2** findings while
`bill-kotlin-code-review-architecture` produced **1**. The most-skewed area was the one where the
technology-neutral fallback outperformed the native pack.

`bill-kotlin-code-review-testing` — the least-skewed area at 3 of 19 — was the run's **most**
productive lane at 6 findings. `runTest` virtual time, dispatcher ordering, and Flow sequence
assertions transfer to Android intact.

The skew is therefore uneven rather than pack-wide. `architecture` is evidenced; the other four are
measured but not yet demonstrated to under-perform.

### The completed run raised this from measured skew to a confirmed miss

`rvw-20260817-183143-ilvx` finished with 18 findings, 12 adjudicated `confirmed`. Two of them came
from `bill-generic-code-review-architecture`, and they were the sharpest architectural findings in
the run:

- **F-001 — Major, High confidence, `confirmed`.** A schema pull added `OF_workerEntryV2.description`;
  only the new `DailyReportSuggestionResultV2WithVisitsSubscription` document selected it, while the
  pre-existing `DailyReportSuggestionResultV2Subscription` was not updated. Document choice is made
  by a visits-scoped predicate unrelated to attendance, so the same backend payload yields a
  populated worker description on VISIT_STRUCTURED projects and a silent null — persisted into
  `WorkerEntry.description` — on legacy VISITS projects.
- **F-015 — Minor, Medium.** Names the byte-for-byte fragment clone that let F-001 go unnoticed, and
  the absence of any parity assertion over the shared selection set.

`bill-kotlin-code-review-architecture` produced one finding and caught neither. Cross-document
selection-set divergence is a boundary-authority failure — a duplicated source of truth for one
payload — which the technology-neutral rubric asks about directly (`boundary-contract-map` authority
per write, duplicate sources of truth) and the backend-targeted rubric does not.

This makes SKILL-197 blocking rather than merely desirable: **SKILL-196 AC 4 must not ship before
this spec lands**, because doing so would remove the only lane that caught a confirmed Major on the
run that motivated both specs.

### Why this is not an argument for keeping the generic lane

Per SKILL-196, `generic` is a fallback for an area **no native pack declares**, not a second opinion
on a covered area. The repair for a native rubric that asks the wrong questions is to fix that
rubric's ownership, not to run a second lane beside it. This spec is that repair.

## Intended Outcome

Every area planned for an Android/KMP review is reviewed against content targeting the Android/KMP
runtime. No Android review is asked whether an Exposed transaction runner owns a use case, whether a
`@Singleton` captures a request-scoped principal, or whether an `api(project(...))` edge threatens a
published ABI.

Backend and desktop Kotlin reviews are unaffected: the `kotlin` pack keeps its content and its
consumers see no change.

## Acceptance Criteria

1. `kmp` declares `architecture` in `declared_code_review_areas`, with a declared area content file
   and a `lane_conditions` entry, and the manifest passes the platform-pack schema and loud-fail
   contract checks.
2. On an Android/KMP diff that routes `kmp`, `review_run_lanes` contains exactly one `architecture`
   row and its `pack_slug` is `kmp`.
3. The new `kmp` architecture content contains no rule whose only trigger is a technology absent from
   an Android/KMP runtime — specifically none of Exposed, Spring, Hibernate, JDBC, R2DBC,
   `@Transactional`, `DataSource`, servlet request scope, or published-ABI/`api(project(...))`
   reasoning.
4. The new `kmp` architecture content covers at minimum: Gradle module boundary and dependency
   direction for Android modules; DI graph and scope ownership; `ViewModel` and lifecycle ownership
   of long-lived work; the boundary authority between repository, use case, and sync engine; worker
   and background-task ownership; and single-source-of-truth for offline writes.
5. The new `kmp` architecture content carries a rule reaching the F-001 class: two documents,
   fragments, or model trees describing one payload, where a field added to one selection set is not
   added to the other, so the same response decodes differently depending on which document a
   runtime predicate selected. Replaying the WE-4689 diff against the new content surfaces the
   divergence between `DailyReportSuggestionResultV2Subscription` and
   `DailyReportSuggestionResultV2WithVisitsSubscription`.
6. SKILL-196 AC 4 does not ship before this spec. Either both land together, or this lands first;
   no state exists in which generic lanes are excluded and the `kmp` architecture content is absent.
7. Each of `performance`, `security`, `testing`, and `api-contracts` receives a recorded disposition:
   either a `kmp` declaration with Android-appropriate content, or an explicit justification that the
   `kotlin` content is reachable and sufficient on an Android/KMP diff. No area is left undecided.
8. The `kotlin` pack's `declared_code_review_areas`, area content, `area_metadata`, and
   `lane_conditions` are unchanged. A backend Kotlin review plans the same lanes with the same
   rubrics as before this change.
9. No `generic` lane is reintroduced for any area covered by this spec; SKILL-196 AC 4 continues to
   hold.
10. Area coverage is unchanged: the set of distinct areas planned for an Android/KMP diff before and
    after this change is identical. Only the owning pack and its content change.
11. A regression test pins that an Android/KMP diff plans `architecture` owned by `kmp`, and that a
    backend Kotlin diff plans `architecture` owned by `kotlin`.

## Scope

- Add `architecture` to `kmp`'s declared areas, with content, `area_metadata` focus, and
  `lane_conditions` entry consistent with the other required `kmp` areas.
- Audit `performance`, `security`, `testing`, and `api-contracts` against an Android/KMP diff and
  record a per-area disposition, implementing a `kmp` declaration wherever the audit finds the
  `kotlin` content unreachable.
- Add the ownership regression test covering both the Android/KMP and backend Kotlin routes.

## Constraints

- The `kotlin` pack must not be weakened for its backend consumers. Its rules are correct for the
  runtime they target; this spec changes ownership on Android, not the backend rubric.
- Coverage must not shrink. An area may change owning pack; it must not lose its lane.
- New `kmp` content must satisfy the same loud-fail contract as existing packs: declared files exist,
  required sections present, `contract_version` matching the shell.
- Content additions are rules with reachable failure scenarios, matching the existing house style. No
  rule is added that cannot name the failure it prevents.
- No comments are added to any changed file.

## Non-Goals

- **Restructuring `kotlin` into a backend pack plus a neutral Kotlin core.** Considered as the
  alternative repair. Rejected for this ticket: it changes the rubric every existing backend consumer
  sees, for a benefit this spec obtains by adding ownership on the Android side only. Revisit if the
  audit in AC 5 finds all four remaining areas unreachable, which would make the split the smaller
  change.
- **Re-running or re-scoring the observed review.** The 2-vs-1 architecture result is motivating
  evidence, not an acceptance target.
- **Changing routing, lane selection, or fallback semantics.** Owned by SKILL-196; this spec assumes
  that behaviour.
- **Bundling areas into fewer lanes, tightening `lane_conditions` triggers, or a pre-fan-out triage
  pass.** Separate follow-ups tracked on SKILL-196.

## Diagnostic Evidence

Ownership and composition:

- `platform-packs/kmp/platform.yaml` — declares five areas; `code_review_composition.baseline_layers`
  composes `kotlin` with `required: true`.
- `platform-packs/kotlin/platform.yaml` — declares all ten areas; `area_metadata` focus lines naming
  Exposed/Spring/Hibernate, Compose Desktop/Swing/JavaFX, and terminal accessibility.
- `runtime-infra-fs/.../FileSystemReviewRubricResolver.kt:20-50` — resolves the owning pack's
  declared baseline and area content only; rubric content is not composed across baseline layers,
  so the owning pack's content is the entire rubric.

Measured skew:

- `platform-packs/kotlin/code-review/bill-kotlin-code-review-architecture/content.md` — 7 of 15 rule
  lines reference backend-only technology, including the Exposed transaction-runner rule, the
  `@Transactional` outbox rule, the `@Singleton` request-scope rule, and the `api(project(...))`
  ABI rule.
- Same measurement across `-performance` (3/15), `-security` (4/16), `-testing` (3/19),
  `-api-contracts` (3/16).
- `platform-packs/generic/code-review/bill-generic-code-review-architecture/content.md` — the
  technology-neutral counterpart, whose rules (`transaction-owner` spanning the atomic use case,
  `boundary-contract-map` authority per write, `external-effect-adapter`,
  `failure-propagation-path`) are reachable on an offline-first Android codebase.

Observed run:

- `review_run_lanes` and `review_run_finding_lanes` for `rvw-20260817-183143-ilvx` —
  `bill-generic-code-review-architecture` 2 findings, `bill-kotlin-code-review-architecture` 1,
  `bill-kotlin-code-review-testing` 6.
