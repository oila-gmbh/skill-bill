## [2026-08-18] SKILL-197 subtask 2 — KMP uncovered area disposition
Areas: docs/review-area-ownership, platform-packs/kmp/code-review/security, platform-packs/kmp/code-review/ux-accessibility, runtime-infra-fs/scaffold
- Recorded disposition for all four remaining kotlin-owned areas: `security` declared on `kmp` with on-device plus shared/JVM source-set rules; `performance`, `testing`, and `api-contracts` retained on `kotlin` with per-rule reachability audits in `docs/review-area-ownership.md`.
- Declaring on `kmp` displaces the `kotlin` rubric for that area entirely — a `kmp` specialist cannot defer to the baseline `security` lane. reusable
- `kmp` now owns seven physical areas and three inherited; scaffold composition tests and render snapshots pin that split. `ux-accessibility` boundary pointers name the `kmp` `security` specialist, not the kotlin baseline.
- Limitation: `kotlin` pack byte-unchanged; backend-only triggers stay inert (silent, not misleading) on Android inputs for retained areas.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-11] SKILL-182 subtask 3 delegated-review docs reconciled to live playbook
Areas: docs/delegated-review, docs, AGENTS.md
- Prefaced every `docs/delegated-review/` file as the historical record of the SKILL-159-removed external subsystem, each linking to `orchestration/review-delegation/PLAYBOOK.md` as the live contract; corrected `decision.md` so `auto`/omission resolve to `inline` and `delegated` is explicit-only.
- Stated Cursor's current delegated support once (experimental, explicit opt-in, in-harness, not live-CLI verified) and demoted `provider-capability-matrix.md` so deleted registry types are not read as current sources.
- Aligned `docs/capabilities.md` and `docs/review-telemetry.md` mode/default wording with the playbook; lane accounting now admits plan-identity when the harness returns no launch id.
- Reusable: extend the SKILL-159 removal-preface pattern across a whole historical docs directory, then sweep adjacent reader surfaces so they cannot contradict the live playbook.
- Historical SKILL-145 bodies left otherwise unchanged; AGENTS.md dropped a stale validate-phase duplicate only.
Feature flag: N/A
Acceptance criteria: 8/8 implemented
