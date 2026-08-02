# SKILL-145 Subtask 1: Current-state lifecycle evidence and failure matrix

## Scope

Audit the current delegated-review runtime and produce the evidence package
needed by provider evaluation. Focus on the remaining lifecycle gap: worker
state and partial results are currently assembled in memory while final
accounting is written only after the runner returns. Verify the existing
least-context, routing, fallback, and native-agent contracts instead of
reimplementing them.

## Acceptance Criteria

1. A lifecycle model names coordinator, worker, aggregation, and terminal states, and identifies every transition that must be durable.
2. Deterministic fixtures reproduce a coordinator that launches workers but does not complete aggregation, an interrupted/non-zero worker, a missing worker result, and a coordinator crash before terminal persistence.
3. Evidence distinguishes process/MCP heartbeat, provider output, declared specialist progress, durable worker progress, and terminal completion.
4. A scope fixture proves packet base/head identity, packet and assignment digests, worker assignment immutability, and the baseline-untracked inclusion/exclusion policy.
5. Failure classification tests prove that interruption, non-zero exit, timeout, unavailable provider, invalid output, aggregation failure, and missing results do not become successful or schema-repairable review output.
6. The evidence package includes bounded timestamps, worker/provider identities, assignment and packet digests, routed areas, process outcomes, durable events, and diagnostic references without full prompts, diffs, or raw transcripts.
7. The historical matrix disposes of all 47 original SKILL-145 failure items, marking the routing/context/install items already covered by SKILL-144/146 rather than opening duplicate implementation work.

## Non-Goals

- Choosing final timeout values or provider support classifications.
- Changing provider launch policies.
- Rebuilding routing, generic fallback, native-agent installation, or least-context handoffs.
- Enabling delegated review by default.

## Dependency Notes

This is the first subtask. Its lifecycle model, failure matrix, and scope
evidence are required by provider, capacity, and aggregation evaluation.

## Validation Strategy

- Use fake clocks, launchers, providers, persistence, and aggregation ports.
- Assert both accepted and rejected lifecycle transitions.
- Compare durable records with process/provider observations and final merged output.
- Run focused Kotlin tests and the repository validation commands from the parent spec.

## Next Path

Proceed to provider-specific isolation, capacity, deadline, and aggregation evaluation.
