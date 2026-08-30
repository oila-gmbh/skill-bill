# SKILL-222 subtask 2: Goal controls and VSIX release packaging

## Scope

Extend the VS Code status extension with the same operator goal controls the
IntelliJ plugin exposes, and add a hosted CI/release path that publishes a
verifiable extension artifact.

Include:

- Detail-UI **Stop goal** and **Pause after current subtask** when the status
  snapshot is an active `feature-goal` with an issue key.
- Invocations:
  - `skill-bill goal stop <issue-key> --repo-root <canonical>`
  - `skill-bill goal pause <issue-key> --repo-root <canonical>`
- Separate process-runner instance(s) from status polling so a mutation cannot
  coalesce into a poll result; failures collapse to bounded summaries without
  stdout/stderr/paths; pause control disabled when `pause_requested` (or
  equivalent contract field) is already set.
- No Resume control.
- Hosted-only GitHub workflow (or extension of existing release wiring) that
  builds the VSIX/zip, emits a `.sha256` sidecar, and attaches assets to a
  `plugin-v*` / extension tag convention documented in the extension README —
  self-hosted runners remain off-limits for this packaging job.
- Docs: install-from-release steps parallel to IntelliJ's "Install from Disk"
  path (VS Code: Install from VSIX).

## Acceptance Criteria

1. Stop and Pause appear only for eligible active feature-goal snapshots;
   Resume is absent; pause remains disabled while a pause is already requested.
2. Mutating CLI calls use a runner distinct from status polling, never
   terminate Skill Bill processes from the extension, and never leak process
   output into the UI.
3. Hosted CI produces exactly one versioned extension artifact plus a
   checksum sidecar suitable for release attach; the job fails closed before
   publishing a partial asset set.
4. Extension README documents release install and restates that Marketplace
   publish and other workflow mutations stay deferred.

## Non-Goals

- Marketplace listing, publisher identity, or signing beyond local/CI package.
- Goal launch, resume, retry, abandon.
- Full tool window.
- Changes to IntelliJ plugin behavior (parity by contract, not by shared UI code).

## Dependency Notes

- Depends on subtask 1 (`status-extension-foundation`): status detail surface
  and CLI resolution must exist before controls and release packaging attach.

## Validation Strategy

- Unit-test control eligibility and pause-disablement from snapshot fields.
- Integration or host smoke: Stop/Pause invoke the expected argv shape against
  a fake process runner.
- Run the packaging workflow job (or local equivalent scripts) and verify
  artifact + checksum.

## Next Path

Feature complete for status + operator stop/pause parity. Follow-ups
(Marketplace, tool window, Codespaces PATH hardening) need separate issues.
