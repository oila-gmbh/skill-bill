---
name: bill-typescript-code-review-reliability
description: Use when reviewing TypeScript promise observation, cancellation, queues, retries, streams, shutdown, telemetry, and partial failure.
internal-for: bill-code-review
---

# Reliability Review Specialist

## Focus

- Promise completion, causal errors, cancellation, timeouts, and stale work
- Queues, retries, idempotency, streams, resources, and partial progress
- Node process shutdown, browser lifecycle, telemetry, and recovery decisions

## Ignore

- Retries for permanent failures or unsafe non-idempotent operations
- Telemetry requests that cannot change an operator decision
- Scale scenarios outside repository-owned workloads

## Applicability

Apply the cancellation, queue, framework, process, and telemetry contracts actually configured for the affected Node, Deno, Bun, browser, worker, or edge runtime.

## Project-Specific Rules

### Promise and Cancellation Failure Rules

- Callers must observe every promise through `await`, return, or an explicit supervisor; flag unawaited or floating promises and async callbacks whose rejection becomes data loss or process instability. Verify `failure trace` before reporting this failure.
- `catch` blocks and framework handlers must preserve `cause` and stable error classification; reject swallowed failures that prevent retry, response mapping, or incident diagnosis. Verify `failure trace` before reporting this failure.
- `AbortSignal` or the repository cancellation token must propagate through fetches, streams, timers, and child tasks; prevent stale work from committing after a timeout or newer request. Verify `failure trace` before reporting this failure.
- Timeout races must clear losing timers and observe losing promises; flag a lifecycle leak or unhandled rejection after the caller has returned. Verify `failure trace` before reporting this failure.

### Queue, Retry, and Stream Contract Rules

- Queue producers must have bounded admission and consumers must enforce concurrency; reject memory growth, starvation, or overload when downstream capacity is exhausted. Verify `failure trace` before reporting this failure.
- Retries must classify transient failures, cap attempts with backoff, and preserve an `idempotency-key` or equivalent operation identity; prevent duplicate writes or messages. Verify `failure trace` before reporting this failure.
- Stream pipelines must propagate errors, cancellation, backpressure, and close signals across Node and Web stream adapters; flag truncation, resource leaks, or stuck consumers. Verify `failure trace` before reporting this failure.
- Partial multi-step operations must record progress or compensate according to the durable contract; reject a retry path that repeats completed side effects. Verify `failure trace` before reporting this failure.

### Shutdown and Observability Failure Rules

- Node, Bun, or Deno shutdown must stop intake, drain or abandon jobs by policy, close servers and pools, and respect `SIGTERM`; prevent deployment hangs or dropped acknowledged work. Verify `failure trace` before reporting this failure.
- Browser components and workers must remove listeners, subscriptions, channels, and timers on navigation or termination; flag stale updates and retained-resource leaks. Verify `failure trace` before reporting this failure.
- Logs, metrics, and traces must carry operation identity, failure class, attempt, and terminal outcome without exposing secrets; reject telemetry that hides timeout, cancellation, retry, or queue saturation. Verify `failure trace` before reporting this failure.
- Dependency degradation must have an explicit fallback, fail-closed, or fail-fast contract tested with repository fault injection; prevent cascading availability failure from ambiguous partial success. Verify `failure trace` before reporting this failure.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
