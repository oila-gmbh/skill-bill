# Skill Bill Teams Roadmap

Status: Product direction

## Positioning

Skill Bill Teams is the control plane for standardizing, tuning, distributing,
and measuring AI-agent workflows across an engineering team.

The product promise is:

> Admins define how AI agents should work; developers get the same governed
> setup; telemetry shows whether the setup is helping; admins iterate without
> each developer hand-editing local skills.

This is not a separate coding agent. Skill Bill Teams sits above the agents a
team already uses and makes their behavior repeatable: governed skills,
platform packs, validation, versioned distribution, permissions, and usage
feedback.

The wedge is enablement as much as governance. In most teams the developers
getting the least from the AI seats the company already pays for are not the
enthusiasts — they are the majority for whom raw agents produced inconsistent
results and who fell back to using AI as a question-answering tool. A governed,
process-backed setup is what converts those developers into shipping real
features with agents; Skill Bill's earliest adopters followed exactly that
pattern, alongside heavy agent users who kept the setup for durable resume,
per-repo memory, and tuned packs. Skill Bill Teams sells that conversion first;
governance, distribution, and telemetry are how a team sustains and measures
it.

## Product Principles

- Start local-first. Prove that an admin-published setup can move cleanly to
  multiple developers before building a hosted service.
- Keep the runtime contract authoritative. Team features publish and sync
  governed Skill Bill source; they must not bypass `content.md`, platform
  manifests, generated-output boundaries, validation, or install staging.
- Make every rollout reversible. Team changes need versioning, validation,
  preview, publish, and rollback.
- Treat telemetry as evidence, not surveillance. Anonymous aggregate telemetry
  is the default; code, prompt text, file contents, and finding descriptions
  stay out unless an organization explicitly opts in.
- Keep admin power scoped. Admins can tune skills and packs for their team, but
  normal members should get a stable use-only setup.

## Phase 1: Team Bundle

Goal: one maintainer can publish a validated team setup, and developers can
sync that setup reliably.

Core capabilities:

- `skill-bill team export` creates a versioned bundle containing governed
  skills, platform packs, add-ons, overrides, and manifest metadata.
- `skill-bill team sync` installs a bundle into the local Skill Bill workspace
  after checksum and validation checks pass.
- Bundle metadata includes version, source repo/ref, created time, author,
  contract version, content hash, and selected channel.
- Local sync preserves rollback state so a developer can return to the previous
  bundle if validation or install fails.
- Bundle install reuses the existing render/install/validate path instead of
  copying generated agent files directly.

Non-goals:

- No hosted account system.
- No remote permissions model.
- No admin dashboard beyond CLI or existing desktop primitives.

Success signal:

- A team admin can publish one bundle and at least three developers can sync it,
  run `/bill-feature`, `/bill-code-review`, and `/bill-code-check`, then roll
  back without maintainer handholding.

## Phase 2: Admin Editing

Goal: admins can safely modify team-owned skills and platform packs without
dropping to raw repository editing for every change.

Core capabilities:

- Desktop surfaces for editing authored `content.md`, platform pack manifests,
  add-ons, and team overrides.
- Diff preview before publish.
- Validation before publish, including `skill-bill validate` and
  `scripts/validate_agent_configs` equivalent checks.
- Bundle publish action from the desktop app.
- Rollback to a previous bundle version.
- Optional proposal flow where maintainers can edit but only admins can publish.

Role model for this phase:

| Role | Scope |
| --- | --- |
| Team Admin | Edit, validate, publish, and roll back team bundles |
| Maintainer | Propose/edit team skills and packs, but cannot publish |
| Member | Sync and use the published setup |
| Viewer | Read docs, bundle metadata, and validation status |

Non-goals:

- No multi-org billing.
- No cross-team policy inheritance.
- No hosted telemetry dashboard yet.

Success signal:

- A non-core maintainer can adjust a pack or skill, validate it, publish it to a
  test channel, and have another developer sync it without touching Git manually.

## Phase 3: Telemetry Loop

Goal: admins can see whether a skill or platform-pack version is improving team
outcomes and tune it based on evidence.

Core capabilities:

- Usage by skill, platform pack, team, channel, and bundle version.
- Review finding accepted/rejected rates by routed skill and version.
- Quality-check pass/fail loops and iteration counts.
- Feature-task completion, abandonment, retry, and duration metrics.
- Before/after comparison across bundle versions.
- Privacy tiers aligned with existing telemetry levels:
  - `off`: no events
  - `anonymous`: aggregate usage and outcomes, no code or prose content
  - `full`: opt-in detailed telemetry for organizations that explicitly allow it
- Self-hosted telemetry proxy remains supported.

Non-goals:

- No automatic skill mutation based on telemetry.
- No hidden learning writes. Admins must approve skill or pack changes.

Success signal:

- An admin can identify one weak review/check workflow, tune it, publish a new
  bundle, and compare outcome metrics against the previous version.

## Phase 4: Hosted Org Control Plane

Goal: support multiple teams under one organization with hosted distribution,
permissions, and telemetry.

Core capabilities:

- Organizations, teams, users, and memberships.
- Roles:
  - Org Owner: billing, org policy, org-wide telemetry, team creation
  - Team Admin: edit and publish bundles for one team
  - Maintainer: propose or edit team-owned packs and skills
  - Member: sync and use approved bundles
  - Viewer: read metadata and dashboards
- Hosted bundle registry with stable, beta, and development channels.
- Policy-controlled auto-sync or prompted sync.
- Audit log for publish, rollback, permission, and telemetry setting changes.
- Optional customer-managed telemetry proxy for sensitive environments.

Non-goals:

- Do not replace GitHub, GitLab, or source hosting.
- Do not host customer code.
- Do not require teams to use one specific coding agent.

Success signal:

- One organization can run multiple teams with different platform packs, publish
  controlled bundle versions, and make adoption/tuning decisions from telemetry.

## Commercial Wedge

The team product should sell governance and distribution, not raw model access.

Likely paid value:

- shared team setup
- controlled publishing and rollback
- team and org permissions
- telemetry dashboards
- private platform packs and add-ons
- support for onboarding and pack tuning
- self-hosted or privacy-restricted telemetry options

Individual and noncommercial use can remain free while team/company use requires
a commercial license.

## Discovery Checklist

Before building the hosted control plane, collect proof from real users:

- Which commands become habitual?
- Which team-specific changes do admins want first?
- How often do users need rollback?
- Which telemetry would change an admin decision?
- Does a bundle sync reduce onboarding friction?
- Do teams tune bundled packs, or mostly add overrides?
- What privacy setting is acceptable by default?

The hosted product should follow the answers to those questions, not precede
them.
