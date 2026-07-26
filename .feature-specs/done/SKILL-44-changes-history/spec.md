# SKILL-44 — Source-Control Changes and History (Subtask 05)

Status: Complete

## Sources

- Primary subtask spec: `docs/desktop-skill-bill-app/ui-feature-subtasks/05-changes-history.md`
- Parent overview: `docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`
- Sibling subtasks (cross-invariants only): `01-state-repo-tree.md`, `02-validation-workbench.md`, `03-authored-content-editor.md`, `04-render-console.md`

## Feature size / rollout

- Size: MEDIUM
- Rollout / feature flag: not required.

## Acceptance Criteria

1. The Changes tab reflects `git status --short` for the selected repo.
2. Selecting a changed file shows its diff.
3. Staging and unstaging selected files updates status.
4. Generated artifacts are labeled and cannot be opened in editable mode.
5. History tab is empty with a clear message when no Git repo is open.
6. Recent commits load for a valid Git repo.
7. Selecting a tree item can filter or highlight path-related commits.
8. Copy path and copy hash work without mutating repo state.
9. Changed files are grouped (staged/unstaged/untracked/generated).
10. Git status refreshes after save, scaffold, render, validation, manual refresh, stage, and unstage.
11. Git errors are surfaced without changing app state.

## Non-Goals

- Commit and push.
- Rebase, merge, conflict resolution, or force push.
- Full branch graph visualization.
- GitHub API integration.
- Destructive Git commands.

## UI Entry Points

- Bottom dock `Changes` tab.
- Bottom dock `History` tab.
- Branch/source-control label in the toolbar.
- Bottom status bar branch and repo items.
- Future stage/unstage controls inside the Changes dock.

## Goal

Show local repo changes, diffs, and recent history so users can understand what
editing, scaffolding, rendering, or validation changed before commit.

## Scope

- Add a real `GitGateway` implementation for status, diff, staging, unstaging,
  and log/history.
- Show changed files grouped by staged, unstaged, untracked, and generated.
- Show selected-file diff.
- Mark generated artifacts and generated pointer files distinctly.
- Add stage and unstage controls.
- Refresh Git status after save, scaffold, render, validation, manual refresh,
  stage, and unstage.
- Replace static history rows with recent commits for the selected repo.
- Filter or annotate history rows that touched the selected authored path.
- Allow copying file paths and commit hashes.

## Runtime and Service Requirements

- All Git command execution belongs behind `GitGateway`.
- Generated-file classification should reuse shared generated-artifact discovery
  where possible (`skillbill.scaffold.discoverGeneratedArtifactFiles`).
- No destructive Git commands are allowed.
- Git errors must be surfaced without changing app state.

## Validation

Use a temporary repo with a local bare remote:

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:core:data:jvmTest :runtime-desktop:feature:skillbill:jvmTest
```

Manual smoke:

1. Open a temporary repo copy.
2. Edit and save an authored file.
3. Confirm the changed file appears.
4. View the diff.
5. Stage and unstage the file.
6. Confirm recent history rows load.
