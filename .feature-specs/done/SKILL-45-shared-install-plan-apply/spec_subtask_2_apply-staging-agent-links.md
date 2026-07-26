# SKILL-45 Shared Install Plan/Apply - Subtask 2: Apply Staging And Agent Links

Parent overview: `.feature-specs/SKILL-45-shared-install-plan-apply/spec.md`

Original parent subtask: `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_1_shared-install-plan-apply.md`

Status: Complete

## Scope

Implement the typed install apply API for filesystem-side installation work driven by the plan from subtask 1. This subtask owns staged skill materialization, dynamic base/platform skill inclusion, agent target linking, native-agent linking, and structured filesystem outcomes.

The implementation must reuse the existing staging model owned by `InstallStaging`: generated wrappers, generated support pointers, and provider-native agent outputs are materialized under `~/.skill-bill/installed-skills/<slug>-<hash>/`, while source `skills/` and `platform-packs/` directories remain read-only.

## Acceptance Criteria

1. A typed install apply API consumes the shared plan and returns structured success, warning, and failure outcomes.
2. Callers do not need to parse shell output to understand what happened.
3. Base skills and selected platform packs from the plan are applied through dynamic discovery, not a hardcoded platform list.
4. Agent links are applied only for the selected or detected supported agents from the plan.
5. Native-agent linking reuses existing runtime operations and reports linked, skipped, warning, or failed outcomes structurally.
6. Staging writes occur only under `~/.skill-bill/installed-skills` and agent install targets; generated governed artifacts are not written to source directories.
7. Windows symlink/elevation failures are captured as explicit structured outcomes with clear fallback or failure state and the existing Developer Mode/elevated shell guidance preserved.

## Non-Goals

- Do not implement telemetry persistence or MCP mutation execution in this subtask except for preserving fields needed by later subtasks.
- Do not migrate `install.sh` or runtime CLI commands.
- Do not change manifest schemas or skill source-generation rules.
- Do not add remote pack discovery.

## Dependencies

- Depends on subtask 1 because apply consumes the typed install plan and result model.

## Validation Strategy

Add focused runtime-core tests for apply behavior where practical, especially staging paths, selected platform application, agent selection, and structured Windows symlink/elevation outcomes. Full contract coverage remains deferred to subtask 4.

## Recommended Next Prompt

Run bill-feature-implement on `.feature-specs/SKILL-45-shared-install-plan-apply/spec_subtask_2_apply-staging-agent-links.md`.
