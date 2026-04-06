---
name: bill-php-code-review-performance
description: Use when reviewing performance risks in PHP backend/server code, including hot-path work, blocking I/O, ORM/query-shape issues, inefficient DB/network access, serialization/rendering overhead, buffering, and resource waste. Use when user mentions N+1, query performance, rendering waste, serialization overhead, or hot path in PHP.
---

# Performance Review Specialist

Review only high-impact performance issues.

## Focus
- Blocking or heavy work on latency-sensitive request or worker paths
- Expensive or repeated work in hot paths
- Inefficient DB/network access patterns (N+1, redundant calls)
- Retry/backoff inefficiency and CPU/network waste
- Memory pressure, buffering, or throughput regressions users or operators would notice

## Ignore — DO NOT report these
- Micro-optimizations without measurable user-facing or production-facing impact
- Style feedback
- Single extra object allocations with no realistic impact
- Small collection reshaping with no hot-path evidence
- Minor refactors that are only theoretically faster

**Litmus test before reporting:** Would a user or operator ever notice this in production? Does it cause latency spikes, throughput collapse, memory pressure, queue backlog, or operational cost growth? If neither, skip it.

## Applicability

Apply shared performance rules to backend/server code. Apply the deeper ORM, query-shape, serialization, and rendering-path checks only when the changed code uses those mechanisms.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-php-code-review-performance` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

### Shared Backend Performance
- Avoid repeated expensive work in hot paths when inputs are unchanged
- Watch for N+1 query/call patterns and redundant round-trips
- Retry behavior must not amplify latency, downstream load, or CPU/network waste under failure
- Large batch processing must avoid unbounded memory growth

### Backend/Server-Specific Rules
- Do not perform blocking or heavy work on latency-sensitive request or worker execution paths without explicit offloading or batching strategy
- Watch for per-item downstream calls inside request handlers, consumers, schedulers, or batch loops
- Watch for cross-module enrichment loops that turn one logical read into repeated port/adapter calls or repeated queries
- Reuse expensive clients, serializers, and parsers where construction cost is significant instead of rebuilding them per request/job
- Bound pagination, batch sizes, queue drains, and in-memory buffering
- Flag cache stampede or thundering-herd patterns when they can realistically spike load, latency, or infrastructure cost
- Watch for duplicate serialization, duplicate auth lookups, or repeated config parsing inside hot paths
- Projection rebuilds, feed generation, ranking, and backfill jobs must avoid quadratic work or repeated per-item lookups when batch access is possible
- Queue/batch processing must use bounded chunk sizes and avoid loading unbounded job payloads or record sets into memory
- Cache keys, cache cardinality, and invalidation scope must not create unbounded memory growth or low-hit-rate caches

### ORM / Query Shape
- ORM-backed reads must not load large relation graphs or whole record sets when the hot path only needs a small slice of fields
- Count, exists, and aggregate paths must not load full rows or hydrated models when scalar queries would preserve behavior
- Avoid hydration-heavy loops when scalar queries, chunking, streaming, or bulk operations would preserve behavior with lower cost
- Collection pipelines, eager/lazy loading choices, and accessor/appended attribute usage must not introduce hidden repeated work in hot paths

### Serialization / Rendering Paths
- Server-rendered HTML, component rendering, and API/resource serialization paths must not repeatedly compute or re-query the same data without need
- Serialization and response shaping must not trigger hidden lazy loads or repeated transformation work on large result sets

## Output Rules
- Report at most 7 findings.
- Include expected impact statement (latency, memory, throughput, queue backlog, or infrastructure cost) per finding.
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
