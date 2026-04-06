---
name: bill-kotlin-code-review-performance
description: Use when reviewing performance risks in Kotlin code, including hot-path work, blocking I/O, latency regressions, and resource waste. Use when user mentions performance, blocking I/O, hot path, memory leak, or latency in Kotlin code.
---

# Performance Review Specialist

Review only high-impact performance issues.

## Focus
- Main-thread or request-thread blocking
- Expensive or repeated work in hot paths
- Inefficient DB/network access patterns (N+1, redundant calls)
- Retry/backoff inefficiency and battery/network/CPU waste
- Memory pressure, buffering, or startup/latency regressions users or operators would notice

## Ignore — DO NOT report these
- Micro-optimizations without measurable user-facing or production-facing impact
- Style feedback
- Snapshot record overhead from unconditional `mutableStateOf` writes (equality guard is nice but negligible)
- Small list allocations on recomposition (e.g., `.filter {}` on <20 items)
- `SharedTransitionLayout` vs `Crossfade` differences (unless in a scroll/animation hot path)
- `remember` vs `derivedStateOf` for cheap computations on small collections
- Single extra object allocation per recomposition or request

**Litmus test before reporting:** Would a user or operator ever notice this in production? Does it cause jank, ANR, latency spikes, memory pressure, throughput collapse, or battery drain? If neither, skip it.

## Applicability

Use this specialist for shared Kotlin performance risks across libraries, app layers, and backend services. Favor findings that would matter regardless of platform; leave UI-framework-specific or backend-transport-specific concerns to route-specific specialists.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-code-review-performance` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

### Shared Kotlin Performance
- Avoid repeated expensive work in hot paths when inputs are unchanged
- Watch for N+1 query/call patterns and redundant round-trips
- Keep blocking I/O and heavy CPU work off latency-sensitive threads or tight loops
- Reuse expensive clients, serializers, parsers, and caches where construction cost is significant
- Avoid per-item downstream calls inside large loops when batching or prefetching is feasible
- Bound pagination, batch sizes, queue drains, and in-memory buffering
- Use bounded retries with backoff and jitter for transient failures
- Large batch processing must avoid unbounded memory growth
- Watch for duplicate serialization, repeated auth lookups, or repeated config parsing inside hot paths
- Flag cache stampede or thundering-herd patterns only when they can realistically spike load or latency

## Output Rules
- Report at most 7 findings.
- Include expected impact statement (latency/memory/battery/startup/throughput) per finding.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Format

Every finding must use this exact bullet format for downstream tooling:

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Do NOT use markdown tables, numbered lists, or any other format for findings.
