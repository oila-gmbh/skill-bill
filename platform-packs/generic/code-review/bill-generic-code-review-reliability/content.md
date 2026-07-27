---
name: bill-generic-code-review-reliability
description: Technology-neutral reliability review for retries, timeouts, cancellation, recovery, and telemetry.
internal-for: bill-code-review
---

# Generic Reliability Review

## Review Focus

Inspect partial failure, retry bounds, idempotency, timeouts, cancellation, shutdown, lease or lock expiry, restart recovery, backpressure, and terminal telemetry. Ensure failures remain visible and do not silently convert to partial success.

## Evidence

Give a concrete dependency failure or interruption sequence and show how the changed code loses work, duplicates effects, wedges progress, or hides the terminal outcome.
