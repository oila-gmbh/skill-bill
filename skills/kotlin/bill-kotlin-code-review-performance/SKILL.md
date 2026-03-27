---
name: bill-kotlin-code-review-performance
description: Use when reviewing performance risks in Android, KMP, backend/server, and general Kotlin code, including UI jank, hot-path work, blocking I/O, and resource waste.
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

Apply shared Kotlin performance rules everywhere. Apply Android/KMP-only rules only when Android/KMP signals are present. Apply backend/server-only rules only when backend/server signals are present.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-code-review-performance` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

### Shared Kotlin Performance
- Avoid repeated expensive work in hot paths when inputs are unchanged
- Watch for N+1 query/call patterns and redundant round-trips
- Use bounded retries with backoff and jitter for transient failures
- Large batch processing must avoid unbounded memory growth

### Android/KMP-Specific Rules

#### Compose Recomposition
- `LaunchedEffect` keys must be stable — using full data objects causes unnecessary restarts; derive a stable boolean or ID instead
- `List<T>` where `T` is stable is inferred stable — do not flag as instability
- Verify `remember` keys match actual dependencies

#### Database
- Use atomic SQL updates over load-modify-save patterns
- Use bulk operations (`insertMany`/`updateMany`) instead of N individual calls in loops
- Transactions only for multi-table operations, not simple reads
- Watch for N+1 query patterns

#### Dispatchers
- Never use `Dispatchers.*` directly when the project standard is `DispatcherProvider`
- Heavy computation must not run on Main dispatcher

#### Image Loading
- If the project uses Coil/Compose image loading, verify proper caching and sizing

### Backend/Server-Specific Rules
- Do not perform blocking work on event-loop or request-processing threads without explicit offloading
- Watch for per-item downstream calls inside request handlers, consumers, or schedulers
- Reuse expensive clients/serializers/parsers where construction cost is significant instead of rebuilding them per request
- Bound pagination, batch sizes, queue drains, and in-memory buffering
- Flag cache stampede or thundering-herd patterns only when they can realistically spike load or latency
- Watch for duplicate serialization, duplicate auth lookups, or repeated config parsing inside hot paths

## Output Rules
- Report at most 7 findings.
- Include expected impact statement (latency/memory/battery/startup/throughput) per finding.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Table
| Area | Severity | Confidence | Evidence | Why it matters | Minimal fix |
|------|----------|------------|----------|----------------|-------------|
