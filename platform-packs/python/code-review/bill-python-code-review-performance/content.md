---
name: bill-python-code-review-performance
description: Review Python performance risks in hot paths, database access, network/filesystem work, memory use, imports, batching, streaming, and async/blocking boundaries.
internal-for: bill-code-review
---

# Python Performance Review

Review avoidable latency, throughput loss, memory pressure, and resource exhaustion.

## Focus

- Hot paths, ORM query shape, repeated I/O, batching, streaming, memory growth, worker state, and async/blocking boundaries

## Ignore

- Micro-optimizations, small allocations, formatting, or style changes without an operator-noticeable latency, memory, throughput, or resource impact
- Cold paths with bounded inputs unless the diff supplies measurements or a realistic amplification path

## Applicability

Use this specialist for request handlers, serializers, batch jobs, workers, imports, large collections, database/network/filesystem paths, pandas or notebook workloads, and async code.

## Project-Specific Rules

### ORM and Data Shape

- Verify Django `QuerySet` laziness is preserved and reject accidental evaluation, repeated iteration, or serialization-triggered relationship loads that create N+1 queries.
- Reject `count()` or `exists()` logic that hydrates full rows, and reject hydration-heavy loops when projection, aggregation, batching, or streaming would avoid operator-noticeable cost.
- Require bounded pagination, batches, caches, queues, temporary buffers, and concurrency for realistic input sizes.

### Execution and Resource Use

- Reject synchronous database, network, filesystem, compression, or serialization work on the event loop; require GIL-aware isolation with `asyncio.to_thread`, `run_in_executor`, a process pool, or an owned worker boundary as appropriate.
- Require cancellation propagation and explicit timeouts for async or remote work; reject paths that can continue consuming event-loop, worker, or dependency capacity after the caller has stopped waiting.
- Require repeated downstream calls and per-item filesystem operations to batch, cache, or stream when call frequency creates a plausible throughput failure.
- Require cache stampede protection for concurrently missed hot keys and require long-lived workers to clear request, job, dataframe, or model state that would otherwise accumulate across executions.
- Reject unbounded list accumulation, full-file JSON/YAML/archive buffering, cache growth, import-time work, or per-item object churn on measured or obviously hot paths.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
