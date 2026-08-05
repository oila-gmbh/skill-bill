---
status: Prepared
issue_key: SKILL-136
feature_name: android-native-review-specialists
preparation_mode: decomposed
source: inline user request, informed by a live capmo-android WE-4596 delegated review; scope redesigned 2026-08-05 to pack aliasing plus two declared areas, replacing an earlier four-specialist draft and an interim generic-baseline-sourcing draft
---

# SKILL-136: Treat Android and KMP as one pack, with Android-native persistence and reliability

## Intended Outcome

The `kmp` platform pack is the single owner of Android-shaped Kotlin, whether
or not multiplatform source sets exist. A plain, single-module Android diff
routes to it and receives Android/Compose-native `ui`, `ux-accessibility`, and
`platform-correctness` review, plus new `persistence` and `reliability`
specialists written for Room, SQLDelight, DataStore, WorkManager, and
offline-first sync — instead of the generic Kotlin pack's Exposed, Spring,
Hibernate, JDBC/R2DBC, broker-offset, and `resilience4j` rubric.

The pack keeps the `kmp` slug. Kotlin-language areas continue to come from the
Kotlin baseline unchanged, and the generic Kotlin pack is not modified.

## Motivation

A delegated `bill-code-review` run against capmo-android commit `5077e9c58`
(WE-4596, a 24-file/613-line Room + DataStore + DI refactor, no KMP
multiplatform source sets) routed to the generic Kotlin pack. Its two most
expensive lanes were `persistence` (25 tool calls, ~89.6k tokens) and
`reliability` (25 tool calls, ~101.7k tokens), both governed by
backend-framework rubric; `persistence` and `api-contracts` produced no
standalone findings at all. The findings that did surface — a ViewModel `Flow`
collector that dies permanently on an uncaught exception, a shared sync-engine
watermark wiped by an unrelated "Reset and resync" action, missing
idempotency-retry coverage — are Android and sync-engine concerns that a
Room/WorkManager-aware rubric targets directly.

Lane selection is the root cause. `platform-packs/kotlin/platform.yaml` gates
lanes on generic Kotlin keywords that Android satisfies by construction:

```yaml
persistence:   content: ["transaction", "hibernate", "exposed", "database"]
reliability:   content: ["retry", "timeout", "shutdown", "supervisor"]
ui:            content: ["compose", "swing", "javafx", "render"]
```

Room code contains `@Transaction` and `RoomDatabase`, so `transaction` and
`database` match with no backend present. WorkManager `Result.retry()` and
`SupervisorJob` match `retry` and `supervisor`. Jetpack Compose matches
`compose`, whose intent in that list is Compose Desktop — which is why a
second capmo-android review selected `bill-kotlin-code-review-ui` for a
Jetpack Compose Navigation diff, despite that specialist's own `Ignore`
section reading *"Android and Compose Multiplatform source-set or
target-specific behavior, which belongs to KMP."*

Retuning the Kotlin pack's keyword gates is rejected: `transaction` and
`database` are load-bearing for real backend repositories, so narrowing them
changes routing for every backend Kotlin service to fix an Android problem.

The KMP pack already lists `AndroidManifest.xml` in both
`routing_signals.strong` and `routing_signals.path`, so it already claims
Android signals. Exactly one tie-breaker contradicts that claim and pushes
plain-Android diffs to the Kotlin pack:

> Do not prefer this pack when adjacent Kotlin or Android signals dominate
> without multiplatform source sets.

### Why the pack declares these areas rather than composing them

`ReviewLaunchPlanPolicy` resolves area ownership by nearest composition depth
and builds one lane per area from the winner, so a pack's own declared area
(depth 0) shadows a baseline layer's (depth 1). Declaring `persistence` and
`reliability` in the KMP pack is therefore sufficient to stop the
backend-Kotlin lanes from being selected; no composition-contract change is
required.

This also matches the house pattern for a first-class platform pack. The iOS
pack declares all ten areas, uses no baseline composition, and writes purely
framework-specific rules (`NSManagedObjectContext` `perform`, SwiftData
`ModelContext` actor isolation, `NSManagedObjectID` transfers) without
restating technology-neutral kernel rules. Android/KMP follows the same shape
for the areas it owns.

KMP keeps inheriting the Kotlin baseline for language-level areas, unlike iOS,
because Kotlin's `runTest` virtual-time, dispatcher-ordering, Flow-assertion,
and `explicitNulls`/`encodeDefaults` rubric applies to Android unchanged.
There is no second Swift pack for iOS to inherit from; there is a Kotlin pack
for KMP.

### Related work

SKILL-129 addresses delegated-review runtime cost (repeated discovery,
double-orchestrator hops). It does not change lane selection or rubric
content, so it is complementary and out of scope here.

## Decided Behaviour

### Android and KMP are one pack

The pack's identity widens from "Kotlin Multiplatform" to "Android and Kotlin
Multiplatform". `display_name` changes accordingly; the `platform` slug stays
`kmp` and no skill is renamed, because `bill-kmp-code-review*` names are
referenced across the repo, installed agent directories, and user
configuration.

The contradicting tie-breaker is removed so that a plain, single-module
Android application — Android Gradle plugin markers plus `AndroidManifest.xml`,
with Room/DataStore/WorkManager/Compose usage — routes to this pack with no
multiplatform source sets present. A mixed monorepo where a backend
Kotlin/Exposed service dominates the changed product surface and an unrelated
Android module merely coexists continues to route to the generic Kotlin pack;
the retained tie-breakers still exclude backend-dominant diffs and still
exclude generated and vendored files from dominance.

### Lane conditions reachable from a plain Android layout

`lane_conditions.ui` currently gates on `path: ["androidMain", "iosMain"]`. A
single-module Android app has neither — its sources live under `src/main` — so
the lane could not fire even once routing selects the pack. The gate widens to
cover non-multiplatform Android layouts. `ux-accessibility` is content-only
and needs no change.

### Two new declared areas

`platform-packs/kmp/code-review/` gains two specialists, using the existing
approved area taxonomy:

- `bill-kmp-code-review-persistence` — Room transaction and dispatcher
  boundaries, migration safety, and destructive-migration fallbacks;
  SQLDelight transaction and driver-thread correctness; DataStore
  (Preferences and proto) write atomicity and concurrent-write races;
  offline-first sync idempotency keys, delta/watermark cursor advancement,
  and coupling through shared cross-feature tables.
- `bill-kmp-code-review-reliability` — WorkManager and `CoroutineWorker`
  retry, backoff, and constraint correctness; foreground-service and
  process-death recovery for long-running sync; `viewModelScope` and
  `SupervisorJob` collector death from an uncaught exception silently
  disabling a recurring trigger; connectivity-aware retry and failure
  telemetry.

Each follows the existing specialist shape (`Focus`, `Ignore`,
`Applicability`, `Project-Specific Rules`). Neither references Exposed, Spring
`@Transactional`, Hibernate, JDBC, R2DBC, broker ack/offset semantics, or
`resilience4j`; those stay exclusively in the Kotlin pack.

### Resulting composition

| Area | Owner |
| --- | --- |
| platform-correctness, ui, ux-accessibility | KMP declared (unchanged) |
| persistence, reliability | KMP declared (new) |
| architecture, performance, security, testing, api-contracts | kotlin baseline (unchanged) |

## Subtasks

### Subtask 1 — Alias Android to the KMP pack

Remove the contradicting tie-breaker from
`platform-packs/kmp/platform.yaml`, update `display_name`, and widen
`lane_conditions.ui` beyond multiplatform-only paths. Extend
`KmpPlatformPackTest` and `KotlinPlatformPackTest` with fixtures for: a plain
single-module Android diff (Room/DataStore/DI, no multiplatform markers); a
Jetpack Compose Navigation plus ViewModel/StateFlow diff; an actual
multiplatform diff touching `commonMain`/`androidMain`; and a mixed monorepo
where a backend Kotlin/Exposed service dominates. Assert selected pack, and
that the Compose fixture resolves `ui` to `bill-kmp-code-review-ui` rather
than `bill-kotlin-code-review-ui`.

Depends on: nothing.

### Subtask 2 — Author the persistence and reliability specialists

Scaffold `bill-kmp-code-review-persistence` and
`bill-kmp-code-review-reliability` through the existing scaffolder rather than
hand-assembling boilerplate, then author `content.md` covering the frameworks
listed in Decided Behaviour. Rules are framework-specific in the iOS pack's
style; each Blocker/Major rule names a concrete data-loss, consistency, or
availability failure scenario.

Depends on: nothing (parallel with Subtask 1).

### Subtask 3 — Declare the areas and route to them

Add `persistence` and `reliability` to `declared_code_review_areas`,
`declared_files`, `area_metadata`, and `lane_conditions` in
`platform-packs/kmp/platform.yaml`, and add matching rows to the Diff-Signal
Routing Table in
`platform-packs/kmp/code-review/bill-kmp-code-review/content.md`. Assert the
composed launch plan resolves both areas to the KMP specialists, that the five
baseline-sourced areas still resolve to `bill-kotlin-code-review-*`, and that
no other pack's composed plan changes.

Depends on: Subtasks 1 and 2.

### Subtask 4 — Controlled vocabularies for review-run attribution

Agent-authored descriptive columns on `review_runs` are stored as free prose,
so no routing or stack analysis is possible without hand-normalizing. Measured
across 329 recorded runs:

| Column | Distinct values | Example pollution |
| --- | --- | --- |
| `routed_skill` | 24 (for ~10 packs) | KMP appears 12 ways: `bill-kmp-code-review`, `… (baseline: bill-kotlin-code-review)`, `… (required baseline: …)`, `… (layers … baseline)`, `… (kmp-baseline -> …)`, `… (Kotlin baseline layer + KMP UI/UX-a11y specialists)`; also `` `bill-kotlin-code-review` `` backtick-quoted, a comma-joined `bill-kmp-code-review, bill-ios-code-review`, a full explanatory sentence, plus `unrouted`, `none`, `NULL` |
| `detected_stack` | 57 (for ~10 stacks) | `Kotlin` (149), `kotlin` (54), `Kotlin/JVM` (35) recorded as distinct |
| `detected_scope` | 296 (of 329 runs) | effectively unique per run |
| `execution_mode` | 2 + `NULL` | clean vocabulary; 22 rows null |

Record a canonical identifier alongside the preserved raw text for
`routed_skill`, `detected_stack`, and `detected_scope`, resolved at ingestion
against known pack skill names, known platform slugs, and a controlled scope
vocabulary (working tree, staged, commit range, pull request, other) with the
free-form detail retained in a separate field. `reviewAttributionPort
.routedSkillPlatformSlugs()` in `ReviewService.kt` already supplies the
routed-skill mapping for the analysis side. An unresolvable value is retained
and marked unresolved rather than silently bucketed. Backfill existing rows
where the canonical value is unambiguous, and record `execution_mode` for runs
that currently omit it.

`severity`, `confidence`, `disposition`, and `event_type` are already clean
controlled vocabularies and are not changed.

Depends on: nothing.

### Subtask 5 — Close review-run completeness gaps

Two fields are recorded so rarely that the analyses depending on them are
impossible:

- `specialist_reviews` is null or empty for 322 of 329 runs (98%). It is the
  only record of which lanes ran, so no finding can be attributed to the lane
  that produced it. Populate it from the composed launch plan at run time
  rather than from agent narration, and record it per lane so findings can be
  attributed rather than as one comma-joined string.
- `review_finished_at` is null for 234 of 329 runs (71%), so run duration and
  recency cannot be computed. Record it on the terminal path for every run,
  including runs that end without findings.

Add per-lane finding attribution: a finding carries the lane that produced it,
so pack and area effectiveness become measurable. This is what makes the
Open Questions measurable rather than deferred.

Depends on: Subtask 4.

### Subtask 6 — Review store integrity, outbox signal, and lifecycle

- `telemetry_outbox.last_error` is an empty string rather than `NULL` on the
  success path for all 10,495 rows, so a real delivery error is
  indistinguishable from a healthy send. Write `NULL` on success and backfill.
  Delivery itself is healthy (0 unsynced) and is not changed.
- The `learnings` table holds 0 rows while `session_learnings` holds 250,
  indicating either dead schema or a broken promotion path. Determine which,
  then remove the table or repair the promotion.
- Findings live in two stores that share no key: `review_runs`/`findings`
  (1463 findings, keyed by `review_run_id`) and the workflow review loop's
  `review_generation_findings`/`unaddressed_findings`/
  `review_finding_dispositions` (61/225/122 rows, keyed by
  `workflow_id`+`generation_id`). Triage outcomes therefore cannot be joined
  to the routed pack. Introduce a shared key so disposition and feedback data
  attach to the run that produced the finding. `feedback_events` already
  joins on `review_run_id` but covers only 43 of 329 runs (13%); widen
  coverage so accepted/rejected outcomes are recorded for every run that
  produces findings.
- No code path creates a `review-metrics.db` without applying migrations.
  `DbConstants.defaultDbPath` resolves state under `userHome`, yet a
  zero-byte, schema-less database exists in the working directory; that path
  is what made an earlier reading of this telemetry conclude the store was
  empty.
- Define and document a retention policy for `~/.skill-bill/`
  `review-metrics.*.db` snapshots. 37 hand-named backups currently occupy
  2.9 GB. These appear to be deliberate manual snapshots rather than a runtime
  defect, so the deliverable is a documented policy and an opt-in prune
  command, not automatic deletion of existing files.

Referential integrity is otherwise sound: no orphaned findings, and the outbox
drains fully. Neither is changed.

Depends on: Subtask 4.

### Subtask 7 — Documentation and full maintainer gate

Update README and any platform-pack catalog section listing the KMP pack's
declared areas and its Android coverage. Update `docs/review-telemetry.md`
for the canonical attribution fields, per-lane finding attribution, the shared
finding key, and the snapshot retention policy. Run the full gate and
`./install.sh` so local staged installs refresh.

Depends on: Subtasks 1–6.

## Scope

- `platform-packs/kmp/platform.yaml`: `display_name`, `tie_breakers`,
  `declared_code_review_areas`, `declared_files`, `area_metadata`,
  `lane_conditions`.
- Two new specialist directories under `platform-packs/kmp/code-review/`.
- `platform-packs/kmp/code-review/bill-kmp-code-review/content.md`: routing
  table rows.
- `KmpPlatformPackTest`, `KotlinPlatformPackTest`, and launch-plan resolution
  coverage.
- Review-telemetry ingestion and schema: canonical attribution fields and
  their migrations/backfill, per-lane finding attribution, run-completeness
  fields, outbox error signalling, the `learnings`/`session_learnings`
  resolution, a shared key across the two finding stores, the
  working-directory database-initialization path, and snapshot retention.
- README, platform-pack catalog, and `docs/review-telemetry.md` documentation.
- Source changes render through the normal staging flow; no generated
  `SKILL.md` wrappers or support pointers are committed.

## Acceptance Criteria

1. A plain single-module Android diff (Android Gradle plugin markers,
   `AndroidManifest.xml`, no multiplatform source sets, no dominant
   backend-framework markers) routes to the `kmp` pack. An actual
   multiplatform diff continues to route there. A fixture where a backend
   Kotlin/Exposed service dominates alongside an incidental Android module
   still routes to the generic Kotlin pack.
2. For a Jetpack Compose Navigation diff under plain-Android routing, `ui` and
   `ux-accessibility` resolve to `bill-kmp-code-review-ui` and
   `-ux-accessibility`, never the Kotlin pack's Compose-Desktop-scoped
   equivalents. The `ui` lane condition fires on a non-multiplatform
   `src/main` layout.
3. `platform-packs/kmp/platform.yaml` declares `persistence` and `reliability`
   with `declared_files`, `area_metadata.focus`, and `lane_conditions`
   entries, validating against
   `orchestration/contracts/platform-pack-schema.yaml`. The focus text names
   Android-native frameworks (Room, SQLDelight, DataStore, WorkManager)
   rather than backend-JVM frameworks.
4. `bill-kmp-code-review-persistence/content.md` and
   `-reliability/content.md` exist with non-placeholder `Focus`, `Ignore`,
   `Applicability`, and `Project-Specific Rules` sections covering the
   frameworks in Decided Behaviour. Neither references Exposed, Spring
   `@Transactional`, Hibernate, JDBC, R2DBC, broker ack/offset semantics, or
   `resilience4j`.
5. `bill-kmp-code-review`'s Diff-Signal Routing Table includes rows for both
   new areas, matching the existing rows' format and specificity.
6. The composed KMP launch plan resolves `persistence` and `reliability` to
   the KMP specialists, and `architecture`, `performance`, `security`,
   `testing`, and `api-contracts` to their `bill-kotlin-code-review-*`
   equivalents.
7. `platform-packs/kotlin/` and `platform-packs/generic/` specialists, rubric
   text, lane conditions, and routing signals are unmodified, and no pack
   other than `kmp` has a changed composed launch plan.
8. The `kmp` slug and all `bill-kmp-code-review*` skill names are unchanged.
9. `routed_skill`, `detected_stack`, and `detected_scope` each record a
    canonical identifier alongside preserved raw text, resolved at ingestion
    against known pack skill names, platform slugs, and a controlled scope
    vocabulary. Unresolvable values are retained and marked unresolved rather
    than silently bucketed. Existing rows are backfilled where unambiguous:
    grouping the 329 recorded runs by canonical routed skill yields one row
    per pack instead of 24 variants, and by canonical stack yields one row per
    stack instead of 57.
10. `specialist_reviews` is recorded from the composed launch plan for every
    run, per lane rather than as one joined string, and every finding carries
    the lane that produced it. Pack-and-area effectiveness is queryable by
    joining findings and their dispositions to the canonical routed skill.
11. `review_finished_at` and `execution_mode` are recorded on the terminal
    path for every run, including runs that produce no findings.
12. `telemetry_outbox.last_error` is `NULL` on the success path and non-null
    only for real delivery failures, with existing rows backfilled.
13. The `learnings` table is either removed as dead schema or its promotion
    path from `session_learnings` is repaired, with coverage either way.
14. Findings recorded through the workflow review loop and through review-run
    import share a key, so triage dispositions and feedback events join to the
    routed pack. Accepted/rejected outcomes are recorded for every run that
    produces findings.
15. No code path creates a `review-metrics.db` without applying migrations;
    a database file is either absent or schema-complete.
16. A snapshot retention policy for `~/.skill-bill/review-metrics.*.db` is
    documented and an opt-in prune command exists. Existing snapshots are not
    deleted automatically.
17. All review-telemetry schema changes ship as migrations that preserve
    existing rows, verified against a copy of a real 91.5 MB store.
18. README, platform-pack catalog, and `docs/review-telemetry.md` reflect the
    pack's Android coverage, its declared areas, the canonical attribution
    fields, per-lane attribution, the shared finding key, and the retention
    policy.
19. `skill-bill validate` passes, `(cd runtime-kotlin && ./gradlew check)`
    passes, `npx --yes agnix --strict .` passes, and
    `scripts/validate_agent_configs` passes. `./install.sh` runs after the
    source/pack changes so local staged installs refresh.

## Non-Goals

- Renaming the `kmp` platform slug or any `bill-kmp-code-review*` skill, or
  creating a separate top-level `android` pack.
- Declaring KMP-specific `architecture`, `performance`, `security`, `testing`,
  or `api-contracts` specialists; those stay on the Kotlin baseline.
- Changing the generic Kotlin pack's specialists, rubric, lane conditions, or
  routing signals — including retuning its keyword gates.
- Changing the `generic` pack, or introducing per-area baseline sourcing or
  additive baseline layering. Both were considered and are unnecessary once
  the pack declares the areas; additive layering remains available later as a
  cross-cutting refactor if kernel gaps are observed.
- Changing iOS, Go, PHP, Python, Rust, or TypeScript pack content.
- Changing the approved `declared_code_review_areas` taxonomy.
- Cross-lane expansion/evidence caching within a review run (SKILL-129).

## Constraints

- Source skill directories contain `content.md` only, except allowed
  `native-agents/` sources.
- Generated `SKILL.md` wrappers, support pointers, and provider-specific
  native-agent output are not committed.
- Use the existing scaffolder and pack patterns rather than hand-assembling
  specialist boilerplate.
- Schema or routing-contract changes fail loudly through typed errors and
  include parity coverage.
- The spec directory name still reads `android-native-review-specialists` from
  the original scope; the tracked path is left unchanged deliberately.

## Open Questions

- Neither declared area inherits the generic pack's technology-neutral kernel
  (atomic unit-of-work, lost-update guards, migration-forward safety), because
  a declared area shadows the baseline. This matches iOS, which has shipped
  that way without a reported gap. Whether it costs findings is not directly
  measurable today: `findings.issue_category` is a clean eight-value
  vocabulary that only partially maps to areas, and `specialist_reviews` is a
  comma-joined area list, so no finding is attributable to the lane that
  produced it. Subtask 5 closes this prospectively by recording per-lane
  attribution, but it cannot backfill runs already recorded, so the question
  stays open until enough post-change runs accumulate. If Android runs show
  missed kernel-level findings,
  the follow-up is additive baseline layering, not restating kernel rules in
  this pack.
- Aggregate telemetry complicates the single-run evidence in Motivation. Over
  329 recorded runs, KMP-routed reviews produced 344 findings (7.2 per run)
  against Kotlin's 3.8, and `data_persistence` is the *largest* category on
  KMP runs (123 findings, 35.8%) even though those runs used the
  backend-Kotlin persistence rubric. WE-4596's persistence lane produced no
  standalone findings, but that is one run, not the pattern. The case for this
  change rests on rubric applicability and wasted tool calls, not on the
  persistence lane being unproductive in general.

## Validation Strategy

Extend the per-pack tests with the four routing fixtures (plain Android,
Compose Navigation, true multiplatform, backend-dominant monorepo) and assert
composed area-to-skill resolution for KMP, plus an unchanged plan for at least
one other pack. Assert the new rubric text contains Room/DataStore/WorkManager
terms and omits Exposed/Hibernate/JDBC/R2DBC/`resilience4j` terms. Then:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Render `bill-kmp-code-review` and its new specialists to confirm the installed
shell exposes both new areas, and inspect the generated staging hash after
`./install.sh`.

## Next Path

```bash
skill-bill goal SKILL-136
```
