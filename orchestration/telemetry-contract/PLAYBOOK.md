---
name: telemetry-contract
description: Canonical shared telemetry contract for skill-bill skills.
---

# Shared Telemetry Contract

This maintainer-facing reference snapshot documents the shared telemetry contract used across all telemeterable skills in the skill-bill suite. Runtime-facing skills consume this contract through a sibling `telemetry-contract.md` file inside each skill directory. Do not reference this repo-relative path directly from installable skills.

## Standalone-first contract

Every telemeterable skill must be usable alone. When invoked directly by a user, each skill generates its own session id and emits its own events:

- `bill-code-review` — `skillbill_review_finished` (once the review lifecycle resolves)
- `bill-quality-check` — `skillbill_quality_check_started` + `_finished`
- `bill-feature-verify` — `skillbill_feature_verify_started` + `_finished`
- `bill-pr-description` — `skillbill_pr_description_generated`
- `bill-feature-implement` — `skillbill_feature_implement_started` + `_finished`

## The `orchestrated` flag

Every telemeterable MCP tool accepts `orchestrated: bool = false`.

- **`orchestrated=false` (standalone):** the tool generates its own session id, emits started/finished events, and owns its lifecycle.
- **`orchestrated=true` (nested):** the tool emits **zero** telemetry events. Instead it returns a `telemetry_payload` dict on the tool result that the caller (the orchestrator) collects.

The orchestrator is responsible for setting the flag. A child skill never infers orchestrated mode from ambient state.

## MCP tools by skill kind

### Review skills

Review skills (`bill-code-review` and its stack-specific implementations) use the `import_review` and `triage_findings` MCP tools.

- **Standalone:** `import_review` imports the review text and emits telemetry; `triage_findings` records user feedback and completes the review lifecycle.
- **Orchestrated (`orchestrated=true`):** both tools suppress outbox emission and return a `telemetry_payload` for the parent to embed.

### Quality-check skills

Quality-check skills (`bill-quality-check` and its stack-specific implementations) use the `quality_check_started` and `quality_check_finished` MCP tools.

- **Standalone:** call `quality_check_started` once stack routing is decided, then `quality_check_finished` when the loop finishes.
- **Orchestrated:** skip `quality_check_started`; call `quality_check_finished` with `orchestrated=true` and all started+finished fields combined.

### Feature-verify skills

Feature-verify skills (`bill-feature-verify`) use the `feature_verify_started` and `feature_verify_finished` MCP tools.

- **Standalone:** call `feature_verify_started` after criteria are confirmed, then `feature_verify_finished` after the verdict.
- **Orchestrated:** skip `feature_verify_started`; call `feature_verify_finished` with `orchestrated=true` and all started+finished fields combined.

### PR description skills

PR description skills (`bill-pr-description`) use the `pr_description_generated` MCP tool.

- **Standalone:** call `pr_description_generated` after the PR description is presented.
- **Orchestrated:** call `pr_description_generated` with `orchestrated=true`.

### Feature-implement skills

Feature-implement skills (`bill-feature-implement`, `bill-feature-implement-agentic`) use the `feature_implement_started` and `feature_implement_finished` MCP tools. These are always top-level — they are not invoked with `orchestrated=true` by other skills.

## child_steps aggregation

When the parent's finished event fires, it embeds each collected `telemetry_payload` in a `child_steps` array. One workflow, one event. Orchestrator skills must:

1. Pass `orchestrated=true` to every child MCP tool they invoke.
2. Collect the `telemetry_payload` dict returned by each child tool.
3. Append each payload to a running `child_steps` list.
4. Pass the complete `child_steps` list to their own finished tool call.

## Routers never emit

`bill-code-review` and `bill-quality-check` are thin routers. They do not emit telemetry of their own — routing metadata is carried inside the concrete routed skill's telemetry call. They pass `orchestrated` through to the routed concrete skill unchanged.

## Telemetry Ownership

The review layer that owns the final merged review output for the current review lifecycle owns review telemetry.

- If this review is delegated or layered under another review, do not call `import_review`. Return the complete review output plus summary metadata (`review_session_id`, `review_run_id`, detected scope/stack, execution mode, specialist reviews) to the parent review instead.
- If this review owns the final merged review output for the current review lifecycle, call the `import_review` MCP tool:
  - `review_text`: the complete review output (Section 1 through Section 4)

## Triage Ownership

The same parent review owns triage recording after the user responds to findings so the review lifecycle can complete and the finished telemetry event can fire.

- If this review is delegated or layered under another review, do not call `triage_findings`; the parent review owns triage handoff and telemetry completion.

Each finding gets one decision using its position number from the risk register:
- `fix` — the finding was accepted and the fix was applied
- `accept` — the finding was accepted but no code change was needed
- `skip` — the finding was intentionally skipped (append a reason after ` - `)
- `false_positive` — the finding was incorrect

- If this review owns the final merged review output for the current review lifecycle and the user responds to findings, call the `triage_findings` MCP tool:
  - `review_run_id`: the review run ID from the review output
  - `decisions`: prefer a single structured selection string that fully resolves the review, e.g. `["fix=[1,3] reject=[2]"]`
  - fallback: explicit numbered decisions still work, e.g. `["1 fix", "2 skip - intentional", "3 accept"]`

Skip triage recording when the final parent-owned review produced no findings.

## Graceful degradation

If a parent skill forgets to pass `orchestrated=true` to a child, the child emits its own standalone event. The workflow produces extra events but nothing is lost. Always pass the flag from the orchestrator's skill instructions.
