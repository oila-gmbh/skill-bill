# SKILL-77 subtask 5 - first-run handoff and end-to-end validation

## Scope

Land the user in the installed workspace when the first-run wizard finishes,
and lock the integrated feature behavior:

- Change `finishFirstRunSetup` (`SkillBillViewModel.kt:862-870`) so successful
  wizard completion opens the installed workspace via the locator + the
  `openRepo` funnel, instead of merely clearing wizard state (verified gap:
  today it does not open anything). Dismiss/failure paths stay unchanged.
- Confirm `JvmDesktopFirstRunGateway` home resolution (line 55) matches the
  locator's, so the wizard's install output is exactly the root the handoff
  opens.
- Add integration-style ViewModel tests covering the full chain: fresh
  install → wizard completes → installed session open → git provisioned →
  editor save → change appears in the changes snapshot (and, where cheap, the
  modified indicator from subtask 4).
- Reconcile the parent `spec.md` to final state (status, resolved open
  questions, corrections) as part of finishing the feature.

## Acceptance Criteria

1. `finishFirstRunSetup` on success transitions state to an open
   installed-workspace session (tree populated, `repoPathText` = installed
   root).
2. Wizard failure/dismiss paths do not open the workspace and remain
   unchanged.
3. End-to-end test: the post-wizard session supports edit, save, and git
   change tracking.
4. `(cd runtime-kotlin && ./gradlew check)` passes for the full feature.

## Non-Goals

- Wizard step/content changes.
- Telemetry or agent-selection changes.

## Dependency Notes

- Depends on subtask 2 (the handoff lands in the default-open flow) and
  subtask 3 (the freshly installed workspace must provision git during the
  handoff).
- Runs last to also lock integrated behavior including subtask 4's
  indicators where cheap.

## Validation Strategy

- jvmTest ViewModel integration tests with a fake first-run gateway + temp
  workspace.
- `(cd runtime-kotlin && ./gradlew check)`.
- Manual: wipe `~/.skill-bill`, launch the app, complete the wizard, confirm
  landing in the workspace with a provisioned git history and a working
  editor.

## Next Path

Feature complete after this subtask: reconcile the parent spec to final state
and proceed to PR.
