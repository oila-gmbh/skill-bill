# SKILL-224: Prose phase settle (synthesize + MCP)

## Intended Outcome

Prose phases (preplan, plan, implement, audit) stop blocking on outer
phase-output packaging near-misses. The runtime synthesizes a canonical
envelope when a handoff `value` (and audit `verdict`) can be recovered, then
adds MCP settlement tools as the preferred control-plane path so
status/verdict/value are typed RPCs instead of schema-policed stdout.

## Acceptance Criteria

1. For preplan, plan, implement, and audit: after a strict phase-output schema
   reject, a synthesizer recovers `produced_outputs.value` from legacy siblings
   (e.g. `implementation_receipt`) or non-blank prose and stamps a conforming
   envelope (`contract_version`, `phase_id`, `status`, `summary`,
   `produced_outputs`).
2. Audit without a recoverable `verdict` in `{satisfied, gaps_found}` still
   rejects; the synthesizer never invents `satisfied`.
3. Blank or missing recoverable `value` still rejects for completed prose
   phases.
4. MCP tools `feature_task_phase_complete`, `feature_task_phase_block`, and
   `feature_task_audit_settle` write a durable settlement record and return
   acknowledgement.
5. `gateOutput` prefers a settlement for the current workflow/phase/attempt
   over stdout; when present, stdout schema is skipped.
6. No settlement and no recoverable value still blocks (no silent advance).
7. Docs note the settlement-only MCP carve-out versus the former “no
   feature-task MCP” rule.
8. Last-write wins when multiple settlements are recorded for the same attempt.

## Constraints

- Scope is preplan, plan, implement, audit only.
- Durable phase records still store a stamped canonical envelope.
- Do not restore the full `feature_task_*` lifecycle MCP family.
- Do not soft-interpret missing audit verdict into `satisfied`.

## Non-Goals

- Review / build / validate / verify_findings envelope redesign.
- Removing stamped durable envelopes from phase records.
- Unifying `PhaseOutput.value` and `AgentPhaseOutput.output` names.

## Validation Strategy

- Unit tests: implement `implementation_receipt` sibling → accepted; audit
  without verdict rejects; blank value rejects.
- MCP/store tests: complete then gate accepts without valid stdout; block
  disposition; last-write wins.
- `./install.sh` after runtime changes; targeted Gradle tests for synthesizer
  and MCP settlement.

## Delivery Plan

1. Subtask 1 — prose envelope synthesizer + gate seam.
2. Subtask 2 — settlement store, application API, MCP tools, gate preference,
   prompts/docs.
