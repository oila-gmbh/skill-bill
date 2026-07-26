# SKILL-107 Subtask 3: Manifest-Driven Runtime Hygiene

**Parent:** [spec.md](spec.md)
**Depends on:** none (independent of subtasks 1–2; sequenced after them on the shared branch).
**Covers:** audit findings 2, 7, 3.

## Context

Three runtime seams hard-code platform knowledge or leak pack-internal files:

1. `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/review/ReviewPlatformSlugSupport.kt:5-15` maps routed review skills to platform slugs with a hard-coded `when` (only `bill-kmp-`/`bill-kotlin-` live, plus dead `bill-agent-config-`/`bill-android-` branches) — go/ios/php/python reviews attribute to `"unknown"` in telemetry.
2. `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/runtime/RepoValidationRuntime.kt:121` pins `portableReviewSkills = setOf("bill-kotlin-code-review", "bill-kmp-code-review")`, so the portable-wording lint (used at line 664) skips go/ios/php/python baselines.
3. `copyPackNonSkillFiles` in `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/apply/InstallApplyPlatformPackView.kt:166-190` copies pack-root `agent/` boundary-memory files into user installs (`platform-packs/ios/agent/history.md` and `platform-packs/python/agent/history.md` currently ship).

## Scope

- Derive the routed-skill-prefix → slug mapping from discovered pack manifests (each pack's slug and declared skill-name prefixes, e.g. `bill-<slug>-`), replacing the hard-coded `when`. Delete the dead `bill-agent-config-`/`bill-android-` branches. Keep `"unknown"` as the fallback for unroutable input. Mind module boundaries: `runtime-infra-sqlite` must not grow an fs dependency — inject the mapping (routed-prefix → slug pairs) through the existing composition seam rather than discovering manifests inside the sqlite module.
- Derive `portableReviewSkills` in `RepoValidationRuntime` from discovered pack manifests' declared code-review skill names (baseline `bill-<slug>-code-review` per pack) so the portable-wording lint covers every pack.
- Exclude `agent/` directories in `copyPackNonSkillFiles` so boundary-memory files never reach installed staging.

## Acceptance criteria

1. Reviews routed to `bill-go-code-review`, `bill-ios-code-review`, `bill-php-code-review`, and `bill-python-code-review` (and their area specialists, e.g. `bill-go-code-review-security`) attribute to slugs `go`, `ios`, `php`, `python` respectively (acceptance tests); an unrecognized routed skill still attributes to `"unknown"` and a null/blank input stays `"unknown"` (rejection tests).
2. The hard-coded `bill-agent-config-` and `bill-android-` branches are gone; adding a hypothetical new pack manifest makes its reviews attribute correctly WITHOUT touching `ReviewPlatformSlugSupport` (test with a fixture pack).
3. The change is attribution input only: no modification to any telemetry event schema file and no telemetry contract version bump (assert by leaving `orchestration/contracts` telemetry schemas untouched; verify the review row column type is an unconstrained string).
4. `portableReviewSkills` is computed from discovered pack manifests; a seeded portable-wording violation in a non-kotlin pack baseline (fixture) is flagged by the lint (acceptance), and kotlin/kmp lint behavior is unchanged (regression test).
5. `copyPackNonSkillFiles` skips any path under a pack-root `agent/` directory: installed staging for the ios and python packs contains no `agent/history.md` or `agent/decisions.md` (acceptance test), while other non-skill pack files (e.g. `addons/*.md`, `platform.yaml`) still copy (regression test).
6. All four validators pass: `skill-bill validate`, `scripts/validate_agent_configs`, `npx --yes agnix --strict .`, `(cd runtime-kotlin && ./gradlew check)`.

## Non-goals

- No telemetry event schema changes and no new telemetry fields.
- No changes to what packs declare in manifests (schema work is subtask 2; this subtask reads existing declared data — slug and code-review skill names — which exists at contract 1.1 and 1.2 alike).
- No deletion of the `platform-packs/*/agent/` source files themselves; they are legitimate boundary memory in the repo, they just must not install.
- No AGENTS.md carve-out sentence (subtask 6 documents the behavior this subtask implements).

## Validation strategy

```bash
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
(cd runtime-kotlin && ./gradlew check)
./install.sh
```

After `./install.sh`, spot-check the installed pack staging directory for absence of `agent/` files.

## Risk notes

- The sqlite↔fs module boundary is the main design decision: pass the prefix→slug mapping in, do not import manifest discovery into `runtime-infra-sqlite`. Follow the existing injectable-strategy bias (no conditional platform branching).
- Verify what `normalizePlatformSlug` already does with area-specialist names so prefix matching does not double-normalize.

## Handoff

Run bill-feature-task on `.feature-specs/SKILL-107-audit-fixes/spec_subtask_3_manifest-driven-runtime-hygiene.md`.
