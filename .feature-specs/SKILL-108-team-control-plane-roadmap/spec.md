# SKILL-108: Team Control Plane Roadmap

**Status:** Decomposed - parent overview
**Issue:** SKILL-108
**Branch:** `feat/SKILL-108-team-control-plane-roadmap` (base: `main`)
**Execution model:** `same_branch_commit_per_subtask` - one commit per completed subtask on the feature branch.
**Source roadmap:** [docs/team-control-plane-roadmap.md](../../docs/team-control-plane-roadmap.md)

## Overview

Implement the Skill Bill Teams roadmap as a governed, local-first control plane
for standardizing, tuning, distributing, and measuring AI-agent workflows across
engineering teams.

The roadmap has four product phases:

1. Team Bundle: publish and sync a validated team setup locally.
2. Admin Editing: let admins modify and publish team-owned source safely.
3. Telemetry Loop: measure outcomes by bundle, skill, pack, team, and channel.
4. Hosted Org Control Plane: add hosted distribution, permissions, and audit.

This spec decomposes those phases into implementation lanes that can land
independently while preserving the existing Skill Bill contracts:

- authored governed skill source remains `content.md`
- platform behavior remains manifest-driven through `platform-packs/<slug>/`
- generated wrappers, support pointers, provider-native agent files, and install
  staging output stay out of committed source
- install, validation, scaffold, telemetry, and desktop surfaces reuse runtime
  contracts rather than reimplementing governance in UI code
- missing manifests, schema drift, checksum drift, invalid bundles, and failed
  validation fail loudly with typed errors

## Current State

The repo already has several foundations this roadmap should reuse:

- governed skill and platform-pack source shape
- manifest-driven platform packs, add-ons, and internal sidecars
- strict runtime contract schemas under `orchestration/contracts/`
- Kotlin install plan/apply, staging, validation, reconcile, and rollback
  primitives
- desktop local authoring, validation, scaffolding, first-run install, installed
  workspace, and generated-artifact read-only protections
- workflow telemetry levels (`off`, `anonymous`, `full`), local outbox, remote
  sync, and remote stats proxy contracts for workflow metrics
- external add-on sources for private or team-specific add-ons

The repo does not currently have:

- `skill-bill team export`
- `skill-bill team sync`
- a team bundle runtime contract
- a local or hosted team bundle registry
- bundle-version attribution in telemetry
- desktop bundle publish/sync/rollback surfaces
- hosted organizations, teams, users, memberships, audit logs, or policy-driven
  sync

Important baseline decision: desktop Git publishing has been retired and is not
part of this roadmap. Team bundle publish means publishing a governed Skill Bill
bundle to a local registry, file destination, or hosted bundle registry. It must
not mean committing, pushing, opening compare URLs, or creating pull requests
from the desktop app.

## Subtasks

| # | Subtask | Spec | Depends on |
|---|---------|------|------------|
| 1 | Team bundle contract foundation | [spec_subtask_1_team-bundle-contract-foundation.md](spec_subtask_1_team-bundle-contract-foundation.md) | - |
| 2 | CLI export and local registry | [spec_subtask_2_cli-export-and-local-registry.md](spec_subtask_2_cli-export-and-local-registry.md) | 1 |
| 3 | CLI sync, install integration, and rollback | [spec_subtask_3_cli-sync-install-rollback.md](spec_subtask_3_cli-sync-install-rollback.md) | 1, 2 |
| 4 | Desktop admin bundle authoring and publish | [spec_subtask_4_desktop-admin-bundle-authoring.md](spec_subtask_4_desktop-admin-bundle-authoring.md) | 1, 2, 3 |
| 5 | Local roles and proposal workflow | [spec_subtask_5_local-roles-and-proposals.md](spec_subtask_5_local-roles-and-proposals.md) | 1, 4 |
| 6 | Team telemetry attribution and privacy | [spec_subtask_6_team-telemetry-attribution-privacy.md](spec_subtask_6_team-telemetry-attribution-privacy.md) | 1, 2, 3 |
| 7 | Telemetry analysis and tuning surfaces | [spec_subtask_7_telemetry-analysis-tuning-surfaces.md](spec_subtask_7_telemetry-analysis-tuning-surfaces.md) | 6 |
| 8 | Hosted org, registry, and audit contracts | [spec_subtask_8_hosted-org-registry-audit-contracts.md](spec_subtask_8_hosted-org-registry-audit-contracts.md) | 1, 5, 6 |
| 9 | Hosted sync policy and launch readiness | [spec_subtask_9_hosted-sync-policy-launch-readiness.md](spec_subtask_9_hosted-sync-policy-launch-readiness.md) | 3, 7, 8 |

## Acceptance Criteria

1. The repo has a strict team-bundle runtime contract schema with a Kotlin
   contract-version constant, parity test, typed schema error, and loud-fail
   parse seams.
2. `skill-bill team export` creates deterministic, checksum-verifiable bundles
   containing only governed Skill Bill source and manifest metadata. Generated
   runtime/install artifacts are excluded.
3. `skill-bill team sync` verifies checksum, contract version, source shape,
   selected channel, and validation before installing through the existing
   render/install/staging path, and preserves rollback state for the previous
   bundle.
4. A developer can run a full Phase 1 pilot: one admin exports a bundle, three
   local installs sync it, run `/bill-feature`, `/bill-code-review`, and
   `/bill-code-check`, then rollback without maintainer handholding.
5. Desktop admin surfaces allow local team-owned source editing, validation,
   preview, bundle publish, sync, and rollback without introducing Git commit,
   push, compare-URL, or pull-request workflows.
6. Local role and proposal behavior represents Team Admin, Maintainer, Member,
   and Viewer semantics clearly for local-first operation, with hard local
   restrictions only where the runtime can enforce them honestly.
7. Workflow telemetry carries team, channel, and bundle-version attribution
   without sending code, prompt text, file contents, or finding prose at
   `anonymous` level.
8. Admin-facing stats can compare workflow outcomes before and after bundle
   versions by skill, platform pack, channel, team, and workflow type, while
   self-hosted telemetry proxy support remains intact.
9. Hosted org control-plane contracts cover organizations, teams, users,
   memberships, roles, hosted bundle registry channels, sync policy, telemetry
   settings, and audit logs without hosting customer code or requiring one
   specific coding agent.
10. Documentation and launch-readiness materials describe the local-first path,
   the telemetry privacy model, the no-Git-publishing desktop boundary, and the
   discovery checklist that must be answered before hosted rollout.
11. The final branch passes `skill-bill validate`,
   `scripts/validate_agent_configs`, `npx --yes agnix --strict .`, and
   `(cd runtime-kotlin && ./gradlew check)`.

## Non-goals

- No automatic skill mutation based on telemetry.
- No hidden learning writes. Admins must approve authored skill or pack changes.
- No hosted customer code storage.
- No replacement for GitHub, GitLab, or source hosting.
- No requirement that teams use a specific coding agent.
- No desktop Git commit, push, fork, compare-URL, or pull-request publishing.
- No multi-org billing implementation before hosted org contracts are in place.
- No cross-team policy inheritance before the hosted contract explicitly defines
  it.

## Validation Strategy

Each subtask must run the narrow tests for its touched modules. Subtasks that
touch runtime contracts, install, CLI, desktop, telemetry, or hosted-client
surfaces must also run the relevant slice of:

```bash
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
(cd runtime-kotlin && ./gradlew check)
```

Phase 1 needs a manual smoke with a local exported bundle, at least one scratch
install, sync, workflow invocation, and rollback. Later subtasks must preserve
that smoke.

## First Subtask

Run subtask 1 first. The bundle schema and typed domain model are the base
contract for export, sync, desktop publish, telemetry attribution, and hosted
registry work.
