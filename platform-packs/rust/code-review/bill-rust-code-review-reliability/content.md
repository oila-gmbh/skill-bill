---
name: bill-rust-code-review-reliability
description: Use when reviewing Rust async task lifecycle, cancellation, retries, timeouts, backpressure, cleanup, degradation, and observability.
internal-for: bill-code-review
---

# Rust Reliability Review

Review production failure behavior and recovery.

## Checks

- Give spawned Tokio or async-std tasks explicit ownership, shutdown, join, error propagation, and panic handling.
- Propagate cancellation and deadlines; do not detach work whose success the caller reports or whose resources outlive the request unexpectedly.
- Bound channels, queues, concurrent futures, stream buffering, retries, and in-flight work to preserve backpressure.
- Use retry policies only for safe, transient operations with bounded attempts, jitter, deadlines, and idempotency.
- Avoid blocking calls or long-held synchronous locks on async executor threads.
- Release permits, locks, temporary files, sockets, transactions, and partial state on errors, cancellation, and panic boundaries.
- Distinguish graceful degradation from silent data loss or false success.
- Include actionable structured logs, metrics, and traces without sensitive data or unbounded cardinality.
- Verify supervisors, workers, consumers, and schedulers surface terminal failures and do not spin or stall silently.

Ground findings in a concrete outage, leak, overload, lost-work, or observability scenario.
