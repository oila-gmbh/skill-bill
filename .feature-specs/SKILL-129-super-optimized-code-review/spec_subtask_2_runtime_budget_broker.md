# Subtask 2 - Enforce bounded launches and evidence

## Scope

Connect the authoritative packet to every production delegated-review launch.
Implement a provider-neutral broker that owns launch projection, allowed
evidence, expansions, tool/turn accounting, result validation, and typed budget
termination.

## Acceptance Criteria

1. Every delegated worker launch is derived from a validated assignment and
   contains only its specialist contract, one rubric, assigned hunks/paths,
   matched rules, bounded dependencies, evidence targets, IDs, and budget.
   Native agents treat their embedded governed rubric as authoritative and have
   no instruction to reread a sidecar; any explicitly supported generic worker
   receives exactly one projected rubric from the parent.
2. Codex launches are isolated with `fork_turns: "none"`; provider strategies
   expose equivalent isolation where supported without process-runner identity
   branching.
3. The broker rejects scope/status/diff discovery, AGENTS traversal, routing,
   learnings/MCP rediscovery, broad searches, unrelated rubric reads, and
   unassigned file access; allowed evidence is batched and measured.
4. Parent packet, launch, evidence result/cumulative evidence, expansions, lane
   result, tool calls, model turns, and enforceable provider token dimensions
   are checked at their real production boundaries.
5. Every exceeded limit returns typed `review_context_budget_exceeded`; a
   non-enforceable post-run token excess returns `budget_regression`, with no
   truncation, widening, skipped required lane, worker replacement, or mode
   substitution.
6. Application and adapter tests use fake worker/tool surfaces to prove forbidden
   operations fail, excessive reads/turns/results terminate, allowed expansions
   are audited, and ordinary bounded reviews complete.

## Non-Goals

- Do not change platform composition or native-agent installation.
- Do not weaken rubrics, findings, or required lanes.
- Do not rely on prompt prose as the enforcement boundary.

## Dependency Notes

Depends on subtask 1 for the canonical packet, assignment, digest, and expansion
contracts.

## Validation Strategy

Run focused application, broker, adapter, configuration, and process-strategy
tests, including boundary tests for every budget dimension and provider usage
ownership.

## Next Path

Continue with subtask 3, which flattens layered platform review composition into
direct assignments consumed by this broker.
