# SKILL-145 Subtask 3: Decision and reliability contract

## Scope

Synthesize the evidence into a provider-aware go/no-go decision and specify the
runtime, telemetry, schema, UX, and test changes required for any future promotion
of delegated review.

## Acceptance Criteria

1. The decision classifies delegated review as supportable, experimental explicit opt-in, or unsupported for each provider.
2. Every supportable classification cites measured evidence and every unsupported classification names the failed promotion criterion.
3. The reliability contract defines durable worker progress, scope identity, capacity accounting, deadlines, terminal failure classes, aggregation completeness, and transcript retention.
4. Promotion criteria are falsifiable and include maximum bounded latency, complete declared-area coverage, zero silent worker loss, deterministic terminal status, and provider-isolation tests.
5. Recommended implementation work is split into ordered schema, domain, application, adapter, telemetry, installation, and documentation changes.
6. The decision explicitly preserves inline as the default and auto-resolved mode until a separate governed change approves promotion.
7. The final report includes the complete numbered failure-mode matrix, with no unresolved item omitted from the go/no-go rationale.

## Non-Goals

- Implementing the entire remediation plan.
- Declaring reliability from a single successful run.
- Changing inline review coverage or output semantics.

## Dependency Notes

Depends on subtasks 1 and 2.

## Validation Strategy

- Trace every conclusion to a fixture, durable record, transcript, or measured canary.
- Review the proposed contract against current runtime schemas and provider strategy boundaries.
- Validate that proposed tests include acceptance and rejection cases.

## Next Path

Use the decision to prepare separate implementation specs for approved remediation.
