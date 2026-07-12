---
name: bill-kotlin-code-review-performance
description: Review Kotlin blocking, allocation, Flow, serialization, persistence, queue, and measurement performance risks.
internal-for: bill-code-review
---

# Performance Review Specialist

## Focus

- Dispatcher capacity, backpressure, allocation, serialization, ORM access, queues, and evidence

## Ignore

- Micro-optimizations without measurable latency, memory, throughput, or resource impact

## Applicability

Use when Kotlin execution context, data volume, I/O, or framework behavior can create observable cost.

## Project-Specific Rules

### Performance Review Rules

- Reject `runBlocking` inside suspend code because it consumes a worker and risks dispatcher starvation under concurrent load.
- Require JDBC, filesystem, and legacy client calls to run on `Dispatchers.IO` or a bounded owned dispatcher; blocking the default pool causes latency failure.
- Verify `Dispatchers.IO.limitedParallelism` and connection-pool capacity agree; excessive fan-out can exhaust resources and amplify timeouts.
- Require upstream placement of `flowOn` around expensive production; misplaced context changes leave CPU or blocking work on the collector and cause performance regression.
- Reject unbounded `buffer`, `Channel.UNLIMITED`, or queue accumulation because a fast producer can cause memory failure before backpressure arrives.
- Verify large `map` and `filter` chains with `async-profiler` or allocation measurements; eager intermediates can create avoidable memory pressure.
- Require `sequence` or single-pass processing only when `jmh` evidence shows collection allocation affects the hot path; speculative rewrites risk slower behavior.
- Reject `Json.encodeToString` repetition for invariant payloads on a hot path when profiling shows serialization latency and allocation regression.
- Require ORM projections for count, existence, and summary queries; hydrating full `@Entity` graphs risks N+1 access and resource exhaustion.
- Verify lazy associations are not traversed during serialization; `Hibernate.initialize` cascades can cause query storms or session failure.
- Require `batchSize`, semaphore, or rate limits around queue consumers; unbounded parallel launches risk downstream timeout and connection starvation.
- Reject performance claims without reproducible `jmh`, load-test, trace, or profiler evidence because unmeasured changes can hide throughput regression.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
