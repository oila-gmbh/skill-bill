---
name: bill-python-code-review-performance
description: Review Python performance risks in hot paths, database access, network/filesystem work, memory use, imports, batching, streaming, and async/blocking boundaries.
internal-for: bill-code-review
---

# Python Performance Review

Focus on changed code that can add avoidable latency, throughput loss, or memory pressure.

## Review Focus

- Hot paths: request handlers, loops over large collections, serializers, CLI batch jobs, workers, imports executed on startup, and per-item object churn.
- Database and network access: N+1 queries, repeated downstream calls, missing batching, unbounded pagination, inefficient filters, and synchronous I/O in async paths.
- Filesystem and serialization: repeated reads/writes, large JSON/YAML loads, archive processing, streaming vs buffering, compression, and temporary file usage.
- Memory behavior: accumulating lists where iterators/streams would work, caching without bounds, pandas/notebook data growth, and long-lived worker state.
- Async/blocking mismatch: blocking libraries in event loops, inappropriate thread pools, too much concurrency, and missing cancellation or timeout behavior.

## Findings Standard

Report performance risks with a plausible input size, call frequency, or operational path. Avoid micro-optimization unless the diff touches a measured or obviously hot path.
