---
name: bill-python-code-review-reliability
description: Review Python reliability risks in external clients, retries, timeouts, workers, schedulers, queues, observability, and graceful degradation.
internal-for: bill-code-review
---

# Python Reliability Review

Review behavior under dependency failure, load, worker restarts, and deploy operations.

## Focus

- External clients, timeouts, retries, queues, workers, schedulers, caches, observability, replay, shutdown, and graceful degradation

## Ignore

- Hypothetical operational concerns without a reachable failure path or ownership boundary
- Telemetry naming preferences that cannot hide, misclassify, or expose a production failure

## Applicability

Use this specialist for `requests`, `httpx`, external clients, Celery, RQ, Arq, Dramatiq, schedulers, cron jobs, queues, caches, long-running commands, and operational lifecycle code.

## Project-Specific Rules

### Clients, Retries, and Replay

- Require an explicit connect/read or total timeout on every outbound `requests` and `httpx` call because neither should inherit an assumed safe application default.
- Require bounded retries with backoff, error classification, idempotency, rate-limit handling, response validation, and backpressure; reject retry storms and unbounded replay.
- Require after-commit or outbox dispatch when a task or event depends on durable state; reject enqueue-before-commit paths that can expose missing or rolled-back data.
- Require cache thundering-herd prevention for hot misses and never hold a local or distributed lock across remote I/O because dependency latency can block unrelated work.

### Workers and Operations

- Verify Celery `acks_late` together with broker visibility-timeout behavior so a slow or crashed task is neither lost nor redelivered concurrently without an idempotency guard.
- Require queue acknowledgement only after durable success and require explicit poison-message limits, dead-lettering, or quarantine so permanent failures cannot loop forever.
- Reject leaked asyncio tasks and blocking calls on the event loop; require explicit task ownership, shutdown cancellation, and cleanup on partial failure.
- Require structured logs, metrics, traces, correlation identifiers, and alertable error signals without sensitive data so dependency failures and degradation remain observable.
- Verify long-running commands, backfills, migrations, file descriptors, memory growth, safe interruption, fallback behavior, and deploy sequencing cannot create operator blind spots or user-visible incidents.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
