## [2026-08-05] SKILL-136 KMP persistence and reliability specialists
Areas: KMP code-review specialists, KMP platform manifest, runtime scaffold platform-pack tests
- Added `bill-kmp-code-review-persistence` and `bill-kmp-code-review-reliability` rubrics so both areas are owned by Android/KMP semantics instead of inheriting the Kotlin backend rubrics.
- Persistence covers Room/SQLDelight/DataStore migration safety, write atomicity, and offline-first cursor/idempotency; reliability covers WorkManager/`CoroutineWorker` retry-backoff-constraints, process-death and foreground-service recovery, and collector death from uncaught exceptions.
- Reusable: overriding an inherited area means the override must be term-disjoint from the parent pack — every Blocker/Major rule names a concrete data-loss or availability scenario, and backend terms (Exposed, Spring `@Transactional`, Hibernate, JDBC/R2DBC, broker ack/offset, resilience4j) are asserted absent by `KmpPlatformPackTest`.
- Specialist source directories hold `content.md` only; wrappers, support pointers, and native-agent outputs stay generated, and a test guards against committing them.
- Known limitation: rubric term parity is enforced by keyword assertions, so renamed APIs in future Jetpack releases need the test updated alongside the rubric.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-05] SKILL-136 Alias Android to the KMP pack
Areas: KMP platform manifest routing signals and lane conditions, runtime scaffold platform-pack tests
- The `kmp` pack now owns plain single-module Android diffs: `display_name` widened to "Android & Kotlin Multiplatform" and the self-contradicting tie-breaker that rejected Android signals without multiplatform source sets was removed and replaced with a backend-dominance exclusion.
- Android markers (`androidx.compose`, `androidx.navigation`, `androidx.lifecycle`, `androidx.room`, `androidx.datastore`, `@Composable`, `dagger.hilt`) were added to `routing_signals.content` only.
- Reusable: never add `src/main` to `routing_signals.path` — a unique path signal scores 10 points on every backend Kotlin file and flips backend-dominant diffs to this pack. Path-shaped Android layout belongs in `lane_conditions`, where `ui` gained `/src/main/` so Compose lanes fire on non-multiplatform layouts.
- Slug `kmp` and all `bill-kmp-code-review*` skill names are unchanged; `platform-packs/kotlin` and `platform-packs/generic` are byte-unmodified, so Kotlin backend routing is untouched.
- Known limitation: `ux-accessibility` remains content-only and does not fire from Android layout paths alone.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-07-12] SKILL-114 KMP pack depth
Areas: KMP review composition and specialists, KMP add-ons, quality-check routing, telemetry, platform-pack substance audit
- Effective KMP review coverage composes seven inherited Kotlin areas with three KMP overrides, and production composition plus merger tests verify lane attribution and deduplication.
- All twelve add-ons use manifest-owned activation, exclusion, ownership, and consumer-pointer contracts; parsed governance tests keep every declared consumer reachable without duplicating core rubrics.
- Reusable: platform substance audits require a directly declared quality checker rather than inheriting quality behavior through review baseline composition.
- Migrated KMP quality checks from the historical Kotlin fallback to a direct KMP route; telemetry preserves Kotlin-only normalization while emitting no fabricated fallback reason for KMP.
- Known limitation: live install synchronization remains deferred until runtime goal continuation ends, and the audit-reported criterion 1 integration-test gap remains for remediation.
Feature flag: N/A
Acceptance criteria: 8/9 implemented

## [2026-07-10] SKILL-112 KMP pack elevation
Areas: KMP code-review baseline and specialists, KMP add-ons, platform manifest, runtime install staging and tests
- Added the platform-correctness lane and elevated UI and UX-accessibility to canonical routing, ownership, severity, and Compose Multiplatform rules.
- Kept `compose-guidelines.md` as the single UI rubric source, target-scoped Android guidance, and repaired R8, navigation, interop, adaptive-layout, and edge-to-edge add-on reachability.
- Reusable: authored skill companion files are staged flat beside wrappers and participate in shared filename validation, install-plan cache validation, and deletion recovery.
- Followed manifest-declared specialist/native-agent registration and canonical structure conformance without changing existing pack frontmatter or contract versions.
- Known limitation: live `./install.sh` synchronization remains deferred until the runtime goal-continuation guard is inactive; isolated staging and plan/apply regression coverage passed.
Feature flag: N/A
Acceptance criteria: 8/8 implemented
