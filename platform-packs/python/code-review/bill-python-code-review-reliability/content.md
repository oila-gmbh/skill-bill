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

### Python Reliability Rules

- Require explicit `requests.Timeout` values or `httpx.Timeout` connect, read, write, and pool limits for every outbound client path; absent limits leak sockets and cause cascading timeouts.
- Verify retries configured through `urllib3.Retry`, `tenacity`, or repository policy classify transient failures, honor `Retry-After`, add jitter, and stop after a bound; indiscriminate replay risks storms and invalid duplicate writes.
- Require an idempotency key, durable deduplication record, or safe operation contract before retrying mutations; process restarts or client retries can otherwise corrupt state through duplication.
- Require bounded `asyncio.Queue`, broker prefetch, or worker concurrency to apply backpressure; unbounded intake exhausts memory and turns dependency slowdown into service failure.
- Verify poison messages have an attempt limit and a dead-letter or quarantine path in Celery, RQ, Arq, or Dramatiq configuration; permanent failures must not loop forever and starve valid work.
- Require Django `transaction.on_commit` or an atomic outbox before publishing work derived from database state; enqueue-before-commit can expose missing data or lose delivery after rollback.
- When Celery is detected, verify `acks_late`, task idempotency, and broker `visibility_timeout` agree with maximum runtime; mismatches risk task loss or concurrent redelivery.
- Require owned `asyncio.TaskGroup` cancellation and awaited cleanup during partial failure; orphaned tasks can mutate state after the request reports failure.
- Verify `Executor.shutdown(wait=True, cancel_futures=True)` or equivalent service teardown stops admissions before resources close; leaked executor work delays deploy shutdown and accesses invalid clients.
- Require graceful worker shutdown to stop producers, drain or explicitly reject queued work, and close `httpx.AsyncClient`, sessions, and file descriptors; reversed lifecycle ordering causes lost jobs and resource leaks.
- Require structured `logging`, metrics, trace correlation, and terminal error status for retry exhaustion and degraded fallbacks; silent failure paths prevent operators from detecting data loss or availability regressions.
- Verify circuit-breaker recovery probes and fallback data are bounded and visibly stale through the repository telemetry contract; permanent open state or hidden stale responses break service contracts.
- Require scheduler jobs to hold a distributed lease or use an idempotent `Celery beat` task contract; overlapping executions race and can corrupt durable state.
- Verify worker health and readiness expose broker, database, and dependency failure through configured `logging` and metrics; false-ready processes cause routing and availability failures.
- Reject broad `except Exception` acknowledgement in RQ, Celery, or Dramatiq handlers; swallowed errors lose failure identity and can mark invalid work successful.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
