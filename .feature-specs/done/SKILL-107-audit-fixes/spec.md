# SKILL-107: Repo Consistency Audit Fixes

**Status:** Decomposed — parent overview
**Issue:** SKILL-107
**Branch:** `feat/SKILL-107-audit-fixes` (base: `main`)
**Execution model:** `same_branch_commit_per_subtask` — one commit per completed subtask on the feature branch.

## Overview

Fix all findings from the 2026-07-06 repo consistency audit plus one mid-planning correction: the `feature-implement` family name is retired in favor of `feature-task` and residual identifiers must be retired where safe.

Note: `CLAUDE.md` is a symlink to `AGENTS.md`. Every "CLAUDE.md" edit in these specs is an edit to `AGENTS.md`.

## Subtasks (dependency order)

| # | Subtask | Spec | Depends on |
|---|---------|------|------------|
| 1 | Stale-surface retirement (feature-implement naming + pre-shell dead paths) | [spec_subtask_1_stale-surface-retirement.md](spec_subtask_1_stale-surface-retirement.md) | — |
| 2 | `feature_addon_usage` schema field, contract 1.1 → 1.2 | [spec_subtask_2_feature-addon-usage-schema.md](spec_subtask_2_feature-addon-usage-schema.md) | 1 |
| 3 | Manifest-driven runtime hygiene (slug attribution, portable lint, agent/ install exclusion) | [spec_subtask_3_manifest-driven-runtime-hygiene.md](spec_subtask_3_manifest-driven-runtime-hygiene.md) | — |
| 4 | Python code-review native agents | [spec_subtask_4_python-native-agents.md](spec_subtask_4_python-native-agents.md) | — |
| 5 | peak-hours-warner operator config | [spec_subtask_5_peak-hours-operator-config.md](spec_subtask_5_peak-hours-operator-config.md) | — |
| 6 | Governance doc reconciliation (AGENTS.md, README, capabilities) | [spec_subtask_6_governance-doc-reconciliation.md](spec_subtask_6_governance-doc-reconciliation.md) | 1, 2, 3 |
| 7 | Pointer/playbook docs and nitpicks | [spec_subtask_7_pointer-and-playbook-docs.md](spec_subtask_7_pointer-and-playbook-docs.md) | 2, 5 |

## Finding → subtask map

- Finding 1 (retired scaffold kinds in docs) → subtask 6
- Finding 2 (hard-coded review slug fallback) → subtask 3
- Finding 3 (agent/ files copied into installs) → subtask 3
- Finding 4 (`feature_addon_usage`, contract 1.2) → subtask 2
- Finding 5 (pre-shell `skills/kotlin|kmp` docs + dead code) → subtask 1 (code + doc lines it owns), subtask 6 (remaining doc claims)
- Finding 6 (iOS missing from README/capabilities) → subtask 6
- Finding 7 (portableReviewSkills hard-coded) → subtask 3
- Finding 8 (python native-agents agents.yaml) → subtask 4
- Finding 9a–f (doc staleness batch) → 9e/9f in subtask 6; 9a/9b/9c/9d in subtask 7
- Finding 10 (peak-hours-warner genericization) → subtask 5
- Finding 11 (nitpicks) → 11b in subtask 6; 11a/11c in subtask 7
- Correction (retire residual `feature-implement` identifiers) → subtask 1, consumed by subtask 2

## Global constraints (apply to every subtask)

- All validators green at the end of every subtask:
  - `skill-bill validate`
  - `scripts/validate_agent_configs`
  - `npx --yes agnix --strict .`
  - `(cd runtime-kotlin && ./gradlew check)`
- Runtime changes need acceptance AND rejection test coverage.
- No unnecessary comments.
- No company identifiers in the repo; neutral placeholders (`acme`) only.

## Acceptance Criteria

1. All seven subtasks are complete with their own acceptance criteria satisfied and one commit each on `feat/SKILL-107-audit-fixes`.
2. Repo-wide grep for `feature-implement` (excluding `.feature-specs/` and `.git/`) returns only the recorded durable compat aliases.
3. The platform-pack shell contract is at 1.2 with `feature_addon_usage` declared, all six pack manifests bumped, and the contract-version and anchored-bijection parity tests green.
4. Reviews routed to any of the six packs attribute to the correct platform slug in telemetry; the portable-wording lint covers every pack baseline; installed pack views contain no `agent/` files.
5. `bill-python-code-review` specialists install as native subagents like the go/php packs.
6. `peak-hours-warner.md` contains no vendor names or concrete hours; warning values come from untracked operator config with silent skip when absent.
7. AGENTS.md, README.md, docs/capabilities.md, docs/skill-source-generation.md, and the playbooks are consistent with landed code: current scaffold kinds only, no phantom pre-shell claims, iOS pack catalogued, authored-sidecar and `agent/` carve-out rules stated once and identically, ceremony pointers documented as a third pointer family.
8. `skill-bill validate`, `scripts/validate_agent_configs`, `npx --yes agnix --strict .`, and `(cd runtime-kotlin && ./gradlew check)` all pass on the final branch state.

## Non-goals (whole feature)

- No new platform packs.
- No changes to audit tooling.
- No telemetry contract changes beyond attribution input for finding 2 (must not require a telemetry-event-schema bump).
- No migration of durable workflow rows; durable strings keep compat aliases where renaming would orphan state.

## First subtask

Run subtask 1 first: it retires the `feature-implement` family name that subtask 2's new schema field must key on, and it settles the pre-shell/dead-code facts the doc subtasks describe.
