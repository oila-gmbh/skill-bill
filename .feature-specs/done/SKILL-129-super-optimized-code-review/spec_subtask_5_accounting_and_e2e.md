# Subtask 5 - Report cost and prove end-to-end optimization

## Scope

Complete review-tree accounting, status, telemetry, durable summaries,
documentation, and cross-seam Codex/parallel fixtures using the packet, broker,
flattened plan, and native-agent preflight implemented by earlier subtasks.

## Acceptance Criteria

1. Parent and lane summaries report launch/evidence/result bytes, expansions,
   tool calls, model turns, direct/inclusive ownership, input/cached/output/
   reasoning/total tokens, fresh-token approximation, terminal outcome, and
   aggregate totals without double-counting.
2. Durable artifacts and telemetry retain only bounded numeric metadata,
   identifiers, digests, and outcomes; tests prove prompts, diffs, source,
   guidance bodies, and tool-output bodies are absent.
3. End-to-end Codex fixtures for Kotlin and layered KMP prove one-time discovery,
   `fork_turns: "none"`, no forbidden child commands/MCP calls, no nested
   baseline orchestrator, exact direct lanes, bounded batched evidence, native
   preflight, and deterministic findings/accounting.
4. Regression fixtures cover long parent/AGENTS context, overlapping lanes,
   dangling agents, excessive output, excessive turns, cumulative cached input,
   and provider threshold excess with the required repair or typed outcome.
5. Parallel lanes independently reuse the optimized flow without recursive
   parallel invocation, cross-lane packet leakage, or double-counted usage.
6. Operator and architecture documentation explains budgets, overrides,
   flattened layering, forbidden rediscovery, evidence expansion, agent repair,
   cumulative input, cached input, and fresh-token approximation.
7. The full maintainer gate passes.

## Non-Goals

- Do not defer unit or seam-level correctness owned by earlier subtasks into
  these end-to-end fixtures.
- Do not claim provider totals are billing-accurate when they are heuristic or
  inclusive.
- Do not persist sensitive context for observability.

## Dependency Notes

Depends on subtasks 1-4. It integrates their public contracts but does not
redesign their ownership boundaries.

## Validation Strategy

Run focused telemetry/status/redaction and parallel-runner tests, the new
recording Codex end-to-end fixtures, then:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Next Path

The feature is complete when all acceptance criteria pass and a real delegated
KMP smoke review shows flattened direct lanes with bounded, attributable usage.
