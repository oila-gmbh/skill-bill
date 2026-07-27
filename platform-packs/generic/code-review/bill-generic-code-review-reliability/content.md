---
name: bill-generic-code-review-reliability
description: Technology-neutral reliability review for retries, timeouts, cancellation, recovery, and telemetry.
internal-for: bill-code-review
---

# Generic Reliability Review

## Focus

Inspect partial failure, retry bounds, idempotency, timeouts, cancellation, shutdown, lease or lock expiry, restart recovery, backpressure, and terminal telemetry. Ensure failures remain visible and do not silently convert to partial success.

## Ignore

- Operational preferences without a reachable availability or recovery failure.

## Applicability

Use when changes affect retries, timeouts, cancellation, recovery, cleanup, or observability.

## Project-Specific Rules

### Reliability Rules

- Require every changed `retry boundary` to be bounded and safe to repeat; reject behavior that causes duplication, leaked work, or unavailable recovery.
- For Blocker or Major findings, describe the concrete availability, duplication, or cleanup failure scenario.
