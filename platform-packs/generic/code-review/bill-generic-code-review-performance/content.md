---
name: bill-generic-code-review-performance
description: Technology-neutral performance review for amplification, blocking, resource lifetime, and scale.
internal-for: bill-code-review
---

# Generic Performance Review

## Review Focus

Inspect repeated work, unbounded collections, blocking on constrained workers, excessive serialization or transfer, resource retention, and algorithms whose cost grows unexpectedly with user or data volume.

## Evidence

Quantify the triggering scale or repeated path where possible. Do not report micro-optimizations without a plausible workload and material latency, throughput, memory, or cost consequence.
