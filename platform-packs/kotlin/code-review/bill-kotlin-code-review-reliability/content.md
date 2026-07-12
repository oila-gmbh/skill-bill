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
- Require a locally owned `TimeoutCancellationException`, which may become a domain timeout at that boundary, to remain distinct from parent or sibling `CancellationException`, which must propagate; broad recovery otherwise risks cancellation failure, abandoned work, and duplicate effects.
- Require idempotency keys around retried writes such as `repository.insert`; absent replay protection risks duplicate durable state.
- For the detected broker, verify `acknowledge` or offset commit occurs only after the corresponding durable state reaches its required commit point; exact ack, transaction, redelivery, and offset semantics are framework-specific, but a crash must not turn incomplete work into permanent loss.
- Require `SupervisorJob` only for independently recoverable partitions; hiding a shared fatal dependency failure can leave invalid partial service state.
- Verify the `readiness` signal follows the migrations, clients, subscriptions, and warmup actually required by the detected service; publishing readiness before required dependencies risks request failure or consumption in an invalid partial state.
- Require shutdown hooks to stop intake, apply the broker's drain or rebalance contract, await bounded in-flight work, cancel remaining scopes, and `close` resources in dependency order; skipped stages risk loss, duplication, or use-after-close failure.
- Require bounded `withContext(NonCancellable)` only for cleanup that must finish despite cancellation, and keep that region minimal; an unbounded non-cancellable region risks shutdown timeout, while ordinary cleanup should remain cancellable.
- Require outbox records and business state to share one `@Transactional` boundary; separate commits risk missing or incorrectly ordered events.
- When a circuit-breaker library such as `resilience4j` is detected, verify its configured failure predicate distinguishes dependency failures and timeouts from caller cancellation and locally excluded outcomes; counting the wrong signals can open or hold a circuit incorrectly.
- Require metrics and structured logs to expose attempts, terminal outcome, queue depth, and correlation ID; missing `retry_count` evidence makes operational failure invisible.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
