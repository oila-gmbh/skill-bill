# SKILL-145 Subtask 2: Provider, capacity, deadline, and aggregation evaluation

## Scope

Evaluate delegated review independently for Codex, Claude, Cursor, and every
other provider that claims delegated support. Use subtask 1’s lifecycle model
and fixtures to measure launch isolation, progress visibility, deterministic
waves, worker completion, deadline enforcement, cancellation, and aggregation.
Keep provider behavior behind each provider’s injected command-builder and
process strategy; do not apply a Codex workaround globally.

## Acceptance Criteria

1. Each claimed provider has a capability matrix covering fresh-context isolation, worker tracking, output capture, declared progress, cancellation, timeout, token reporting, and terminal-result behavior.
2. Codex tests prove its liveness/deadline behavior is isolated from Claude, Cursor, and unchanged providers. Providers that cannot satisfy the contract are explicitly marked unsupported.
3. Deterministic one-lane and multi-wave scenarios record selected, queued, launched, running, completed, failed, timed-out, cancelled, and aggregated workers in durable state.
4. Capacity accounting includes the coordinator slot, reports predicted and actual waves, and never silently drops or duplicates a selected review area.
5. Startup, progress-idle, per-worker, aggregation, and whole-review deadlines terminate predictably and durably block with bounded diagnostic evidence.
6. Aggregation rejects missing worker results, duplicate ownership, assignment/provider/attempt mismatches, invalid finding envelopes, and incomplete declared-area coverage.
7. Representative small, medium, and multi-area fixtures report elapsed time, tokens, process count, observable MCP startup count, and completed-area count.
8. Interruption is exercised before launch, during a worker, between waves, during aggregation, and after aggregation but before terminal persistence; every case has one deterministic terminal classification.
9. Provider runs disposition every remaining parent-spec failure item relevant to that provider and prove that provider-specific mitigations do not change other command builders or process strategies.

## Non-Goals

- Sharing one liveness policy across providers.
- Promoting delegated mode to default.
- Masking an unsupported provider with inline fallback.
- Repeating the already-governed routing, fallback, assignment projection, or native-agent inventory work.

## Dependency Notes

Depends on subtask 1’s lifecycle model, historical failure matrix, and
reproducible scope/lifecycle fixtures.

## Validation Strategy

- Run provider adapter and process-runner tests with fake clocks and deterministic launch outcomes.
- Run bounded live canaries only where a provider is installed and authenticated.
- Assert provider parity for unchanged adapters and explicit unsupported behavior for unclaimed providers.
- Exercise interruption at every lifecycle boundary and compare durable state with final aggregation.

## Next Path

Proceed to the provider-aware decision and implementation-ready reliability contract.
