---
name: bill-typescript-code-review-reliability
description: Use when reviewing TypeScript promises, cancellation, retries, timeouts, queues, shutdown, observability, and degradation.
internal-for: bill-code-review
---

# Reliability Review Specialist

## Focus

- Awaiting, rejection and error propagation, cancellation, timeouts, retries, and partial progress
- Queues, concurrency limits, backpressure, workers, timers, and orphan work
- Node process lifecycle, browser component lifecycle, and graceful shutdown
- External dependency failure, logging, metrics, tracing, and recovery

## Ignore

- Requests for retries when repetition is unsafe or failure is permanent
- Observability preferences without an operational decision
- Hypothetical scale outside documented workloads

## Project-Specific Rules

- Flag unawaited or floating promises and async callbacks whose callers cannot observe rejection or completion.
- Preserve causal errors through `await`, `catch`, promise chains, and framework handlers; do not swallow rejections.
- Use `AbortSignal` or the repository's cancellation contract consistently and clean up listeners, timers, streams, and resources.
- Bound concurrency, queues, retained promises, retry attempts, and response buffering.
- Prevent races where stale async completions overwrite newer state or duplicate side effects.
- Graceful shutdown must stop intake, settle or abandon work by policy, close resources, and terminate.
- Distinguish timeout, cancellation, retryable failure, permanent rejection, and programmer error in reporting.
- Findings must describe the production failure sequence and observable impact.
