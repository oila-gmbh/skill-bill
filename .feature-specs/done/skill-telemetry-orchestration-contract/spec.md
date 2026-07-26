# Feature: skill-telemetry-orchestration-contract
Created: 2026-04-13
Status: Complete
Sources: chat discussion about telemetry coverage gaps, standalone-vs-orchestrated skill usage, and how to avoid double-counting when one user-initiated workflow fans out across several skills

## Acceptance Criteria
1. Every telemeterable skill's MCP tool accepts an optional `orchestrated: bool = false` parameter and respects it at runtime.
2. When `orchestrated=false` (standalone), the tool generates its own session id, emits its own started/finished events, and owns its lifecycle end-to-end.
3. When `orchestrated=true` (nested), the tool emits zero telemetry events, and instead returns a structured `telemetry_payload` object on the tool result that the parent can aggregate.
4. Orchestrator skills (`bill-feature-implement`, `bill-feature-verify`, and any future ones) collect the returned `telemetry_payload` from each child tool call and embed them as entries in a `child_steps` array on their own finished event.
5. Add `skillbill_quality_check_started` / `skillbill_quality_check_finished` events for `bill-quality-check`, including the orchestrated/standalone mode split.
6. Add `skillbill_feature_verify_started` / `skillbill_feature_verify_finished` events for `bill-feature-verify`, including the orchestrated/standalone mode split.
7. Add a single `skillbill_pr_description_generated` event for `bill-pr-description`, including the orchestrated/standalone mode split.
8. `bill-feature-implement`'s finished event gains a `child_steps` array capturing nested quality-check, code-review, and pr-description payloads when those children were invoked in orchestrated mode during the session.
9. Review telemetry (`import_review` / `triage_findings`) is retrofitted to use the same `orchestrated` flag so the whole suite follows one consistent contract; when orchestrated, no `skillbill_review_finished` event is emitted and the review payload is returned to the parent for aggregation.
10. Router skills (`bill-code-review`, `bill-quality-check`) never emit telemetry themselves; they pass `orchestrated` through to the routed concrete skill unchanged.
11. Validator asserts that every telemeterable MCP tool accepts and respects `orchestrated=true` by producing zero outbox rows for that path.
12. Validator asserts that every orchestrator skill's `SKILL.md` instructs the agent to pass `orchestrated=true` when invoking child telemeterable tools.
13. Skill docs (`docs/review-telemetry.md`) gain a "Session correlation" section describing the standalone-first contract, the `orchestrated` flag, and the `child_steps` aggregation shape.
14. Tests cover: standalone-path event emission, orchestrated-path silence plus structured return, parent aggregation into `child_steps`, and level-aware field redaction inside nested payloads.

## Non-Goals
- Cross-event correlation via a shared `parent_session_id` field. One user-initiated workflow must produce exactly one telemetry event; correlation by construction replaces correlation by foreign key.
- Automatic orchestration detection. A child skill must never infer "am I orchestrated?" from ambient state — the parent passes the flag explicitly.
- A session registry, active-session lookup, or any global in-memory coupling between parent and child runs.
- New telemetry for skills that are conversational (`bill-grill-plan`), content-generating with privacy risk (`bill-boundary-decisions`, `bill-boundary-history`), or subsumed by existing events (`bill-unit-test-value-check`, `bill-feature-guard`, `bill-feature-guard-cleanup`, `bill-new-skill-all-agents`).
- Changes to the existing `off` / `anonymous` / `full` telemetry level model. Field-level redaction inside `child_steps` follows the same level rules already defined.

---

## Consolidated Spec

### Problem

Today only `bill-code-review` (via `skillbill_review_finished`) and `bill-feature-implement` (via the `_started` / `_finished` pair) emit telemetry. The remaining base skills are dark. The obvious fix — instrument every skill — creates a worse problem: when `bill-feature-implement` internally runs a code review, a quality check, and a pr description, naive instrumentation would produce four separate events for one user-initiated workflow. That makes funnel analysis in PostHog misleading and inflates event volume without adding signal.

At the same time, every skill must remain usable standalone. A user invoking `bill-quality-check` alone must still produce a clean, analyzable event. Standalone usability is the baseline; orchestrated usage is the optimization layer.

### Contract

Every telemeterable MCP tool accepts an `orchestrated: bool` parameter, default `false`.

**Standalone mode (`orchestrated=false`):**
- Tool generates its own session id (`qck-YYYYMMDD-HHMMSS-XXXX`, `fvr-…`, `prd-…`, etc.).
- Tool writes started and finished events to the local outbox as it does today for `bill-feature-implement`.
- Tool returns a minimal result (session id, status).
- This is the canonical event shape for each skill.

**Orchestrated mode (`orchestrated=true`):**
- Tool does not generate a session id.
- Tool does not write any events to the outbox.
- Tool returns a `telemetry_payload` dict on the tool result, carrying the same fields the finished event would have carried in standalone mode (minus the `session_id`, plus a `skill` field identifying which child produced it).
- Tool return contract: `{"mode": "orchestrated", "telemetry_payload": {...}}`.

**Parent responsibility:**
- Orchestrator `SKILL.md` explicitly instructs the agent to pass `orchestrated=true` to every child telemeterable tool it calls.
- Orchestrator collects each returned `telemetry_payload` into a `child_steps: list[dict]` field on its own finished event.
- Orchestrator emits exactly one rolled-up finished event for the whole workflow.

**Graceful degradation:**
- If a parent forgets to pass the flag, children emit standalone events. The result is noisy (multiple events for one workflow) but never silently lost. This is the intended failure mode — it is visible in telemetry volume and easy to catch in review.

### New events

- `skillbill_quality_check_started` / `skillbill_quality_check_finished` — fields: `session_id`, `routed_skill`, `detected_stack`, `scope_type`, `initial_failure_count`, `final_failure_count`, `iterations`, `result`, `duration_seconds`. `full` adds `failing_check_names`, `unsupported_reason`.
- `skillbill_feature_verify_started` / `skillbill_feature_verify_finished` — fields: `session_id`, `acceptance_criteria_count`, `rollout_relevant`, `feature_flag_audit_performed`, `review_iterations`, `audit_result`, `completion_status`, `duration_seconds`, plus a nested `review` object (the child review payload in orchestrated mode). `full` adds `spec_summary`, `gaps_found`.
- `skillbill_pr_description_generated` — one-shot event. Fields: `session_id`, `commit_count`, `files_changed_count`, `was_edited_by_user`, `pr_created`. `full` adds `pr_title`.

### Extended event

`skillbill_feature_implement_finished` gains a `child_steps` array. Each entry is a `telemetry_payload` returned by a child tool invoked during the session, with a `skill` field. Fields inside each entry follow the standalone event schema for that child skill, minus `session_id`. Level-aware redaction applies inside each entry exactly as it does at the top level.

### Review telemetry retrofit

`import_review` and `triage_findings` today follow a parent-owned ownership rule enforced by "only the parent calls these tools." This works but uses a different mechanism from the rest of the suite. Retrofit both tools to accept `orchestrated: bool = false`:
- `orchestrated=false`: behavior unchanged. The tool writes outbox rows and eventually emits `skillbill_review_finished` when the lifecycle resolves.
- `orchestrated=true`: the tool returns the review payload structurally but suppresses event emission. The parent embeds the payload into its own finished event's `child_steps`.

One contract across the whole suite replaces two rules that mean the same thing.

### Router skills

`bill-code-review` and `bill-quality-check` are thin routers. They never emit telemetry of their own. Their job is to pick the concrete stack-specific skill and pass through `orchestrated` unchanged. A user calling the router standalone produces one event from the routed concrete skill. A parent calling the router with `orchestrated=true` produces zero events and one returned payload.

### Enforcement

- Validator test: for each telemeterable MCP tool, invoke it with `orchestrated=true` against an in-memory outbox and assert zero rows written.
- Validator test: for each orchestrator skill, grep its `SKILL.md` for the child-invocation block and assert the instruction to pass `orchestrated=true` is present.
- Regression tests: parent aggregation path, standalone emission path, level-aware redaction inside `child_steps`, graceful degradation when the flag is absent.

### Docs

`docs/review-telemetry.md` gains a "Session correlation" section covering:
- the standalone-first principle
- the `orchestrated` flag contract
- the `child_steps` aggregation shape
- the graceful-degradation rule
- a worked example of a `bill-feature-implement` session showing the single rolled-up event

### Rollout

- Phase 1: add the `orchestrated` flag and the three new events. Emit in standalone mode only; orchestrator aggregation wiring follows.
- Phase 2: retrofit review tools with the same flag; wire `bill-feature-implement` to pass `orchestrated=true` and collect `child_steps`.
- Phase 3: validator tests and docs.

Each phase is independently shippable and each phase leaves standalone usage fully functional.
