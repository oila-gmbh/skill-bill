---
name: bill-python-code-review-reliability
description: Review Python reliability risks in external clients, retries, timeouts, workers, schedulers, queues, observability, and graceful degradation.
internal-for: bill-code-review
---

# Python Reliability Review

Focus on how changed Python code behaves under failure, load, and operations.

## Review Focus

- External clients: explicit timeouts, retries with backoff, error classification, idempotency, rate limits, circuit-breaking, and response validation.
- Background work: Celery/RQ/Arq/Dramatiq/tasks, schedulers, cron-style jobs, queue acknowledgment, deduplication, poison-message handling, and shutdown behavior.
- Failure handling: exception boundaries, cleanup on partial failure, fallback behavior, graceful degradation, backpressure, and avoiding retry storms.
- Observability: structured logs, metrics, traces, correlation IDs, domain events, alertable error signals, and avoiding noisy or sensitive logs.
- Operational compatibility: migrations/backfills, long-running commands, memory growth, file descriptor leaks, and safe cancellation/interruption.

## Findings Standard

Report risks that would turn routine dependency failures, worker restarts, traffic spikes, or deploy sequencing into user-visible incidents or operator blind spots.
