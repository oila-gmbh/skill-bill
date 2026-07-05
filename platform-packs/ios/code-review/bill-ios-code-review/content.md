---
name: bill-ios-code-review
description: Use when reviewing native iOS/Swift changes (UIKit/SwiftUI, Xcode projects, local SPM packages).
internal-for: bill-code-review
---

# Adaptive iOS PR Review

You are an experienced iOS architect conducting a code review.

This skill owns the baseline native iOS review layer. It covers UIKit/SwiftUI app code, local Swift Package Manager modules, and the specialist-selection logic that the iOS review lanes build on top of.

## Project Guidance

Treat coherent local project standards and established architecture as the consistency target. Do not preserve local patterns that are inconsistent, accidental, or harmful; use this iOS pack to flag concrete maintainability, correctness, security, scalability, or testability risks and guide changes toward established iOS/Swift practices.

## Finding Discipline

These rules apply to every specialist lane. They exist to keep precision high — a plausible-sounding finding that cannot actually fire, or that is rated far above its real impact, erodes trust in the whole review.

- **Verify the triggering precondition before asserting a consequence.** Before reporting "this crashes / hangs / shows the wrong screen / gets requested," confirm the state or configuration that would trigger it can actually occur. Examples of premises to check first: that a SwiftUI/WidgetKit family or lifecycle case can actually be requested given the declared `supportedFamilies`/guards; that an interactive-pop (swipe-back) gesture is still enabled given `navigationBarBackButtonHidden` + custom leading items; that a "spinner never resets" path is reachable when both success and failure branches reset it; that a value can actually be `nil`. If the guarding config contradicts the premise, do not report it.
- **Scan removed (`-`) lines, not just additions.** Dropped call sites, deleted effects, removed guards/tracking, and commented-out flows are as important as added code — a refactor that silently drops a data-loading call, a network-fetch fallback, or an analytics event is a real regression that only shows up on the `-` side of the diff. On refactor-heavy or deletion-heavy diffs, explicitly diff what behavior the removals drop.
- **Get Swift semantics right.** Do not report a finding that depends on a misreading of the language. Common traps:
  - A `do { … }` block **without** a `catch` does not swallow errors — `try` inside it propagates to the enclosing throwing scope. It is only "swallowed" if there is an actual empty/`catch {}` handler.
  - An `enum` with **no associated values**, or whose associated values are all `Equatable`/`Hashable`, gets `Equatable`/`Hashable` synthesized automatically — a nested payload-less enum does not break the outer type's synthesis.
  - A computed `URL?` (or any optional) that coalesces to a **non-optional** value (`optional ?? nonOptional`) can never be `nil`; a `guard let` on it is dead, but that is a dead-code Nit, not a crash.
  - `[weak self]`/`[weak store]` on an `ObservedObject`/store that outlives the closure is a robustness/consistency nit, not a leak or crash.
- **Calibrate severity to demonstrated impact.** Reserve Blocker/Major for a concrete, reachable failure (data loss, crash, wrong result a user or crash report would see). A finding that flags conformance to (or deviation from) an intentional, pervasive house pattern with no demonstrated wrong outcome is at most Minor — and often a Nit. When a rule below says "should," a violation is not automatically Major.

## iOS Review Heuristics

Always include:

- `bill-ios-code-review-architecture`
- `bill-ios-code-review-platform-correctness`

Add other specialists only when the changed files justify them.

| Signal in the diff | Specialist review to run |
|---|---|
| `DependencyContainer+Registrations.swift`, new `Provider` protocol, new SPM package, package-boundary crossing | `bill-ios-code-review-architecture` |
| `{Feature}Store/Action/Environment`, Combine effect chains, `.receive(on: mainScheduler)`, `cancellableId` | `bill-ios-code-review-platform-correctness` |
| GraphQL/Apollo client package, `*.graphql` operations, generated GraphQL API code, Apollo cache/field policies | `bill-ios-code-review-api-contracts` |
| `Database` package, GRDB migrations, `createTable`/schema changes, `{Statement}SQLStatementTests.swift`, deep sync | `bill-ios-code-review-persistence` |
| Background-sync engine (interceptor/chain-of-responsibility Read/Write/Permissions/Utility stages), background sync, error logging | `bill-ios-code-review-reliability` |
| Auth/session/Keychain, token storage, sensitive log content | `bill-ios-code-review-security` |
| Hot Store reducers, large sync/import loops, image/PDF/camera work (photo-gallery, PDF-editor, camera modules) | `bill-ios-code-review-performance` |
| Test files, `assertSnapshotsOf`, `{Statement}SQLStatementTests.swift`, missing tests for new views/stores | `bill-ios-code-review-testing` |
| `{Feature}View.swift`, shared UI-component/theme module, navigation (`Scene+{Feature}.swift`), snapshot baselines | `bill-ios-code-review-ui` |
| `Strings.swift`, `*.lproj` (5 languages), VoiceOver/accessibility labels | `bill-ios-code-review-ux-accessibility` |

`api-contracts` is reframed, not dropped: for a GraphQL/Apollo-consuming client the contract risk is codegen regeneration, `.graphql` changes, and cache/field-policy correctness — not request validation.

## Mixed Diffs

If different parts of the diff touch different review surfaces:

- inspect those changed areas separately
- keep the baseline specialists for the whole review
- add only the specialists needed for the relevant areas
- do not force every file through every specialist

## Specialist Selection Bounds

- Minimum 2 specialist reviews: `architecture` plus one other
- If no additional triggers match, include `bill-ios-code-review-platform-correctness` as the default second specialist
- If tests changed materially, include `bill-ios-code-review-testing`
- Maximum 10 specialist reviews

When the diff is large, high-risk, or spans multiple review surfaces, build per-specialist file lists so each selected review lane stays focused:

1. Scan each changed file's name and imports for the routing-table signals above.
2. Map each file to the specialists whose signals it matches.
3. `bill-ios-code-review-architecture` always receives all changed files.
4. Every other specialist receives only files matching its routing signals.
5. If a non-architecture specialist's scoped file list is empty, drop it from the selected set.
6. After scoping, re-check the minimum-2-specialist requirement; if only architecture remains, add `bill-ios-code-review-platform-correctness` with all changed files as the default second.

This is a lightweight file-level classification, not a full review.
