---
name: bill-rust-code-review-performance
description: Use when reviewing Rust latency, allocation, copying, contention, async scheduling, I/O, memory, and throughput regressions.
internal-for: bill-code-review
---

# Rust Performance Review

Report only production-relevant performance risks.

## Checks

- Flag repeated allocation, cloning, collection, parsing, serialization, or formatting on demonstrated hot paths.
- Check iterator and ownership choices for accidental full-buffer materialization or copies, without preferring clever borrowing over clarity absent impact.
- Detect blocking filesystem, database, crypto, compression, or CPU work on Tokio or async-std executor threads.
- Review mutex scope, lock ordering, contention, atomics, channels, and task fan-out for realistic latency or throughput collapse.
- Bound queues, streams, response bodies, batch sizes, concurrency, and retained buffers.
- Avoid repeated network/database calls and N+1 behavior when batching or streaming preserves contracts.
- Check Cargo feature changes for unexpectedly large dependency or binary-size costs on constrained targets.
- Require measurements or a credible workload before recommending unsafe code or complex allocation avoidance.

Skip micro-optimizations with no user, operator, memory, latency, or cost impact.
