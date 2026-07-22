---
status: Draft
issue_key: SKILL-136
source: inline user request, informed by a live capmo-android WE-4596 delegated review
---

# SKILL-136: Android-native persistence/reliability/testing/api-contracts specialists for the KMP pack

## Intended Outcome

An Android or KMP diff gets persistence, reliability, testing, and
api-contracts review coverage written for the frameworks Android/KMP code
actually uses — Room, SQLDelight, DataStore, WorkManager/CoroutineWorker,
foreground services, offline-first sync engines, Retrofit/Ktor client/Apollo
Kotlin, kotlinx.serialization — instead of coverage borrowed wholesale from
the generic backend-Kotlin pack, whose entire persistence/reliability rubric
is written for Exposed, Spring proxy transactions, Hibernate sessions,
JDBC/R2DBC, message brokers, and resilience4j. A plain, single-module Android
app with no multiplatform source sets gets this native coverage too, not just
actual `commonMain`/`androidMain`/`iosMain` multiplatform projects.

The generic Kotlin pack, its specialists, and its backend-framework rubric
text are unchanged and continue to serve real backend/server Kotlin
repositories exactly as today.

## Motivation

A delegated `bill-code-review` run against capmo-android commit `5077e9c58`
(WE-4596, a 24-file/613-line Room + DataStore + DI refactor, no KMP
multiplatform source sets) was audited end-to-end using the run's own stored
telemetry (`review_runs` row `rvw-20260721-123352-td8f` in
`review-metrics.db`). Its self-reported `detected_stack` was `"Kotlin
(Android app, no KMP/multiplatform markers in scope)"`, and its own reasoning
field states: *"Kotlin/JVM + Gradle + Android markers dominate the diff
without any multiplatform source-set evidence, so the Kotlin platform pack
owns this review per its tie-breaker rules."* That tie-breaker is intentional
and documented in `platform-packs/kmp/platform.yaml`:

> Do not prefer this pack when adjacent Kotlin or Android signals dominate
> without multiplatform source sets.

The run then launched six generic-Kotlin-pack specialists (architecture,
platform-correctness, persistence, testing, api-contracts, reliability). The
two most expensive lanes were `persistence` (25 tool calls, ~89.6k tokens)
and `reliability` (25 tool calls, ~101.7k tokens) — both governed entirely by
`platform-packs/kotlin/code-review/bill-kotlin-code-review-persistence` and
`-reliability`, whose rules reference `newSuspendedTransaction`, Spring
`@Transactional` proxy boundaries, Hibernate lazy-loading, JDBC/R2DBC
hand-off, `@Version` optimistic locks, broker offset commits, and
`resilience4j` — none of which exist anywhere in a Room/DataStore/WorkManager
Android codebase. The review's own final output confirms the mismatch: the
`api-contracts` and `persistence` lanes produced no standalone findings and
were folded into other lanes' narratives, while the real, valuable findings
that did surface (a ViewModel `Flow` collector that dies permanently on an
uncaught exception; a shared sync-engine table's watermark getting wiped by
an unrelated "Reset and resync" action; missing idempotency-retry test
coverage) are Android/coroutine/sync-engine concerns that a Room- and
WorkManager-aware rubric would target directly, with far less time spent
first ruling out inapplicable backend frameworks.

This is not unique to capmo-android. `platform-packs/kmp/platform.yaml`
declares only three code-review areas —
`platform-correctness`/`ui`/`ux-accessibility` — and sources
`persistence`/`reliability`/`testing`/`api-contracts`/`architecture`
/`performance`/`security` from the generic Kotlin baseline via
`code_review_composition.baseline_layers` (`mode: kmp-baseline`). Every real
multiplatform Android/KMP review hits the same backend-flavored
persistence/reliability rubric, not just plain-Android diffs that get routed
around the KMP pack entirely. By contrast, `platform-packs/ios/platform.yaml`
already declares all ten approved areas with iOS-native rubric
(`persistence` covers Core Data/SwiftData/GRDB; `reliability` covers
`BGTaskScheduler` and background `URLSession` relaunch/expiration) — KMP is
missing the same parity.

A second, independent delegated-review routing decision on the same
capmo-android repository confirms this is not limited to
persistence/reliability. For a diff touching only
`feature/tickets/.../plan/navigation/nav3/PlansEntries.kt` (Android Jetpack
Compose Navigation) plus ViewModel/StateFlow/DI/test files, the router's own
stated reasoning was: *"Not a KMP project (no androidMain/iosMain/commonMain
source sets exist despite AndroidManifest.xml), so per the KMP pack's own
tie-breaker this routes to the standalone Kotlin pack... I'll run delegated
review with architecture + platform-correctness (baseline) + ui + testing
specialists."* The `ui` lane selected here is
`bill-kotlin-code-review-ui`, whose own `Applicability` section reads *"Run
only when the diff contains Compose Desktop, Swing, JavaFX, server-rendered
Kotlin, CLI, or TUI surfaces"* and whose own `Ignore` section reads *"Android
and Compose Multiplatform source-set or target-specific behavior, which
belongs to KMP."* Jetpack Compose Navigation is none of the applicable
surfaces and is explicitly named as out-of-scope by the specialist's own
contract — the router matched generically on "Compose" in the diff without
applying the routing table's actual "Compose **Desktop**" qualifier. The
correct lane, `bill-kmp-code-review-ui`, is already Android/Compose-native
(state hoisting, recomposition, navigation-graph integration, previews) and
sits unused because this plain-Android repo never reaches the KMP pack. This
is the same routing gap the Plain-Android Routing change below closes; it
just surfaces through `ui`/`ux-accessibility` instead of
`persistence`/`reliability`, and is added as an explicit acceptance
criterion below since it is otherwise easy to overlook as a mere consequence
of the routing change.

### Related work

SKILL-129 (in progress) fixes a different, complementary problem: repeated
discovery, double-orchestrator hops, and unbounded token/tool-call cost in
the delegated-review *runtime*. It does not change which rubric content a
specialist receives or which pack a diff routes to, so it would make the
mismatched backend-Kotlin persistence/reliability lanes launch more cheaply
without resolving the mismatch itself. Cross-lane dedup of a single
expansion fact (e.g. "who else calls this shared DAO") that multiple
specialists independently rediscover in the same run — noticed during the
same audit — belongs to SKILL-129's packet/evidence-broker contract and is
out of scope here; this spec only adds pack content and routing.

## Decided Behaviour

### New KMP-native specialists

`platform-packs/kmp/code-review/` gains four new specialist skills, using the
existing approved area taxonomy (no new area names):

- `bill-kmp-code-review-persistence` — Room transaction/dispatcher boundaries
  and migration safety; SQLDelight transaction and driver-thread correctness;
  DataStore (Preferences and proto) read/write atomicity and concurrent-write
  races; offline-first sync-engine idempotency keys, delta/watermark cursor
  advancement, and coupling through shared cross-feature tables.
- `bill-kmp-code-review-reliability` — WorkManager/CoroutineWorker retry,
  backoff, and constraint correctness; foreground-service and process-death
  recovery for long-running sync; `SupervisorJob`/`viewModelScope` collector
  death from an uncaught exception silently disabling a recurring trigger;
  connectivity-aware retry and telemetry for offline-first sync.
- `bill-kmp-code-review-testing` — `runTest`/Turbine `Flow` assertion
  patterns; Robolectric and instrumented Room/DataStore test realism (e.g. an
  in-memory database that is never closed); idempotency and retry-path test
  coverage for sync/cleanup handlers.
- `bill-kmp-code-review-api-contracts` — Retrofit/Ktor-client/Apollo Kotlin
  GraphQL request and response contract stability; kotlinx.serialization and
  protobuf/proto3 schema evolution (field reservation, dangling-reference
  safety on removal); DTO/domain-model mapping nullability and default-value
  drift across app versions.

Each new specialist's `content.md` follows the existing specialist shape
(`### Focus`, `### Ignore`, `### Applicability`, `### Project-Specific
Rules`) used by the Kotlin and iOS packs. None of the four reference Exposed,
Spring, Hibernate, JDBC, R2DBC, message-broker offset/ack semantics, or
`resilience4j` — those remain exclusively in the generic Kotlin pack's
rubric, unchanged.

### Routing table and composition

`platform-packs/kmp/code-review/bill-kmp-code-review/content.md`'s
Diff-Signal Routing Table gains rows for the four new areas (Room/SQLDelight/
DataStore/transaction/migration signals → `persistence`; WorkManager/
CoroutineWorker/foreground-service/retry/backoff/sync-cursor signals →
`reliability`; `*Test.kt`/Robolectric/Turbine/instrumented-test signals →
`testing`; Retrofit/Ktor-client/Apollo/kotlinx.serialization/DTO signals →
`api-contracts`), in the same format as the existing UI/ux-accessibility
rows.

`platform-packs/kmp/platform.yaml`'s `code_review_composition.baseline_layers`
entry for the Kotlin pack (`mode: kmp-baseline`) stops being the source for
`persistence`, `reliability`, `testing`, and `api-contracts` once the KMP pack
declares its own. `architecture` and `platform-correctness` continue to come
from the Kotlin baseline unchanged — their existing rubric (Gradle module
boundaries, injected `CoroutineScope` lifecycles, `CancellationException`
propagation, `StateFlow`/`SharedFlow` invariants) is stack-neutral enough to
still apply correctly to Android/KMP code, unlike persistence/reliability,
whose entire existing rubric is backend-framework-specific.

### Plain-Android routing

`platform-packs/kmp/platform.yaml`'s routing tie-breakers change so a plain,
single-module Android application — Android Gradle plugin markers plus
`AndroidManifest.xml`, with Room/DataStore/WorkManager/Compose usage — routes
to the KMP pack even with no `commonMain`/`androidMain`/`iosMain`/
`expect`/`actual` multiplatform source sets, *provided* no backend-framework
markers (Exposed, Spring, Hibernate, Ktor server, JDBC/R2DBC, message-broker
client libraries) dominate the changed product surface. A mixed monorepo
where a backend Kotlin/Exposed service dominates the diff and an unrelated
Android module merely coexists keeps routing to the generic Kotlin pack,
exactly as today — this only widens the KMP pack's claim over genuinely
Android-shaped diffs, it does not change backend-service routing.

## Scope

- `platform-packs/kmp/platform.yaml`: new `declared_code_review_areas`
  entries, `declared_files`, `area_metadata`, updated
  `code_review_composition.baseline_layers`, and updated routing
  `tie_breakers`.
- Four new specialist directories under `platform-packs/kmp/code-review/`.
- `platform-packs/kmp/code-review/bill-kmp-code-review/content.md`: routing
  table additions.
- Any platform-pack catalog or README section listing the KMP pack's declared
  areas.
- Focused runtime tests for routing (plain Android → KMP; mixed
  backend-dominant monorepo → generic Kotlin, unchanged) and for the new
  areas' rubric content (Android-native terms present, backend-only terms
  absent).
- Source changes are rendered/installed through the normal staging flow; no
  generated `SKILL.md` wrappers or support pointers are committed.

## Acceptance Criteria

1. `platform-packs/kmp/platform.yaml` declares `persistence`, `reliability`,
   `testing`, and `api-contracts` under `declared_code_review_areas`, each
   with a `declared_files` entry and an `area_metadata.focus` description
   naming Android/KMP-native frameworks (Room, SQLDelight, DataStore,
   WorkManager/CoroutineWorker, Retrofit/Ktor client, Apollo Kotlin,
   kotlinx.serialization) rather than backend-JVM frameworks, validating
   against `orchestration/contracts/platform-pack-schema.yaml`.
2. `platform-packs/kmp/code-review/bill-kmp-code-review-persistence/content.md`,
   `-reliability/content.md`, `-testing/content.md`, and
   `-api-contracts/content.md` exist with non-placeholder `Focus`/`Ignore`/
   `Applicability`/`Project-Specific Rules` sections covering the frameworks
   listed in Decided Behaviour. None of the four files reference Exposed,
   Spring `@Transactional`, Hibernate, JDBC, R2DBC, message-broker
   ack/offset semantics, or `resilience4j`.
3. `bill-kmp-code-review`'s Diff-Signal Routing Table includes routing rows
   for all four new areas, matching the existing table's format and
   specificity.
4. `code_review_composition.baseline_layers` in `platform-packs/kmp/platform.yaml`
   no longer supplies `persistence`, `reliability`, `testing`, or
   `api-contracts` coverage for KMP reviews once the KMP pack declares its
   own; `architecture` and `platform-correctness` remain sourced from the
   Kotlin baseline, unchanged.
5. `platform-packs/kotlin/` specialists, rubric text, and routing signals are
   unmodified; the generic Kotlin pack continues to serve backend/server
   Kotlin repositories exactly as before this change.
6. `platform-packs/kmp/platform.yaml`'s routing `tie_breakers` route a plain
   single-module Android diff (Android Gradle plugin markers,
   `AndroidManifest.xml`, no multiplatform source sets, no dominant
   backend-framework markers) to the KMP pack. A fixture where a backend
   Kotlin/Exposed service dominates the diff and an unrelated Android module
   is merely present continues to route to the generic Kotlin pack.
7. Once plain-Android routing selects the KMP pack (AC6), the `ui` and
   `ux-accessibility` lanes it selects for an Android Jetpack Compose diff
   are `bill-kmp-code-review-ui`/`-ux-accessibility` (Android/Compose
   Multiplatform-native), never the generic Kotlin pack's
   `bill-kotlin-code-review-ui`/`-ux-accessibility` (Compose
   Desktop/Swing/JavaFX/CLI/TUI-native). A fixture touching only Android
   Jetpack Compose Navigation, ViewModel/StateFlow, and DI/test files (no
   multiplatform markers, no backend/persistence signal) selects
   architecture + platform-correctness (Kotlin baseline) + KMP's
   ui/ux-accessibility/persistence/reliability/testing/api-contracts as
   applicable — never a Kotlin-pack specialist whose own `Applicability`
   gate would self-disqualify it (e.g. `bill-kotlin-code-review-ui` requires
   "Compose Desktop, Swing, JavaFX, server-rendered Kotlin, CLI, or TUI
   surfaces," none of which are present).
8. Focused tests (mirroring the existing per-pack pattern, e.g.
   `KmpPlatformPackTest`) cover: plain-Android routing selects KMP and its
   new specialists over the generic Kotlin pack's; an Android Jetpack
   Compose Navigation-only fixture selects KMP's `ui`/`ux-accessibility`
   rather than the Kotlin pack's Compose-Desktop-flavored ones; the mixed
   backend-dominant fixture is unaffected; and each new area's declared
   focus/rubric text contains Android-native terms and omits the
   backend-only terms unique to the generic Kotlin pack's persistence and
   reliability rubrics.
9. README and any platform-pack catalog documentation listing the KMP pack's
   declared areas are updated to include the four new areas, mirroring how
   the iOS pack's ten areas are documented today.
10. `skill-bill validate` passes, `(cd runtime-kotlin && ./gradlew check)`
    passes, `npx --yes agnix --strict .` passes, and
    `scripts/validate_agent_configs` passes. `./install.sh` runs after the
    source/pack changes so local staged installs refresh.

## Non-Goals

- Renaming the `kmp` platform pack, its display name, or its slug.
- Adding `architecture`, `performance`, or `security` specialists dedicated
  to KMP; those remain sourced from the Kotlin baseline, whose existing
  rubric is stack-neutral enough to still apply.
- Changing the generic Kotlin pack's specialists, rubric text, routing
  signals, or its applicability to backend/server Kotlin repositories.
- Cross-lane expansion/evidence caching within a single review run (tracked
  under SKILL-129's packet/evidence-broker contract, not here).
- A brand-new top-level `android` platform pack separate from `kmp`.
- Changing the approved `declared_code_review_areas` taxonomy itself.
- Changing iOS, Go, PHP, Python, Rust, or TypeScript pack content.

## Constraints

- Source skill directories under the platform pack contain `content.md` only,
  except for allowed `native-agents/` sources.
- Generated `SKILL.md` wrappers, support pointers, and provider-specific
  native-agent output are not committed.
- Use the existing scaffolder/pack patterns where possible rather than
  hand-assembling new specialist boilerplate.
- Any schema or routing-contract changes fail loudly through typed errors and
  include parity coverage.

## Validation Strategy

Add or extend `KmpPlatformPackTest` (or the equivalent per-pack test) with
fixtures for: a plain single-module Android diff with Room/DataStore/DI
changes and no multiplatform markers; an actual multiplatform diff touching
`commonMain`/`androidMain`; and a mixed monorepo where a backend
Kotlin/Exposed service dominates alongside an incidental Android module.
Assert selected pack, selected areas, and that persistence/reliability rubric
text served to Android/KMP diffs contains Room/DataStore/WorkManager terms
and omits Exposed/Hibernate/resilience4j terms. Then run the full maintainer
gate:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Render `bill-kmp-code-review` and its new specialists to confirm the
installed shell exposes the four new areas, and inspect the generated
staging hash after `./install.sh`.

## Next Path

Run `bill-feature` on
`.feature-specs/SKILL-136-android-native-review-specialists/spec.md`.
