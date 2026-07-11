---
name: bill-typescript-code-review-performance
description: Use when reviewing TypeScript event-loop blocking, allocation, rendering, bundle size, async scheduling, serialization, I/O, and hot paths.
internal-for: bill-code-review
---

# Performance Review Specialist

## Focus

- Event-loop blocking, synchronous I/O, CPU-heavy loops, and excessive microtasks
- Repeated allocation, copying, parsing, serialization, and collection traversal
- Promise fan-out, unbounded concurrency, repeated network/database work, and lost batching
- Browser bundles, rendering frequency, hydration, listeners, and retained object graphs

## Ignore

- Micro-optimizations without evidence that the path matters
- Type-level changes that do not alter emitted JavaScript
- Memoization or concurrency added without a measured need

## Applicability

Use this specialist when changed TypeScript affects event-loop work, resource use, rendering, bundles, I/O, or another measured hot path.

## Project-Specific Rules

### TypeScript Performance Rules

- Verify `TypeScript hot-path and resource APIs` preserve scale invariants; reject a measurable latency, memory, or throughput failure.
- Identify the hot path, expected scale, and runtime target before reporting cost.
- Bound `Promise.all` inputs and worker/task fan-out where input size can grow.
- Avoid sync filesystem, crypto, compression, or parsing work on latency-sensitive Node request paths.
- Check object spread, array chaining, JSON conversion, and deep cloning for material repeated work.
- Ensure caches and memoization have stable keys, invalidation, and bounded retention.
- Confirm type-only imports and server-only dependencies do not inflate browser bundles.
- In TSX, check unstable props, effects, subscriptions, and state updates for avoidable render loops.
- Findings must state the scale factor and expected latency, throughput, memory, or bundle impact.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
