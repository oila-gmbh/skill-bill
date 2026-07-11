---
name: bill-rust-code-review-reliability
description: Use when reviewing Rust cancellation, backpressure, retries, timeouts, task supervision, runtime shutdown, observability, and degradation.
internal-for: bill-code-review
---

# Reliability Review Specialist

## Focus

- Cancellation safety, timeout coverage, retries, idempotency, and partial progress
- Bounded channels, backpressure, task supervision, joins, panics, and orphan work
- tokio/async-std runtime startup and graceful shutdown
- External client failure, degradation, logging, metrics, tracing, and recovery

## Ignore

- Requests for retries where failure is permanent or repetition is unsafe
- Observability preferences without an operational decision they support
- Hypothetical scale concerns outside documented workloads or limits

## Applicability

Apply to async tasks, services, workers, queues, schedulers, network clients, caches, and lifecycle code.

## Project-Specific Rules

- Define what happens when each future is dropped at every `.await` that follows a side effect or state mutation.
- Bound queues, concurrency, retry attempts, and retained task handles; propagate backpressure instead of hiding overload.
- Distinguish timeout, cancellation, retryable remote failure, permanent rejection, and internal invariant failure in `Result` contracts.
- Supervise spawned tasks and surface panics or errors; detached tasks require explicit ownership and shutdown semantics.
- Graceful shutdown must stop intake, drain or abandon by policy, release permits and guards, flush durable state, and terminate.
- Avoid retry storms with deadlines, jitter, budgets, and idempotency appropriate to the operation.
- Findings must describe the production failure sequence and use only the shared Risk Register and canonical severities.
