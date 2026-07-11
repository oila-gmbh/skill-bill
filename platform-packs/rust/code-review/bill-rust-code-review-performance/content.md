---
name: bill-rust-code-review-performance
description: Use when reviewing Rust allocation, cloning, locking, atomics, async scheduling, serialization, I/O, and hot-path regressions.
internal-for: bill-code-review
---

# Performance Review Specialist

## Focus

- Unnecessary allocation, cloning, boxing, collection growth, and serialization copies
- Lock scope, contention, atomics, channels, task spawning, and async executor pressure
- Blocking I/O or CPU work on tokio/async-std executor threads
- Repeated database/network/filesystem work and algorithmic regressions on measured hot paths

## Ignore

- Micro-optimizations without evidence that the path matters
- Replacing clear safe code with `unsafe` for speculative speed
- Mechanical preference for borrowing when ownership makes the API clearer and cost is immaterial

## Applicability

Apply when changed Rust code affects throughput, latency, memory, startup, binary size, or resource bounds. Use repository benchmarks, profiles, limits, and workload assumptions as evidence.

## Project-Specific Rules

- Flag `.clone()` only when the copied value, frequency, or retained lifetime creates material cost.
- Watch mutex or read/write guards held across `.await`; require intentional ownership and lock duration.
- Use `spawn_blocking` or the repository's equivalent for blocking work only with bounded concurrency and shutdown behavior.
- Ensure streams, queues, buffers, and task fan-out have explicit bounds or defensible backpressure.
- Check iterator pipelines and async streams for accidental eager collection, repeated traversal, or lost batching.
- Check feature flags and monomorphization for material build-time or binary-size expansion when that is a product constraint.
- Findings must state the hot path, scale factor, and expected impact, not merely name an allocation or lock.
- Use only the shared Risk Register and canonical severity definitions.
