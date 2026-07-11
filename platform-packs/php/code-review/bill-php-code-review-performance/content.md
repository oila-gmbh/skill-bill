---
name: bill-php-code-review-performance
description: Use when reviewing PHP performance risks including hot paths, repeated database or network work, serialization waste, memory pressure, and throughput regressions.
internal-for: bill-code-review
---

# Performance Review Specialist

Review only high-impact performance issues.

## Focus

- Blocking or heavy work on latency-sensitive request or worker paths
- Expensive or repeated work in hot paths
- Inefficient DB/network access patterns
- Retry/backoff inefficiency and CPU/network waste
- Memory pressure, buffering, or throughput regressions users or operators would notice

## Ignore

- Micro-optimizations without measurable user-facing or production-facing impact
- Style feedback
- Single extra object allocations with no realistic impact
- Small collection reshaping with no hot-path evidence
- Minor refactors that are only theoretically faster

Litmus test before reporting: would a user or operator ever notice this in production? If not, skip it.

## Applicability

Use this specialist for PHP backend/service, worker, batch, queue, API serialization, and server-rendered paths where changed code can affect latency, throughput, memory, or infrastructure cost.

## Project-Specific Rules

### Shared Backend Performance

- Avoid repeated expensive work in hot paths when inputs are unchanged
- Watch for `N+1` query/call patterns and redundant round-trips
- Retry behavior must not amplify latency, downstream load, or CPU/network waste under failure
- Large batch processing must avoid unbounded memory growth

### Backend/Server-Specific Rules

- Do not perform blocking or heavy work on latency-sensitive request or worker execution paths without explicit offloading or batching strategy
- Watch for per-item downstream calls inside request handlers, consumers, schedulers, or batch loops
- Watch for cross-module enrichment loops that turn one logical read into repeated port/adapter calls or repeated queries
- Reuse expensive clients, serializers, and parsers where construction cost is significant instead of rebuilding them per request/job
- Bound pagination, batch sizes, queue drains, and in-memory buffering
- Long-running workers must clear job-specific context, large temporary buffers, and ORM/unit-of-work state between jobs so memory, tenant/user context leakage, and stale-state risks stay bounded
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
- In findings, state the expected production impact such as latency, memory pressure, throughput loss, infrastructure cost, or user-visible slowdown
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
