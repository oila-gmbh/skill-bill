# Add-On System For Governed Stack Depth

Issue key: SKILL-12
Date: 2026-04-15
Status: Complete
Sources:
- Working design discussion in this repo
- Google Android official skills repository: https://github.com/android/skills

## Summary

Introduce a governed add-on system for `skill-bill` so stack-owned framework and runtime depth can be modeled without expanding the top-level package taxonomy. The first pilot is `kmp` with Android and Compose add-ons, using Google's official `android/skills` repository as a reference base for Android-specific guidance.

## Acceptance Criteria

1. Introduce an explicit governed add-on model under `skills/<stack>/addons/`.
2. Keep dominant-stack routing as the primary model; add-ons apply only after stack routing.
3. Prevent add-ons from becoming top-level packages or user-facing commands by default.
4. Define add-on naming, ownership, detection, reporting, and testing rules in repo governance.
5. Pilot the add-on system with `kmp` Android and Compose add-ons.
6. Use the Google `android/skills` repository as reference material for the Android add-on pilot, but adapt only transferable Android and Compose guidance, not migration or upgrade workflows.
7. Update validators, contracts, orchestration references, and tests so add-ons are governed rather than ad hoc.

## Non-Goals

- Creating a new top-level `android` package
- Creating `bill-android-*` routed skills
- Promoting framework names like Laravel, Eloquent, Spring, or Ktor to top-level packages
- Importing Google's `android/skills` repository verbatim
- Making add-ons user-facing commands by default
- Using migration playbooks, AGP upgrades, Play Billing upgrades, or other Android-only upgrade workflows as direct pilot content

## Problem Statement

`skill-bill` already has a strong governed model for top-level stacks such as `kmp`, `php`, `go`, and `backend-kotlin`. What it lacks is a clean second layer for "same stack, deeper framework or runtime specialization."

Examples:

- `kmp` plus Android and Compose depth
- `php` plus Laravel
- `php` plus Eloquent
- `backend-kotlin` plus Spring
- `backend-kotlin` plus Ktor

These are usually not true peer platforms. They are conditional overlays on top of a dominant stack. Modeling them as new packages would create taxonomy sprawl. Add-ons provide a governed second layer without breaking stable base routing.

## Core Design

Routing remains two-stage:

1. Detect the dominant stack.
2. Route to the canonical stack skill.
3. Inside that stack skill, detect and apply zero or more governed add-ons.

Users still interact with stable entry points:

- `bill-code-review`
- `bill-feature-implement`
- `bill-feature-implement-agentic`
- `bill-quality-check`

Those still route to canonical stack skills such as:

- `bill-kmp-code-review`
- `bill-php-code-review`
- `bill-backend-kotlin-code-review`

Add-ons are loaded behind the owning stack, not as first-class routed commands.

## Add-On Definition

An add-on is a governed supporting module owned by a stack package. It is not a top-level package and not a user-facing command by default.

Rules:

- Add-ons live only under `skills/<stack>/addons/`.
- Add-ons are loaded only after the parent stack is selected.
- Add-ons refine base stack behavior; they do not silently replace it.
- Add-ons must be reported when applied.
- Add-ons must have explicit detection rules and test coverage.

## Filesystem Shape

Initial target shape:

- `skills/kmp/addons/android-compose-implementation.md`
- `skills/kmp/addons/android-compose-review.md`

Future examples:

- `skills/php/addons/laravel-review.md`
- `skills/php/addons/eloquent-persistence.md`
- `skills/backend-kotlin/addons/spring-review.md`
- `skills/backend-kotlin/addons/ktor-review.md`

Keep the structure flat under `addons/`. Avoid deeper nested trees unless there is a clear governance need.

## Naming Rules

Allowed shapes:

- `<addon>.md`
- `<addon>-<area>.md`

Good examples:

- `android-compose-review.md`
- `android-compose-implementation.md`
- `laravel-review.md`
- `eloquent-persistence.md`

Avoid vague or marketing-style names.

## Ownership Rules

- Add-ons are owned by the stack they extend.
- Add-ons must live under `skills/<stack>/addons/`.
- Add-ons must not appear in `ALLOWED_PACKAGES`.
- Add-ons must not be routed to directly by the base routers.

## Precedence

Recommended merge order:

1. `.agents/skill-overrides.md`
2. `AGENTS.md`
3. stack skill contract
4. applied add-ons
5. generic supporting files

This keeps project-local rules strongest and ensures add-ons are refinements, not hidden overrides.

## Detection Model

Detection is two-stage.

### Stage 1: Stack Detection

Keep current stack routing as the source of truth.

### Stage 2: Add-On Detection

After a stack owns the route, detect zero or more governed add-ons within that stack.

Example `kmp` Android add-on signals:

- `androidMain`
- Android manifest
- Android resource structure
- Android-specific Compose or runtime APIs
- Android-only navigation or runtime wiring
- project markers indicating Android-targeted KMP UI work

Future examples:

- Laravel: `artisan`, `Illuminate\\`, `routes/web.php`, `config/app.php`
- Eloquent: `Illuminate\\Database\\Eloquent`, model relationships, scopes
- Spring: `@RestController`, `@Service`, `org.springframework`
- Ktor: `io.ktor.server`, `routing {}`

Add-ons apply only after the parent stack already owns the route.

## Reporting Contract

Applied add-ons must be visible in workflow output.

Examples:

- `Applied add-ons: android-compose`
- `Applied add-ons: laravel, eloquent`

Behavior must never be hidden.

## Workflow Impact

Add-ons may refine:

- `bill-feature-implement`
- `bill-feature-implement-agentic`
- stack-specific code review
- stack-specific quality-check only when needed
- possibly `bill-feature-verify`

Base routers should still route only to stack skills, not directly to add-ons.

## Android / KMP Pilot

This should be the first pilot because:

- current routing already treats Android-flavored work as part of `kmp`
- the current Compose spike proved implementation guidance fits better in feature implementation than in review
- Google's `android/skills` repository is a useful reference base for Android-specific guidance

Pilot add-ons:

- `skills/kmp/addons/android-compose-implementation.md`
- `skills/kmp/addons/android-compose-review.md`

Use Google `android/skills` as reference material, especially Compose-centered guidance. Pull only content that transfers well to KMP.

High-transfer categories:

- Jetpack Compose guidance
- some navigation guidance when abstracted properly
- some performance guidance when stated as Compose or UI behavior rather than Android tooling specifics

Do not import directly as-is:

- migration workflows
- AGP upgrade flows
- Play Billing upgrade flows
- Android Studio or Gradle tooling instructions
- Android-only upgrade checklists

## Current Spike Learnings

The current branch work established:

- official Android and Compose guidance belongs more naturally in implementation than review
- `bill-kmp-code-review-ui` should stay an enforcement rubric
- direct one-off wiring into `bill-feature-implement` is too narrow if the repo is going to generalize across stacks and frameworks

Conclusion: the current direct-wiring work is a useful spike, not the final architecture.

## Implementation Plan

### Phase 1: Add-On Governance

1. Update `AGENTS.md`
   Add an add-on system section covering:
   - purpose
   - ownership
   - naming
   - detection
   - reporting
   - testing requirements

2. Update `scripts/validate_agent_configs.py`
   Add validation for:
   - allowed `addons/` location
   - allowed add-on filename patterns
   - ownership rules
   - prohibition on treating add-ons as top-level packages

3. Update `scripts/skill_repo_contracts.py`
   Add add-on support-file awareness where stack skills link to add-on files as owned assets.

4. Update orchestration references:
   - `orchestration/stack-routing/PLAYBOOK.md`
   - `orchestration/review-orchestrator/PLAYBOOK.md`
   - `orchestration/review-delegation/PLAYBOOK.md` if needed
   - `orchestration/telemetry-contract/PLAYBOOK.md` only if reporting changes require it

Define:

- stack-first, add-on-second routing
- add-on reporting format
- merge behavior

### Phase 2: KMP Android Pilot

5. Add:
   - `skills/kmp/addons/android-compose-implementation.md`
   - `skills/kmp/addons/android-compose-review.md`

6. Update KMP skills to use add-ons conditionally:
   - `skills/kmp/bill-kmp-code-review/SKILL.md`
   - `skills/kmp/bill-kmp-code-review-ui/SKILL.md`
   - any KMP-owned implementation or review support files as needed

7. Update feature implementation workflows so they do not hardcode KMP Compose guidance directly. Instead they should:
   - detect stack
   - detect add-ons within stack
   - read add-on implementation guidance when the owning stack says it applies

Likely files:

- `skills/base/bill-feature-implement/SKILL.md`
- `skills/base/bill-feature-implement/reference.md`
- `skills/base/bill-feature-implement-agentic/SKILL.md`
- `skills/base/bill-feature-implement-agentic/reference.md`

The base workflows should remain generic. Stack and add-on logic should be explicit and minimal.

### Phase 3: Tests

8. Add routing-contract tests proving:
   - top-level routing still lands on `kmp`
   - Android markers inside `kmp` apply the Android add-on
   - review outputs report applied add-ons
   - feature-implement paths report and load applied add-ons
   - no new top-level package was introduced
   - no direct `bill-android-*` routing exists

9. Add validator tests for:
   - accepted add-on names
   - rejected add-on names
   - rejected add-ons outside the owning stack
   - rejected package and add-on confusion

### Phase 4: Future Expansion

10. After the pilot stabilizes, consider:
    - `php` plus Laravel
    - `php` plus Eloquent
    - `backend-kotlin` plus Spring
    - `backend-kotlin` plus Ktor

Only expand after the governance and pilot behavior feel stable.

## Recommended Execution For Next Session

If starting fresh:

1. Revert the current direct-wiring Compose guidance spike.
2. Introduce add-on governance.
3. Reapply Compose and Android guidance as the first governed `kmp` add-on pilot.

This yields a cleaner architecture and cleaner history than keeping the current one-off structure and later refactoring it.
