---
name: bill-go-code-review-performance
description: Use when reviewing performance risks in Go backend/service code, including hot-path work, blocking I/O, query-shape issues, inefficient DB/network access, marshaling overhead, buffering, goroutine churn, and resource waste.
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
- Single extra allocations with no realistic impact
- Small collection reshaping with no hot-path evidence
- Minor refactors that are only theoretically faster

**Litmus test before reporting:** Would a user or operator ever notice this in production? Does it cause latency
spikes, throughput collapse, memory pressure, queue backlog, or operational cost growth? If neither, skip it.

## Applicability

Apply shared performance rules to backend/service code. Apply the deeper DB/query-shape, marshaling, and worker-path
checks only when the changed code uses those mechanisms.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-go-code-review-performance`
section, read that section and apply it as the highest-priority instruction for this skill. The matching section may
refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

### Shared Backend Performance
- Avoid repeated expensive work in hot paths when inputs are unchanged
- Watch for N+1 query/call patterns and redundant round-trips
- Retry behavior must not amplify latency, downstream load, or CPU/network waste under failure
- Large batch processing must avoid unbounded memory growth

### Backend/Service-Specific Rules
- Do not perform blocking or heavy work on latency-sensitive request or worker execution paths without explicit offloading or batching strategy
- Watch for per-item downstream calls inside request handlers, consumers, schedulers, or batch loops
- Reuse expensive clients, encoders/decoders, buffers, and parsers where construction cost is significant instead of rebuilding them per request/job
- Bound pagination, batch sizes, queue drains, and in-memory buffering
- Flag cache stampede or thundering-herd patterns when they can realistically spike load, latency, or infrastructure cost
- Watch for duplicate marshaling/unmarshaling, repeated auth lookups, or repeated config parsing inside hot paths
- Worker pools, fan-out concurrency, and batch processing must use bounded concurrency and avoid goroutine storms
- Queue/batch processing must use bounded chunk sizes and avoid loading unbounded payloads or record sets into memory
- Cache keys, cache cardinality, and invalidation scope must not create unbounded memory growth or low-hit-rate caches
- Large payload paths should prefer streaming or chunked processing over whole-buffer reads/writes when buffering would materially increase memory pressure or copy cost
- Hot loops over large data sets should pre-size slices/maps or reuse scratch buffers only when the workload is repeated enough to affect latency, throughput, or memory materially

### Persistence / Query Shape
- Query paths must not load full row sets when the hot path only needs a small slice of fields or a scalar result
- Count, exists, and aggregate paths must not hydrate large record graphs when direct queries would preserve behavior
- Avoid hydration-heavy loops when scalar queries, chunking, streaming, or bulk operations would preserve behavior with lower cost

### Marshaling / Response Paths
- API and event serialization paths must not repeatedly compute or re-query the same data without need
- Response shaping must not trigger hidden repeated work on large result sets or large payloads
- Streaming and buffering choices must stay bounded and avoid needless copies when payloads are large or repeated

## Output Rules
- Report at most 7 findings.
- Include expected impact statement (latency, memory, throughput, queue backlog, or infrastructure cost) per finding.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Table
| Area | Severity | Confidence | Evidence | Why it matters | Minimal fix |
|------|----------|------------|----------|----------------|-------------|
