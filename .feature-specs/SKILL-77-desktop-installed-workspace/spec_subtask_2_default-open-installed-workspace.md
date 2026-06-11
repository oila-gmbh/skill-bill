# SKILL-77 subtask 2 - default-open installed workspace + picker coexistence

## Scope

Make the installed workspace the app's default session, keeping the clone
picker as a secondary flow:

- `SkillBillViewModel` (`feature/skillbill/state/SkillBillViewModel.kt`): on
  startup, when the locator reports an available installed workspace, open it
  through the existing `openRepo` funnel (line 462) instead of waiting on
  `repoPathText` seeded from recents (line 92).
- Trace and adjust the launch-time open trigger in `SkillBillRoute.kt`
  (candidate `LaunchedEffect` paths near lines 269/473 — UNVERIFIED exactly
  which one auto-opens today; confirm during implementation and slot the
  installed-workspace default accordingly).
- Keep `runChooseRepoDirectory` (`SkillBillRoute.kt:227`) for clones. Opening
  the installed workspace must NOT call `rememberRepoPath`; a clone's
  remembered recent path must survive installed-workspace opens.
- Add an affordance to return to the installed workspace from a clone
  session.
- The installed-workspace session must be distinguishable (e.g. path equality
  against the locator's root) where subtasks 3 and 4 need to branch on it.

## Acceptance Criteria

1. With an available installed workspace, app startup yields an open
   recognized session rooted at `~/.skill-bill` with the skill tree
   populated, no picker shown.
2. Without an installed workspace, startup behavior is unchanged
   (picker/recent-path flow).
3. The picker still opens clones; the recent path is only written for
   picker-opened repos.
4. From a clone session, the user can switch back to the installed
   workspace.
5. Editing/saving a document in the installed session writes under
   `~/.skill-bill` (exercises the existing `loadDocument`/`authoringSaver`
   paths; parent AC-2).

## Non-Goals

- Git provisioning (subtask 3).
- Baseline indicators (subtask 4).
- First-run wizard changes (subtask 5).

## Dependency Notes

- Depends on subtask 1: needs the locator to know whether/where an installed
  workspace exists and to mark the session as installed-workspace.

## Validation Strategy

- jvmTest on `SkillBillViewModel` with a fake locator + existing fake
  services (`SkillBillViewModelTest` patterns): default-open, no-workspace
  fallback, recent-path isolation, switch-back affordance, save-path
  assertion.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Run bill-feature-task on .feature-specs/SKILL-77-desktop-installed-workspace/spec_subtask_3_git-init-installed-workspace.md
