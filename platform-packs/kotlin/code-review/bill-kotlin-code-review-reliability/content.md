---
name: bill-kotlin-code-review-reliability
description: Review Kotlin retries, timeouts, supervision, replay, shutdown, cleanup, ordering, and operational evidence.
internal-for: bill-code-review
---

# Reliability Review Specialist

## Focus

- Failure classification, bounded recovery, consumer lifecycle, durable ordering, cleanup, and telemetry

## Ignore

- Observability polish without an outage, loss, duplication, or recovery consequence

## Applicability

Use for Kotlin services, workers, schedulers, queues, caches, and downstream integrations.

## Project-Specific Rules

### Reliability Review Rules

- Require retries around `HttpClient.request` to classify transient, permanent, and authorization failure; retrying every error risks amplification and lockout.
- Reject unbounded `retryWhen` loops; require attempt limits, backoff, jitter, and a terminal outcome to prevent resource exhaustion.
- Verify `TimeoutCancellationException` remains cancellation-aware; treating it as ordinary failure can lose parent cancellation and duplicate effects.
- Require idempotency keys around retried writes such as `repository.insert`; absent replay protection risks duplicate durable state.
- Reject consumer acknowledgement before `transaction.commit`; a crash in that ordering can cause permanent data loss.
- Require `SupervisorJob` only for independently recoverable partitions; hiding a shared fatal dependency failure can leave invalid partial service state.
- Verify startup readiness follows successful migrations and client initialization; publishing readiness early risks request failure and corrupt partial startup.
- Require shutdown hooks to stop intake, drain bounded work, cancel scopes, and close `DataSource` in order; skipped stages risk incomplete shutdown.
- Reject suspending cleanup in `finally` without bounded `withContext(NonCancellable)` where durability requires completion; cancellation can leak resources.
- Require outbox records and business state to share one `@Transactional` boundary; separate commits risk missing or incorrectly ordered events.
- Verify circuit breakers such as `resilience4j` distinguish dependency timeout from caller cancellation; mixed signals can open circuits incorrectly.
- Require metrics and structured logs to expose attempts, terminal outcome, queue depth, and correlation ID; missing `retry_count` evidence makes operational failure invisible.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
