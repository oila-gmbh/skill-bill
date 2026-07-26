# SKILL-44 — Desktop Publish and PR Creation

Status: Complete

## Sources

- Existing publishing baseline: `.feature-specs/SKILL-44-publishing/spec.md`
- Desktop publishing subtask: `docs/desktop-skill-bill-app/ui-feature-subtasks/06-publishing.md`
- Parent overview: `docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`

## Feature Size / Rollout

- Size: MEDIUM
- Rollout / feature flag: not required.
- Depends on SKILL-44 source-control changes/history and existing commit/push publishing support.

## Goal

Add a desktop `Publish` flow that lets a user review local Skill Bill changes
from the perspective of governed artifacts, run preflight checks, create an
intentional commit, push the current branch, and create or open a pull request
without leaving the app.

The surface should not read as a raw Git client. Git remains the execution
mechanism, but the user-facing review groups changes by Skill Bill concepts such
as skills, platform packs, add-ons, native agents, orchestration, runtime
support, docs, tests, and generated output. The primary action is `Publish`;
when pressed, the app runs the safe publish sequence and creates or opens the PR
when all required gates pass.

## User Problem

Users can scaffold or edit platform packs in the desktop app, but there is no
single UI flow that explains those changes in Skill Bill terms and turns them
into a reviewable pull request. The app should guide the user through the same
safe publish sequence an engineer would run manually: inspect governed changes,
validate, commit, push, and hand off for review.

## UI Entry Points

- Bottom change/status panel when the repo has publishable Skill Bill changes.
- Changes dock action row with a single `Publish` entry point.
- Optional toolbar action when a recognized Git repository is open.
- Existing compare URL surface after push.

## UX Contract

- The primary entry point is `Publish`.
- The dialog title is `Publish Changes`.
- The primary action remains `Publish`; inline status/progress explains whether
  the app is validating, committing, pushing, creating a PR, opening an existing
  PR, or falling back to a compare URL.
- The dialog shows changed Skill Bill artifacts before any write operation,
  with file paths available as supporting detail.
- The dialog defaults to including relevant source changes, but never silently
  commits all dirty files without showing the list.
- Generated runtime output is excluded from default staging and labeled as
  generated/read-only when present.
- Failed preflight checks block the final action until the user explicitly
  chooses an override where policy allows it.

## Scope

- Add a guarded publish dialog from the desktop change/status surface.
- Show changed Skill Bill artifacts grouped by governed concept: skills,
  platform packs, add-ons, native agents, orchestration, runtime support, docs,
  tests, and generated/excluded output.
- Keep current branch, remotes, upstream/fork relationship, ahead/behind state,
  staged state, and push target visible as secondary technical details.
- Let the user provide or edit:
  - commit message,
  - PR title,
  - PR body,
  - draft-vs-ready PR mode.
- Run preflight checks before commit/push/PR creation after the user presses
  `Publish`.
- Commit the selected/staged changes.
- Push the current branch to the configured fork/branch remote.
- Detect whether a PR already exists for the branch where supported.
- Create a draft PR when supported by available tooling.
- Fall back to opening the compare URL when authenticated PR creation is not
  available.
- Refresh Git status, history, validation state, and compare/PR state after
  each successful step.

## Preflight Checks

The default preflight is:

1. `skill-bill validate`
2. `scripts/validate_agent_configs`

The dialog should expose a stronger optional check:

```bash
cd runtime-kotlin
./gradlew check
```

The full Gradle check may be opt-in because it is slower, but when selected its
result participates in the same pass/fail gate.

## Runtime and Service Requirements

- Use `GitGateway` for local Git status, staging, commit, branch, remote, and
  push operations.
- Reuse existing validation gateways for Skill Bill validation where possible.
- Keep PR-provider operations behind a new gateway/interface rather than
  coupling desktop UI directly to `gh`, GitHub API calls, or connector details.
- The PR gateway should support:
  - capability detection,
  - existing PR lookup for current branch,
  - draft PR creation,
  - opening or returning a PR URL,
  - clear unavailable/authentication/error states.
- Do not rewrite remotes automatically.
- Do not force push.
- Do not commit generated `SKILL.md`, generated pointer files, provider-native
  native-agent output, install cache output, or desktop build output unless the
  user explicitly stages them outside the default flow.

## Acceptance Criteria

1. `Publish` is disabled when no recognized Git repo is open.
2. `Publish` is disabled when there are no local changes and no unpushed commit.
3. Opening `Publish` shows changed Skill Bill artifacts grouped by governed
   concept, with staged/unstaged/untracked file status visible as supporting
   detail.
4. Generated artifacts are excluded from default publish selection and clearly
   labeled as generated/read-only.
5. The user must provide a non-blank commit message before creating a commit.
6. Preflight checks run before commit when local changes need committing.
7. Failed preflight checks block commit unless the user explicitly confirms an
   allowed override.
8. Commit writes only selected/staged files and refreshes Git status/history.
9. Push target is shown before push as secondary technical detail.
10. Push to a likely canonical remote remains blocked by default, reusing the
    existing publishing safety rules.
11. Push success refreshes ahead/behind and compare/PR state.
12. When an existing PR is detected for the branch, `Publish` opens that PR
    instead of creating a duplicate.
13. When PR creation is available, `Publish` creates a draft PR by default and
    shows the resulting URL.
14. When PR creation is unavailable but a compare URL can be generated,
    `Publish` offers or opens the compare URL instead of failing the whole
    publish path.
15. Auth, network, remote, and provider errors are visible and leave repo state
    unchanged except for completed earlier Git steps.
16. The dialog never silently commits all dirty files without showing the exact
    governed change groups and file list.

## Validation

Automated tests:

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:core:data:jvmTest :runtime-desktop:feature:skillbill:jvmTest
```

Use fake/local gateways for PR-provider behavior:

- no provider capability,
- existing PR found,
- create draft PR success,
- auth failure,
- network/API failure.

Manual smoke:

1. Open a fork-like Skill Bill repo with a new platform pack change.
2. Open `Publish`.
3. Confirm generated/runtime output is excluded.
4. Run default preflight.
5. Commit selected changes.
6. Push to a fork remote.
7. Create or open a draft PR.
8. Confirm Git status/history and PR URL refresh after completion.

## Non-Goals

- Force push.
- Rebase, merge, or conflict resolution.
- Remote rewriting or fork creation.
- Credential setup or SSH key management.
- Publishing release artifacts.
- Editing generated runtime outputs.
