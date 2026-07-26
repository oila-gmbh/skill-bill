# SKILL-114 Subtask 9 - iOS Pack Depth

## Scope

Use iOS as the mature-pack benchmark while auditing it against the new
substance standard. Preserve its proven platform guidance and offline add-ons,
then close gaps across modern Swift language modes, Apple SDK ownership,
project/build tooling, persistence/networking, UI/accessibility, testing, and
quality checks without overfitting to one application architecture.

Required audit depth includes strict Swift concurrency and actor/sendability
behavior, SwiftUI/UIKit ownership and lifecycle, Observation and state models,
background execution and extension limits, URLSession and API contracts,
Core Data/SwiftData/SQLite-family persistence, migration/offline consistency,
Keychain/privacy/entitlements/deep-link/pasteboard/share security, performance
and memory/image/rendering behavior, XCTest/Swift Testing/UI/snapshot evidence,
relaunch/retry/shutdown/telemetry, accessibility/localization/Dynamic Type,
and Xcode/SPM/project configuration.

Quality checking must discover repository/CI schemes and commands, handle
workspace/project/package selection, build/test destinations, formatting and
linting, package resolution, generated artifacts, concurrency diagnostics,
static/security tools when configured, and targeted-to-full escalation.

## Acceptance Criteria

1. All ten iOS specialists continue to meet the depth gate after removing any
   universal review prose now owned by shared orchestration.
2. Each specialist contains current, applicability-gated Swift/iOS SDK,
   framework, lifecycle, tooling and observable failure guidance; repo-local
   knowledge references remain optional enrichment rather than hidden required
   content.
3. Correctness, architecture, reliability, performance and testing cover
   modern Swift concurrency, state/lifecycle, background work, resource and
   memory behavior, relaunch/degradation and current Apple testing surfaces.
4. API, persistence and security cover URLSession/Codable and detected GraphQL,
   Core Data/SwiftData/SQLite ecosystems, migrations/offline sync, Keychain,
   privacy/entitlements, links/sharing/pasteboard and sensitive output.
5. UI and UX/accessibility cover current SwiftUI/UIKit ownership, navigation,
   presentation, animation, layout/adaptation, Dynamic Type, VoiceOver,
   focus/keyboard, localization and feedback across supported OS versions.
6. All four offline add-ons retain clear activation, distinct ownership,
   reachable pointers and non-duplicative substantive value.
7. The quality checker covers discovered Xcode/SPM/repo commands, destinations,
   build/tests, format/lint, resolution/generated state, concurrency and
   configured security/static-analysis paths.
8. iOS passes both duplication thresholds and updates tests/history with the
   final audit evidence.
9. iOS pack tests, `skill-bill validate`, and relevant Gradle checks pass.

## Non-Goals

- No requirement to adopt a single iOS architecture or persistence library.
- No proprietary application rules in the public pack.
- No replacement of real platform detail solely to increase textual
  distinctness.

## Dependency Notes

Depends on subtask 1. Independent of other pack elevations.

## Validation Strategy

Run the maintained-pack audit for iOS, focused iOS/add-on/quality-check tests,
`skill-bill validate`, and the relevant Gradle suite.

## Next Path

Proceed independently; subtask 10 waits for completion.
