# SKILL-145: Delegated code-review reliability decision

## Intended Outcome

Reassess explicit delegated code review against the current runtime after the
generic fallback and least-context handoff work. Determine whether delegated
review is supportable for each provider, identify the smallest missing
enforceable contract, and produce implementation-ready follow-up work where
the evidence shows a real gap.

This is a reliability investigation and decision record, not a request to
rebuild routing, context projection, or every remediation discovered during
the original SKILL-143 investigation. Inline review remains the default and
`auto` remains inline until a separate governed change approves promotion.

## Current Baseline

The current runtime already provides:

- inline as the default execution mode and explicit opt-in for delegated mode;
- one authoritative review packet with immutable base/head identities;
- hunk-scoped delegated assignments, forbidden worker rediscovery, bounded
  evidence expansion, and context budgets;
- manifest-driven path routing, generic fallback routing, flattened pack
  composition, and installed native-agent inventory preflight;
- basic process failure classification, provider token accounting, and final
  bounded review accounting.

The remaining reliability questions are whether worker lifecycle and partial
results are durable, whether capacity and deadlines are explicit and
observable, whether aggregation proves complete coverage, and whether every
provider that claims delegated support conforms independently.

## Acceptance Criteria

1. A current-state matrix disposes of every historical failure item from the original SKILL-145 draft, keyed 1–47 and grouped as installation/native identity, routing/scope, capacity, liveness, completion/aggregation, and telemetry/trust. Each item is marked `resolved`, `remaining`, `not applicable`, or `regression`, with code, test, fixture, or provider evidence.
2. A deterministic lifecycle fixture records coordinator preparation, worker launch, worker progress, worker completion, aggregation, and terminal output, including timestamps, provider/worker identity, assignment digest, packet digest, routed area, and terminal reason.
3. Durable review status distinguishes selected, queued, launched, running, completed, failed, timed out, cancelled, and aggregated workers. Process existence, MCP activity, and heartbeats are recorded only as liveness observations and never as specialist progress.
4. Scope integrity is proven from the caller-selected base/head and packet digest, including the baseline-untracked policy. Tests show that historical or unrelated paths cannot expand routing, and that every worker consumes its digest-bound assignment.
5. Deterministic capacity fixtures measure one-lane and multi-wave execution, count coordinator and worker slots, expose predicted and actual wave counts, and prove that selected areas are neither silently dropped nor relaunched after a completed retry.
6. Worker failure, unavailable provider, invalid output, coordinator interruption, aggregation failure, progress-idle expiry, per-worker deadline expiry, and whole-review deadline expiry each produce an explicit durable terminal classification. Only a normally completed zero-exit response may enter schema repair for an invalid result envelope.
7. Codex, Claude, Cursor, and every other provider that claims delegated support have an independent capability matrix covering isolation, launch, output capture, declared progress, cancellation, timeout, token reporting, and aggregation behavior. Unsupported providers are explicitly classified and do not silently fall back to inline.
8. Representative small, medium, and multi-area fixtures capture elapsed time, token usage, process count, MCP startup count where observable, selected/completed areas, worker loss, and aggregation completeness without persisting full prompts, diffs, or raw transcripts.
9. Startup, progress-idle, per-worker, aggregation, and whole-review deadlines have documented rationale, injectable clock/watchdog tests, and deterministic cancellation/reconciliation behavior.
10. The final decision classifies delegated review per provider as supportable, experimental explicit opt-in, or unsupported; each classification cites evidence and falsifiable promotion or rejection criteria.
11. The reliability contract defines durable lifecycle identity, progress, capacity accounting, deadlines, terminal classes, aggregation completeness, bounded diagnostic evidence, retry idempotency, and provider-strategy isolation.
12. Recommended implementation work is ordered across schema, domain, application, persistence, provider adapter, telemetry, installation, documentation, and tests, and does not change inline review’s default or declared-area checklist.

## Constraints

- Inline code review remains the default, and `auto` resolves inline.
- Delegated review is selected only by an explicit delegated argument.
- Existing least-context, routing, generic fallback, and installed-inventory contracts remain authoritative; this work must not duplicate or weaken them.
- Durable workflow/review state, not in-memory coordinator state, is authoritative.
- Provider-specific behavior stays behind injected command-builder and process strategies.
- Unsupported providers remain explicitly unsupported until independent evidence justifies support.
- Durable evidence is bounded and diagnostic. Do not persist full prompts, complete diffs, raw provider transcripts, source bodies, or tool-output logs.
- No generated skill wrappers, provider-native outputs, or support pointers are committed.

## Superseded Scope

The following original concerns are investigation inputs, not automatic new
implementation work: source-checkout-coupled preflight, false concrete routing,
generic fallback absence, broad worker context, nested baseline orchestration,
and worker diff rediscovery. SKILL-144 and SKILL-146 provide the current
contracts for those areas. The investigation must verify that they still hold
and record regressions, but must not reimplement them under SKILL-145.

## Non-Goals

- Re-enabling delegated review as a default.
- Redesigning inline review or reducing its full-area checklist.
- Treating larger timeouts, subprocess existence, CPU activity, or MCP startup as proof of review progress.
- Implementing every remediation discovered by the investigation in this issue.
- Persisting full transcripts or transferring them between phases or agents.

## Validation Strategy

- Use deterministic fake launchers, clocks, provider adapters, and persistence ports for lifecycle, watchdog, retry, cancellation, and aggregation tests.
- Run bounded provider canaries only where the provider is installed and authenticated; record measurements and bounded diagnostic references.
- Compare durable lifecycle records with final output and provider observations without treating provider observations as authoritative progress.
- Run the appropriate Kotlin module checks plus `skill-bill validate`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

## Delivery Plan

1. Build the current-state failure matrix and deterministic lifecycle evidence package.
2. Evaluate provider isolation, capacity, deadlines, aggregation, and measured limits.
3. Record the provider-aware decision and reliability contract, then prepare separate implementation specs for approved remediation.
