# SKILL-79 — Desktop UI: Installed Content Editor

## Status
Complete

## Intended Outcome

Strip the desktop UI of all git/repo-centric infrastructure and reshape it into a focused
editor for installed `content.md` files. The app navigates the user's installed skill tree,
lets them view and edit `content.md` files in-place, and saves back to the installation root.
No GitHub connection, no validation pipeline, no render/publish flow.

The app's mental model shifts from "developer tool for a git repo" to "settings editor for
installed skills."

## Acceptance Criteria

1. The **Changes dock tab** is removed — no staged/unstaged files, no diff viewer, no
   commit/push UI.
2. The **History dock tab** is removed — no git log, no `recentCommits` calls.
3. The **Validate** toolbar button and dock tab are removed — `ValidationGateway`,
   `ValidationSummary`, and all validation state are deleted from the ViewModel and UI.
4. The **Render** toolbar button is removed — `RenderGateway`, `RenderSummary`, and all
   render state are deleted from the ViewModel and UI.
5. The **Console** dock tab is removed (no render/validation output to surface).
6. The entire **Publish flow** is removed — commit message, PR title/body,
   `GitPublishingStatus`, publish link, `postPublishReinstall` dialog.
7. `GitGateway` and `RuntimeGitGateway` are deleted entirely.
8. `RepoFileChangeObserver` is removed or narrowed to watch the installation root only,
   with no git-repo assumption.
9. The `RepoSession` concept is replaced or narrowed to an installation-root path — the
   session carries no git working-tree state.
10. Tree navigation and the editor remain fully functional: selecting a skill loads its
    `content.md` into the editor; saves write back to the installed location.
11. The dock panel is hidden when no meaningful tabs remain.
12. The toolbar retains only actions relevant to the installed-content context (e.g. Refresh
    to reload from disk). Validate and Render buttons are removed.
13. No regressions to tree navigation, editor, file-system watching of the installation
    root, or first-run setup.

## Known Constraints

- KMP/Compose Desktop app; changes span `commonMain` (UI, ViewModel, domain interfaces) and
  `jvmMain` (gateway implementations).
- `SkillBillViewModel` begin/run/finish triplets for git, validate, and render must be fully
  deleted — not just hidden or no-op'd.
- `SkillBillFrame.kt` is ~2500 lines; removal of dock tabs and toolbar buttons must leave no
  orphaned state references or dead parameters.
- All compile-time references to deleted types must be cleaned up; the project must build
  cleanly after the change.

## Non-Goals

- No new features beyond the cleanup.
- No redesign of the tree or editor layout beyond what removal of surrounding chrome requires.
- No GitHub integration of any kind — not even a hidden or disabled path.
- No render preview feature — `content.md` is always editable as plain text; no rendered
  output panel.
- No migration tooling for users who relied on the old git-backed flow.

## Key Files

| Area | Path |
|------|------|
| ViewModel | `runtime-kotlin/runtime-desktop/feature/skillbill/src/commonMain/kotlin/skillbill/desktop/feature/skillbill/state/SkillBillViewModel.kt` |
| Route orchestrator | `runtime-kotlin/runtime-desktop/feature/skillbill/src/commonMain/kotlin/skillbill/desktop/feature/skillbill/ui/SkillBillRoute.kt` |
| Frame layout | `runtime-kotlin/runtime-desktop/feature/skillbill/src/commonMain/kotlin/skillbill/desktop/feature/skillbill/ui/SkillBillFrame.kt` |
| Domain models | `runtime-kotlin/runtime-desktop/core/domain/src/commonMain/kotlin/skillbill/desktop/core/domain/model/SkillBillModels.kt` |
| Service interfaces | `runtime-kotlin/runtime-desktop/core/domain/src/commonMain/kotlin/skillbill/desktop/core/domain/service/SkillBillServices.kt` |
| Git gateway impl | `runtime-kotlin/runtime-desktop/core/data/src/jvmMain/kotlin/skillbill/desktop/core/data/service/RuntimeGitGateway.kt` |
| Render gateway impl | `runtime-kotlin/runtime-desktop/core/data/src/jvmMain/kotlin/skillbill/desktop/core/data/service/RuntimeRenderGateway.kt` |
| Validation gateway impl | `runtime-kotlin/runtime-desktop/core/data/src/jvmMain/kotlin/skillbill/desktop/core/data/service/RuntimeValidationGateway.kt` |
| File change observer | `runtime-kotlin/runtime-desktop/feature/skillbill/src/commonMain/kotlin/skillbill/desktop/feature/skillbill/ui/RepoFileChangeObserver.kt` |

## Validation Strategy

- Project must compile cleanly with no references to deleted types.
- Run the desktop app against a real installation root: verify tree loads, `content.md`
  opens in the editor, edits save to disk.
- Confirm the dock, toolbar, and all removed UI surfaces are absent at runtime.
- Confirm no git operations are invoked (no calls to `git` subprocess or `GitGateway`).
