---
issue_key: SKILL-94
feature_name: release-tooling
status: Complete
spec_source: local
---

# SKILL-94 Release Tooling

## Outcome

Two related improvements that complete the release loop:

1. **`bill-release` skill** — a governed skill that automates cutting a Skill Bill release: generates a curated user-facing changelog from commits since the last tag, presents it for inline review, asks for version confirmation, then creates and pushes the annotated semver tag to trigger the GitHub Release workflow.

2. **`update-check` release notes** — when `skill-bill update-check` (CLI or MCP) reports an update is available, it now includes the GitHub release body (changelog) so the user can see what changed without leaving the terminal.

## Motivation

Currently releasing requires manually writing changelog notes and running several git commands. The `bill-release` skill packages the editorial judgment and git steps into one governed flow with a hard confirmation gate before any tag is pushed.

The update-check tool already fetches GitHub release metadata but discards the `body` field. Surfacing it closes the loop: a user sees an update is available, reads the changelog inline, and decides whether to run `skill-bill update`.

## Acceptance Criteria

1. `skills/bill-release/content.md` exists and is a valid governed skill.
2. The skill instructs the agent to find the previous stable semver tag via `git tag --sort=-version:refname`.
3. The skill categorizes commits into **New Features**, **Bug Fixes**, and **Other bug fixes and stability improvements** using editorial judgment.
4. The changelog draft is presented inline in chat for user review before any irreversible action.
5. The skill suggests a semver bump (patch/minor/major) based on RELEASING.md policy and requires user confirmation of the version.
6. The skill requires explicit user confirmation before creating the tag and again before pushing it.
7. `UpdateCheckResult` carries a `releaseNotes: String?` field.
8. `UpdateCheckService` extracts the `body` field from the GitHub Releases API response and propagates it to `releaseNotes`.
9. CLI text output (`toText()`) appends a `what's new:` block with the release body when the status is `update_available` and `releaseNotes` is non-null.
10. CLI JSON payload and MCP `update_check` response both include a `release_notes` key.
11. Absence of a release body (null `body` in the API response) results in `releaseNotes = null` with no visible change to existing output.

## Non-Goals

- The skill does not write to `CHANGELOG.md` or any file on disk.
- The skill does not auto-bump the version — the user always confirms the tag string.
- The `update-check` release notes display does not apply to `AHEAD_OF_RELEASE` or `UP_TO_DATE` statuses.
- No changes to how the runtime selects the latest release (stable-only by default).

## Constraints

- Tag push is a one-way action — the skill must never push without explicit user confirmation.
- The annotated tag message is exactly `Release vX.Y.Z` — no changelog body in the tag message itself.
- `releaseNotes` is nullable throughout; all existing tests must pass without modification.

## Files Changed

- `skills/bill-release/content.md` — new skill
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/model/UpdateCheckModels.kt` — `releaseNotes: String?` field
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/updatecheck/UpdateCheckService.kt` — extract `body`, propagate to result
- `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/system/SystemCliCommands.kt` — display in `toText()` and `toPayload()`
- `runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/core/McpRuntime.kt` — include in `updateCheck()` response
