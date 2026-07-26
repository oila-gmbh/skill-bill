# SKILL-112 Subtask 7 - iOS Pack Alignment

## Scope

Model the ios pack after the kotlin/kmp reference standard. The ios pack is
structurally mature (full 10-area canonical skeleton, the repo's best
finding-discipline guidance, a working add-on system) but is overfit to one
Apollo+GRDB app, ships a stub quality-check, and predates Swift Concurrency.
Sources: the 2026-07-09 ios audit and cross-pack matrix.

### 1. Quality-check rebuild (`bill-ios-code-check/content.md`)

Replace the 24-line boilerplate stub with the standard's quality-check
skeleton at go-pack depth: command discovery (`Makefile`, `Mintfile`,
fastlane lanes, tuist, CI workflows), `xcodebuild build`/`test` with scheme
and destination handling vs `swift build`/`swift test` for SPM-only scopes,
lint/format config discovery (`.swiftlint.yml`, `.swiftformat`), a
priority-ordered fix ladder, a never-suppress rule
(`// swiftlint:disable`), and targeted-vs-full-suite escalation.

### 2. De-overfit the tuned lanes

Keep the app-specific rules (they are good) but add applicability branches
so a generic iOS app is covered:

- **api-contracts**: REST/`Codable` branch — decoding-strategy drift,
  non-optional field decode crashes, status-code and error-payload mapping —
  alongside the existing GraphQL/Apollo rules
- **persistence**: Core Data branch (`NSManagedObjectContext`
  `perform`/`performAndWait` threading, merge policies,
  lightweight-migration limits) and SwiftData alongside GRDB
- **baseline routing table**: generalize rows keyed to one app's file
  conventions (`Scene+{Feature}.swift`, `assertSnapshotsOf`) into
  signal-based rows, keeping the app-specific entries as examples

### 3. Modern platform failure modes

- **platform-correctness**: Swift Concurrency — `@MainActor` isolation
  violations, `Sendable` conformance across actor boundaries, unstructured
  `Task {}` lifetime and cancellation on view teardown (`.task` vs
  `onAppear`), actor reentrancy, leaked or double-resumed
  `CheckedContinuation`
- **reliability**: OS background execution — `BGTaskScheduler`
  registration and expiration handlers, `beginBackgroundTask` expiration,
  background `URLSession` configuration and delegate rewiring after
  relaunch, sync interrupted by termination
- **performance** (currently 3 rules): SwiftUI `body` recomputation cost,
  ImageIO thumbnail downsampling vs `UIImage(contentsOfFile:)`,
  `autoreleasepool` in import loops
- **security**: Keychain access groups and `kSecAttrAccessible` classes,
  ATS exception review, `Logger` privacy specifiers (`%{public}`),
  pasteboard expiration
- **ui**: `@StateObject` vs `@ObservedObject` ownership misuse for
  pre-iOS-17 deployment targets

### 4. Structure conformance and manifest

- severity closers: subtask 1 normalized wording and removed "Nit"; add
  closers to `architecture` and `testing`; verify all ten specialists pass
  the conformance test and remove `ios` from the exemption list
- baseline: add the generated/vendored exclusion (generated `API.swift`,
  `Pods/`, `.build/`, `__Snapshots__/`, `project.pbxproj` churn) to scoping
  and Mixed Diffs; keep `## Finding Discipline` (it seeded the standard)
- manifest: add strong signals (`project.pbxproj`, `Info.plist`, `Podfile`,
  `.swiftlint.yml`, `fastlane/Fastfile`; the `"*.swift"` glob landed in
  subtask 1); replace copied-generic `area_metadata.focus` strings with
  iOS-bespoke ones that match each lane's actual framing
- rewrite `native-agents/agents.yaml` descriptions to the canonical pattern
  ("iOS <area> specialist code reviewer. Runs against ... Returns a Risk
  Register in the F-XXX bullet format.")

## Acceptance Criteria

1. `bill-ios-code-check/content.md` contains command discovery, xcodebuild
   and SPM command selection, lint config discovery, a fix ladder, the
   never-suppress rule, and escalation guidance.
2. api-contracts and persistence each carry an applicability branch for the
   generic stack (REST/`Codable`; Core Data/SwiftData) alongside the
   existing tuned rules.
3. Every failure-mode addition in Scope section 3 appears as an enforceable
   rule in the named specialist.
4. All ten ios specialists carry the canonical severity closer; no "Nit"
   rating remains; `ios` is removed from the conformance-test exemption
   list with the test passing.
5. The ios baseline scoping excludes generated/vendored files and the
   routing table is signal-based rather than single-app-keyed.
6. The ios manifest carries the enriched signals and iOS-bespoke
   `area_metadata.focus` strings, and agents.yaml follows the canonical
   description pattern.
7. `skill-bill validate` passes and
   `(cd runtime-kotlin && ./gradlew check)` passes including the ios pack
   tests.

## Non-Goals

- No `feature_addon_usage` for the offline add-ons (they are review-only
  content; a feature-task surface would need new implementation add-ons).
- No removal of app-specific rules; they stay as tuned branches.
- No new review areas.
- No review of `iosMain` Kotlin (owned by the kmp pack's
  platform-correctness specialist from subtask 3).

## Dependency Notes

Depends on subtasks 1-3. Can run independently of subtasks 4-6.

## Validation Strategy

```bash
skill-bill validate --skill-name bill-ios-code-check
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
```

## Next Path

On completion, proceed to subtask 8 (conformance gate retirement and final
verification).
