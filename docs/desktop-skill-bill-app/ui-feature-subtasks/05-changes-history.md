# Subtask 05: Source-Control Changes and History

Status: Complete

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-task-specs.md`. It should land
after save/scaffold/render paths start changing files, and before publishing.

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
  where possible.
- No destructive Git commands are allowed.
- Git errors must be surfaced without changing app state.

## Acceptance Criteria

- The Changes tab reflects `git status --short` for the selected repo.
- Selecting a changed file shows its diff.
- Staging and unstaging selected files updates status.
- Generated artifacts are labeled and cannot be opened in editable mode.
- History tab is empty with a clear message when no Git repo is open.
- Recent commits load for a valid Git repo.
- Selecting a tree item can filter or highlight path-related commits.
- Copy path and copy hash work without mutating repo state.

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

## Non-Goals

- Commit and push.
- Rebase, merge, conflict resolution, or force push.
- Full branch graph visualization.
- GitHub API integration.
