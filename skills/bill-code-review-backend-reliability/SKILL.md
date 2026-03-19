---
name: bill-code-review-backend-reliability
description: Review Kotlin backend/server reliability risks including timeouts, retries, background work, concurrency under load, caching, and observability-critical failures.
---

# Backend Reliability Review Specialist

Review only backend/service reliability issues that can cause outages, stuck work, runaway retries, or production incidents.

## Focus
- Timeout, retry, and backoff correctness
- Background jobs, consumers, schedulers, and replay safety
- Blocking work on request/event-loop threads
- Cache, queue, and downstream dependency failure behavior
- Logging/metrics/tracing gaps that hide real failures

## Ignore
- Pure style comments
- Tiny observability niceties without incident impact

## Applicability

Use this specialist for backend/server code only.

## Project Overrides

If an `AGENTS.md` file exists in the project root, read it and apply its rules alongside the defaults below. Project rules take precedence when they conflict.

## Project-Specific Rules

- Retries must be bounded and reserved for transient failures; include backoff and jitter where stampedes are possible
- Circuit breakers, bulkheads, and rate-limiting configuration must have sensible thresholds and avoid infinite blocks, silent drops, or retry storms
- External calls should have explicit timeout behavior and a clear cancellation story
- Message consumers and scheduled jobs must be safe under duplicate delivery, replay, or partial failure
- Acknowledge/commit work only after durable success, not before
- Avoid blocking request/event-loop threads with slow I/O or heavy CPU work
- Cache fill, refresh, and invalidation logic must not create obvious thundering-herd or stale-data incidents
- Degradation and fallback behavior should fail gracefully and make partial availability explicit where clients or operators need to know
- Logging, metrics, and tracing should include enough contextual identifiers to debug failures without leaking secrets or PII
- Startup and shutdown hooks must initialize and close long-lived resources predictably

## Output Rules
- Report at most 7 findings.
- Include production failure scenario for each Major/Blocker.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Table
| Area | Severity | Confidence | Evidence | Why it matters | Minimal fix |
|------|----------|------------|----------|----------------|-------------|
