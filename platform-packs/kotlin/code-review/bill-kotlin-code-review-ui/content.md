---
name: bill-kotlin-code-review-ui
description: Review standalone Kotlin Compose Desktop, Swing or JavaFX interop, server-rendered views, and CLI or TUI interface correctness.
internal-for: bill-code-review
---

# UI Review Specialist

## Focus

- State, effects, lifecycle, rendering, thread ownership, input, resize, recovery, and cleanup

## Ignore

- Android and Compose Multiplatform behavior, which belongs to KMP; ux-accessibility and security findings belong to their specialist lanes; ignore visual taste without a user-visible failure

## Applicability

Run only when the diff contains Compose Desktop, Swing, JavaFX, server-rendered Kotlin, CLI, or TUI surfaces.

## Project-Specific Rules

### UI Review Rules

- Require Compose Desktop state that survives recomposition to use `remember` or owned observable state; recreating it can reset user input and break interaction state.
- Reject side effects launched directly from a composable body; repeated recomposition can duplicate I/O and leak concurrent work instead of using `LaunchedEffect`.
- Verify `LaunchedEffect` keys identify the actual lifecycle owner; a stale key can retain obsolete data while a broad key restarts expensive work.
- Require expensive derived values to use `derivedStateOf` only when measurements show recomposition pressure; incorrect snapshots can cause stale rendering or performance regression.
- Reject blocking I/O on the Compose application dispatcher; `runBlocking` can freeze painting and create input timeout.
- Require Swing mutations to execute on `SwingUtilities.invokeLater` or the EDT; cross-thread model changes can race and corrupt widget state.
- Verify JavaFX scene graph updates execute through `Platform.runLater`; background-thread updates can throw runtime failure and leave partial UI state.
- Require Swing or JavaFX bridges to dispose listeners and windows with the owning Compose lifecycle; retained callbacks can leak resources after close.
- Reject server-rendered Kotlin templates that derive visible state from hidden mutable singletons; concurrent requests can expose another user's data.
- Verify `kotlinx.html`, Thymeleaf, or FreeMarker conditionals render validation and recovery states; omitted errors can break form completion after invalid input.
- Require CLI commands built with `Clikt` or `kotlinx-cli` to separate parsing, state transition, and rendering; mixed partial output can report success after failure.
- Verify TUI loops handle resize, interrupted reads, redraw recovery, and terminal restoration in `finally`; a crash can leave corrupted rendering or raw terminal state.
- Require `Window` close handling to cancel owned effects; missing lifecycle cleanup can leak concurrent state and resources.
- Require `gradle test` or UI harness evidence for rendering changes; unverified toolchain behavior risks build and interaction failure.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.

Android views and Compose Multiplatform target behavior must route to the KMP UI lane; this specialist remains the Kotlin baseline for standalone surfaces.
