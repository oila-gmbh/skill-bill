---
name: bill-release
description: Cut a release for any tag-driven repo: generate a user-facing changelog from commits since the last tag, confirm with the user, then create and push an annotated semver tag. Requires bump:patch, bump:minor, or bump:major. Use when user mentions cut a release, new release, create release tag, or bump version.
---

# Release Skill Content

## Overview

This skill produces a curated user-facing changelog from commits since the last release, presents it for review, then creates and pushes an annotated semver tag. Pushing the tag triggers whatever CI/CD or release workflow the repo has wired to tag events.

## Intake

Require a bump-type argument: `bump:patch`, `bump:minor`, or `bump:major`. If it is absent or not one of those three values, stop and ask the user which bump type to use before proceeding. Do not guess or suggest a default.

## Steps

### 1. Pre-flight checks

Confirm the working tree is clean and the current branch is up to date with its remote:

```bash
git status --short
git fetch origin
git log HEAD..@{u} --oneline
```

If uncommitted changes exist or commits remain to pull, surface them and ask the user how to proceed before continuing.

### 2. Find the previous release tag

```bash
git tag --sort=-version:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -1
```

If no stable tag exists yet, use the root commit as the base (first commit SHA).

### 3. Gather commits since the previous tag

```bash
git log <prev-tag>..HEAD --oneline
```

Also pull richer context for merge commits:

```bash
git log <prev-tag>..HEAD --merges --format="PR: %s%n%b"
```

### 4. Generate the changelog draft

Categorize the commits using editorial judgment. If the repo has a RELEASING.md or CHANGELOG.md, read it first for project-specific versioning policy. Use commit messages to determine what is user-facing, important, or internal.

**Categories:**

- **New Features** — new user-visible capabilities (new skills, new commands, new runtime modes, new UX flows). Each gets its own bullet.
- **Bug Fixes** — notable, user-impacting fixes worth naming individually. Threshold: would a user notice or care? If yes, name it. Examples: broken command behavior, incorrect output, crashes, data loss, wrong state handling.
- **Other bug fixes and stability improvements** — one grouped bullet covering everything else (internal refactors, minor telemetry tweaks, test-only changes, doc cleanups, minor infra changes, dependency bumps). Do **not** itemize these.

Format:

```markdown
## What's New in vX.Y.Z

### New Features
- <Feature name>: <one-sentence description of the user-visible change>

### Bug Fixes
- <Fix name>: <one-sentence description of what was broken and is now fixed>

### Other
- Other bug fixes and stability improvements.
```

Omit any section that has no entries. If there are only minor changes, the entire changelog may collapse to the "Other" bullet alone.

Do not include:
- Internal implementation details
- Test-only changes
- Commit SHAs or PR numbers (unless the user prefers them)
- Passive voice or marketing language

### 5. Present the changelog inline

Show the draft changelog to the user. Ask for any edits or corrections before proceeding.

### 6. Determine the next version

Apply the required bump type from intake to compute the next version. Show the resulting version string (e.g. `v0.4.0`) and ask the user to confirm or override before proceeding.

### 7. Create the annotated tag

Ask the user to confirm before creating the tag: "Ready to create tag vX.Y.Z — shall I proceed?" Wait for an explicit yes before running the tag command.

```bash
git tag -a vX.Y.Z -m "Release vX.Y.Z"
```

### 8. Push the tag

Confirm with the user before pushing (this is irreversible and triggers the release workflow):

```bash
git push origin vX.Y.Z
```

After pushing, remind the user to watch any CI/CD workflow wired to the tag (e.g. a GitHub Actions release workflow) for build and publish status.

## Rules

- Never push the tag without explicit user confirmation.
- Never create the tag if the working tree has uncommitted changes.
- Never skip the user review of the changelog draft.
- If `git fetch` fails due to network issues, warn the user but let them decide whether to continue.
- Prerelease tags (`v0.5.0-rc.1`) are valid — the workflow publishes them as GitHub prereleases.
- The annotated tag message should be exactly `Release vX.Y.Z` — no changelog body in the tag message itself.
