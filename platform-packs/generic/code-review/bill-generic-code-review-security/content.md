---
name: bill-generic-code-review-security
description: Technology-neutral security review for trust boundaries, authorization, secrets, input, and side effects.
internal-for: bill-code-review
---

# Generic Security Review

## Review Focus

Follow untrusted input to privileged reads, writes, execution, parsing, network access, and logs. Verify object-level authorization, tenant separation, secret handling, safe defaults, output encoding, and resistance to path, injection, and confused-deputy attacks.

## Evidence

Name the attacker-controlled value, missing control, reachable sink, and impact. Avoid speculative findings when validation or authorization is demonstrably enforced upstream.
