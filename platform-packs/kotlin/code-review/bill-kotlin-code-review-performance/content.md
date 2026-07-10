---
name: bill-kotlin-code-review-performance
description: Use when reviewing performance risks in Kotlin code, including hot-path work, blocking I/O, latency regressions, and resource waste. Use when user mentions performance, blocking I/O, hot path, memory leak, or latency in Kotlin code.
internal-for: bill-code-review
---

# Performance Review Specialist

Review only measurable user-facing or production-facing performance failures.

## Focus

- Blocking work, dispatcher saturation, Flow backpressure, collection cost, database over-fetching, and lazy-load amplification

## Ignore

- Micro-optimizations, small allocations, or style changes without evidence of latency, memory, throughput, or resource impact

## Applicability

Use this specialist for Kotlin libraries and services where execution context, data volume, I/O, or serialization can create observable cost.

## Project-Specific Rules

### Execution and Backpressure

- Reject `runBlocking` inside a coroutine or on `Dispatchers.Default`; it blocks worker threads and can starve unrelated coroutine work.
- Reject blocking JDBC or filesystem I/O outside `Dispatchers.IO` or a repository-owned blocking dispatcher.
- Do not treat the `Dispatchers.IO` ceiling as an unlimited safety boundary; reject unbounded blocking fan-out that can exhaust threads, connections, or downstream capacity.
- Verify `flowOn` is placed upstream of the work it should move and require bounded `buffer` or explicit backpressure where producer/consumer rates diverge.

### Allocation and Data Access

- Prefer `asSequence` or a single-pass operation when a large eager `map`/`filter` chain creates avoidable intermediate collections on a hot path.
- Reject full-row or full-entity hydration for count, exists, or aggregate operations when the datastore can compute the answer directly.
- Reject serialization that traverses lazy ORM relationships and triggers N+1 queries or detached-session failures.
- Require batches, queues, retries, and in-memory buffers to remain bounded under realistic load.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
