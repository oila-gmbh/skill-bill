# SKILL-145 Subtask 2: Provider liveness, capacity, and aggregation

## Scope

Evaluate delegated review separately for Codex, Claude, and each agent that declares
delegated support. Measure launch isolation, progress visibility, concurrency waves,
worker completion, aggregation, and deadline enforcement without applying one
provider's workaround globally.

## Acceptance Criteria

1. Each claimed provider has a capability matrix covering fresh-context launch, worker tracking, declared progress, cancellation, timeout, output capture, and token reporting.
2. Codex tests prove its liveness and deadline strategy is isolated from Claude and every other provider.
3. Deterministic one-lane and multi-wave scenarios record worker launch, progress, completion, failure, and aggregation in durable state.
4. Worker capacity includes the coordinator slot and never silently drops a selected review area.
5. Per-worker and whole-review deadlines terminate predictably and durably block with bounded evidence.
6. Aggregation rejects missing worker results, duplicate ownership, invalid finding envelopes, and incomplete area coverage.
7. Representative small, medium, and multi-area fixtures report elapsed time, tokens, process count, MCP startup count, and completed-area count.
8. Provider runs disposition every parent-spec failure mode relevant to that provider and prove that provider-specific mitigations do not change other command builders or process strategies.
9. Status output exposes selected, queued, active, completed, failed, timed-out, and aggregated lanes plus predicted and actual wave counts.

## Non-Goals

- Sharing a single liveness policy across providers.
- Promoting delegated mode to default.
- Masking an unsupported provider with inline fallback.

## Dependency Notes

Depends on subtask 1’s lifecycle model and reproducible fixtures.

## Validation Strategy

- Run provider adapter and process-runner tests with fake clocks.
- Run bounded live canaries only where the provider is installed and authenticated.
- Assert provider parity for all unchanged adapters.
- Exercise interruption before launch, during a worker, between waves, during aggregation, and after aggregation but before terminal persistence.

## Next Path

Proceed to the decision record and implementation-ready reliability contract.
