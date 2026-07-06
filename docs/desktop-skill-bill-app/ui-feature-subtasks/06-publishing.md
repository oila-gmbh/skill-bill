# Subtask 06: Commit, Push, and Fork Publishing

Status: Complete

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-task-specs.md`. It depends on
Subtask 05 for real Git status/diff/staging and should use Subtask 02 validation
before commit.

## UI Entry Points

- Branch label in the top toolbar.
- Bottom status bar branch/source-control state.
- Commit message input and Commit action in the Changes dock.
- Push action and target summary in the Changes dock.
- Compare URL or PR handoff affordance.

## Goal

Complete the source-control publishing loop for Skill Bill changes while
blocking accidental pushes to the canonical repository.

## Scope

- Show current branch, remotes, ahead/behind state, and push target.
- Add commit message input and Commit action.
- Run Skill Bill validation before commit by default.
- Require explicit confirmation to commit with failed validation.
- Push current branch to `origin`.
- Detect likely canonical-vs-fork remote risk.
- Require explicit confirmation before maintainer-style pushes to canonical
  remotes.
- Show or open a GitHub compare URL when `origin` and `upstream` are GitHub
  remotes.
- Surface exact Git credential and remote errors.

## Runtime and Service Requirements

- Use `GitGateway` for all Git operations.
- Use validation gateway before commit.
- Do not silently rewrite remotes.
- Remote configuration changes require explicit confirmation and are not part of
  the default commit/push path.

## Acceptance Criteria

- Commit is disabled until staged changes and a message are present.
- Validation runs before commit.
- Failed validation requires explicit confirmation to commit anyway.
- Commit success refreshes Git status and history.
- Push target is shown before push.
- Push to a likely canonical remote is blocked by default.
- Push success refreshes ahead/behind state.
- Compare URL is generated when remote topology supports it.
- Git errors are visible and leave the repo state unchanged except for Git's own
  failed command effects.

## Validation

Use a temporary repo with local bare remotes and fork-like remote names:

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:core:data:jvmTest :runtime-desktop:feature:skillbill:jvmTest
```

Manual smoke:

1. Open a temporary fork-like repo.
2. Stage an authored-file change.
3. Commit with a message.
4. Push to a local bare `origin`.
5. Confirm a compare URL is shown when remotes are GitHub-shaped.

## Non-Goals

- Force push.
- Rebase or merge conflict resolution.
- SSH key or credential management.
- GitHub API-authenticated PR creation.
