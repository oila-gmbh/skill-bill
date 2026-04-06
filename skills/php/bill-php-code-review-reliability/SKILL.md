---
name: bill-php-code-review-reliability
description: Use when reviewing PHP backend/server reliability risks including timeouts, retries, background work, concurrency under load, caching, and observability-critical failures. Use when user mentions job reliability, queue retry, cache invalidation, external client timeout, or observability in PHP.
---

# Backend Reliability Review Specialist

Review only backend/service reliability issues that can cause outages, stuck work, runaway retries, or production incidents.

## Focus
- Timeout, retry, and backoff correctness
- Background jobs, consumers, schedulers, and replay safety
- Blocking or heavy work on latency-sensitive request or worker execution paths
- Cache, queue, and downstream dependency failure behavior
- Logging/metrics/tracing gaps that hide real failures

## Ignore
- Pure style comments
- Tiny observability niceties without incident impact

## Applicability

Use this specialist for backend/server code only.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-php-code-review-reliability` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

- Retries must be bounded and reserved for transient failures; include backoff and jitter where stampedes are possible
- Circuit breakers, bulkheads, and rate-limiting configuration must have sensible thresholds and avoid infinite blocks, silent drops, or retry storms
- External calls should have explicit timeout behavior and a clear cancellation story
- Message consumers and scheduled jobs must be safe under duplicate delivery and partial failure
- Replay, rebuild, and republish flows must be bounded, observable, and safe to run more than once
- Acknowledge/commit work only after durable success, not before
- Avoid blocking or heavy work on latency-sensitive request or worker execution paths
- Queue, event, and notification dispatch that must happen after commit should respect the project's after-commit or outbox strategy and must not fire early
- Cache fill, refresh, and invalidation logic must not create obvious thundering-herd or stale-data incidents
- Degradation and fallback behavior should fail gracefully and make partial availability explicit where clients or operators need to know
- Logging, metrics, and tracing should include enough contextual and trace/correlation identifiers to debug failures, and failure paths should preserve them where the project expects them, without leaking secrets or sensitive data
- Long-running jobs and consumers should emit enough progress/error context to distinguish poison messages, transient failures, and permanent contract/data issues
- Rate limiting, backpressure, and batch sizing should protect downstream systems and avoid retry amplification under load

## Output Rules
- Report at most 7 findings.
- Include production failure scenario for each Major/Blocker.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Format

Every finding must use this exact bullet format for downstream tooling:

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Do NOT use markdown tables, numbered lists, or any other format for findings.
