---
name: bill-kotlin-code-review-reliability
description: Use when reviewing Kotlin backend/server reliability risks including timeouts, retries, background work, concurrency under load, caching, and observability-critical failures. Use when user mentions timeout, retry logic, circuit breaker, caching, or observability in Kotlin backend.
internal-for: bill-code-review
---

# Backend Reliability Review Specialist

Review service behavior that can cause outages, stuck work, duplication, or invisible failure.

## Focus

- Consumer isolation, timeout classification, retries, replay, durable publication, and failure telemetry

## Ignore

- Tiny observability niceties without incident or operational impact

## Applicability

Use this specialist for Kotlin services, workers, consumers, schedulers, queues, caches, and downstream integrations.

## Project-Specific Rules

### Isolation and Retry

- Require `SupervisorJob` for long-lived consumers whose sibling partitions or handlers must continue after one child fails.
- Preserve `TimeoutCancellationException` as cancellation or a timeout category; reject classification as an ordinary retriable business error when that causes duplicate work or defeats structured cancellation.
- Reject `Thread.sleep` in coroutine retry loops; require cancellable `delay` with bounded attempts, backoff, and jitter for transient failures.
- Distinguish poison, transient, and permanent failures in retry decisions and telemetry so operators can identify drops, dead letters, and retry storms.

### Replay and Durable Ordering

- Require replay, rebuild, and republish flows to be bounded, observable, and safe to rerun without duplicating durable or user-visible effects.
- Acknowledge or commit consumed work only after durable success.
- Require event publication after commit or through an outbox so events never describe rolled-back state and committed state is not left unpublished.
- Require explicit timeouts, bounded concurrency, and cleanup for external calls and long-lived resources.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
