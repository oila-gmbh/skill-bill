# Iteration 05: Git Fork Publishing

Status: Draft

## Parent Spec Context

This iteration belongs to the desktop Skill Bill app spec in
`docs/desktop-skill-bill-app/README.md`. The Skill Bill app is an optional repo-based
desktop app shipped from this project. It must use existing Skill Bill runtime
services or CLI-equivalent adapters for governed behavior, expose authored
source only, and avoid duplicating scaffold, manifest, validation, routing, or
native-agent render rules.

For this iteration, Git support exists only to publish Skill Bill repo changes.
The expected publishing setup is `origin` as the user's fork and `upstream` as
the canonical Skill Bill repository. The app must not silently rewrite remotes
or push to the canonical repo by default.

When this iteration is implemented, update the completion checklist in
`docs/desktop-skill-bill-app/README.md`.

## Goal

Add a focused Git workflow for reviewing, committing, and pushing Skill Bill changes to a user's fork.

## User Value

A user can complete the authoring loop from edit to pushed branch without leaving the Skill Bill app.

## Scope

- Detect Git repository state for the selected repo.
- Show current branch, remotes, dirty files, staged files, and ahead/behind state.
- Show file diffs.
- Stage and unstage selected files.
- Commit with a user-provided message.
- Push current branch to `origin`.
- Detect whether `origin` appears to be a user fork.
- Support configuring `upstream` and `origin` with explicit confirmation.
- Generate a GitHub compare URL for opening a PR when possible.

## Fork Model

Expected publishing setup:

- `origin` is the user's fork and is writable.
- `upstream` is the canonical Skill Bill repo.
- feature branches push to `origin`.
- pull requests target `upstream`.

If `origin` points to the canonical repo, the push action should be blocked unless the user explicitly confirms a maintainer workflow.

## GitGateway

Start with the local `git` executable behind a narrow interface:

- `status(repo)`
- `branches(repo)`
- `remotes(repo)`
- `diff(repo, file)`
- `stage(repo, files)`
- `unstage(repo, files)`
- `commit(repo, message)`
- `push(repo, remote, branch)`
- `setRemote(repo, name, url)`

The UI should not shell out directly. All command execution belongs behind `GitGateway`.

## Safety Rules

- Do not run destructive Git commands.
- Do not rewrite remotes silently.
- Do not commit without showing changed files.
- Do not push without showing target remote and branch.
- Run Skill Bill validation before commit by default.
- Warn when validation fails and require explicit confirmation to commit anyway.

## Out of Scope

- Full Git history browser.
- Rebase and merge conflict resolution.
- Force push.
- GitHub API-authenticated PR creation.
- SSH key or credential management beyond surfacing Git errors.

## Acceptance Criteria

- Git status updates after save, scaffold, stage, commit, and push.
- User can stage selected authored files.
- User can commit staged files.
- Push goes to the configured fork remote.
- App blocks accidental push to canonical remote by default.
- Compare URL opens or is shown when `origin` and `upstream` are GitHub remotes.

## Validation

Automated smoke should use a temporary repo with a local bare remote:

```bash
cd runtime-kotlin
./gradlew :runtime-desktop:test
./gradlew check
```

Manual smoke:

1. Clone a temporary fork-like copy.
2. Edit a skill.
3. Validate.
4. Stage and commit.
5. Push to a test remote.
6. Confirm the pushed branch contains only intended changes.

## Risks

- Remote fork detection is heuristic without GitHub API auth. Treat uncertain remotes as requiring user confirmation.
- Git credentials can fail outside the app's control. Surface the exact Git error and leave the repo unchanged.
