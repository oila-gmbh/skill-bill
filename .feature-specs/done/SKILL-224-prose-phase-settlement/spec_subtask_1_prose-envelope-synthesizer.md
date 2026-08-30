# SKILL-224 Subtask 1: Prose envelope synthesizer

## Outcome

Strict phase-output schema near-misses for preplan, plan, implement, and audit
no longer block when a handoff `value` (and for audit, `verdict`) can be
recovered. The runtime stamps a canonical envelope and continues into existing
settlement.

## Work

1. Add a domain-pure synthesizer that best-effort parses stdout JSON (including
   fenced blocks), recovers `value` from `produced_outputs.value` or known
   legacy siblings, stamps `contract_version` / `phase_id` / `summary` /
   `status`, and for audit requires a recoverable verdict.
2. Wire the synthesizer after schema reject for those phase ids only (validator
   adapter and/or `gateOutput`).
3. Tests: `implementation_receipt` sibling accepted; audit without verdict
   rejects; blank value rejects.
4. Prompt/docs note that packaging near-misses are absorbed for prose phases.

## Acceptance

Matches parent AC 1–3 for the synthesizer path. Persistence and handoff still
consume conforming `0.6` envelopes with no contract version bump unless wire
fields change.
