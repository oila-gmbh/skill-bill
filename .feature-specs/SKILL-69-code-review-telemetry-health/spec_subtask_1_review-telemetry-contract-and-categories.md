---
status: Complete
---

# SKILL-69 Subtask 1 - Review Telemetry Contract and Categories

Parent spec: [.feature-specs/SKILL-69-code-review-telemetry-health/spec.md](./spec.md)
Issue key: SKILL-69

## Scope

Harden the code-review telemetry payload contract so review-health metrics do
not require PostHog-side inference. This subtask owns the review telemetry model,
payload mapping, import/category assignment, and regression tests for standalone
and orchestrated review payloads.

- Add top-level `rejected_findings` and `rejected_rate` to
  `ReviewFinishedTelemetryPayload` and any orchestrated review payload returned
  to parent workflows.
- Add stable review dimensions: `platform_slug` and `scope_type`, preserving the
  existing human-readable `review_platform` and `review_scope`.
- Add per-finding `issue_category` to accepted and rejected finding details at
  anonymous and full telemetry levels.
- Implement deterministic category assignment:
  1. explicit category from review output when present;
  2. routed specialist/area when available;
  3. conservative local fallback classifier;
  4. `other` fallback.
- Keep the taxonomy exactly:
  `behavior_correctness`, `data_persistence`, `concurrency_lifecycle`,
  `ux_accessibility`, `testing_quality_gate`, `security_privacy`,
  `docs_contract`, `other`.
- Keep telemetry privacy boundaries unchanged: category/severity/confidence/id
  are anonymous-safe; descriptions, paths, notes, and learning content remain
  full-only.

## Acceptance Criteria

1. Standalone and orchestrated review telemetry payloads include
   `rejected_findings` and `rejected_rate` derived from terminal latest
   outcomes, not from remote-query complement math.
2. `platform_slug` and `scope_type` are present in review telemetry payloads and
   normalize observed labels including `agent-config`, `kmp`, `kotlin`,
   `backend-kotlin`, `android`, `unknown`, and custom/free-form labels.
3. Accepted and rejected finding details include `issue_category`, `severity`,
   `confidence`, and `outcome_type`.
4. Category assignment works for explicit category, routed specialist/area, and
   fallback classifier paths.
5. Zero-finding reviews and transient unresolved findings before finish-state
   handling are covered without divide-by-zero or misleading rejected-rate
   behavior.
6. Existing full/anonymous/off telemetry-level behavior is preserved except for
   the newly anonymous-safe category field.

## Non-Goals

- Do not change feature-implement lifecycle or child-step accounting; subtask 2
  owns that.
- Do not build stats/reporting output; subtask 3 owns aggregation surfaces.
- Do not tune review prompts or specialist guidance; subtask 4 owns the narrow
  noise-reduction pass.
- Do not backfill historical telemetry.

## Dependency Notes

Depends on: nothing. This subtask establishes the review payload fields that
subtasks 2 and 3 consume when embedding and reporting review child steps.

## Validation Strategy

- Unit tests for `ReviewFinishedTelemetryPayload` mapping.
- Import/triage tests for accepted, rejected, false-positive, edited, unresolved,
  and zero-finding runs.
- Category-classifier tests for explicit, routed, fallback, and `other` cases.
- Orchestrated review payload tests proving the same numeric and detail fields
  are returned for parent `child_steps`.

## Next Path

Subtask 2 applies the parent child-step contract to feature-implement telemetry
and consumes the review payload fields introduced here.

## Spec Path

.feature-specs/SKILL-69-code-review-telemetry-health/spec_subtask_1_review-telemetry-contract-and-categories.md
