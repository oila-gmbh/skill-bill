# SKILL-20 Narrow Built-In Packs to Kotlin and KMP

Status: In Progress

## Sources
- User briefing for SKILL-20
- Repository guidance in `AGENTS.md`
- Feature-implement workflow contract in `skills/base/bill-feature-implement/reference.md`

## Acceptance Criteria
1. The repo is framed as a governed system for managing skills, with orchestration, validators, installers, scaffolder, telemetry, and base shells as the core product.
2. The only built-in first-party reference packs kept in-repo are `kotlin` and `kmp`.
3. Non-Kotlin example packs are removed from the shipped product surface, including pack content, docs/catalog references, tests/fixtures that assume they are built-in, and installer/help surfaces that advertise them.
4. `backend-kotlin` no longer exists as a separate built-in platform pack in this repo.
5. The current governed architecture stays in place: `platform-packs/` remains the home for piloted/reference packs; this change does not move everything back into `skills/`.
6. README and repo guidance are rewritten to clearly state: this repo ships the governance system; `kotlin` and `kmp` are built-in first-party reference implementations; other stacks can be authored separately with the scaffolder.
7. Validation and tests pass after the repo is narrowed to that product story.

## Non-Goals
- No contract reset or migration of all pack content back from `platform-packs/` into `skills/`.
- No new third built-in exemplar beyond `kotlin` and `kmp`.
- No attempt to preserve non-Kotlin packs as first-party shipped examples in this repo.

## Consolidated Spec Content
The repo should be a governed system for authoring, validating, routing, installing, and evolving skills.

The core product is `orchestration/`, `skills/base/`, `skill_bill/`, validators, installer, scaffolder, telemetry, and related machinery.

Kotlin and KMP stay in-repo as built-in first-party exemplars because they are useful in production and show the system’s full depth.

Other shipped example packs should be removed from the built-in product story.

The current governed architecture should remain in place; `platform-packs/` is not being undone in this change.

Backend Kotlin should not remain as a separate built-in platform pack.

Docs and repo guidance should be rewritten around that product boundary.
