---
name: bill-generic-code-review-security
description: Technology-neutral security review for trust boundaries, authorization, secrets, input, and side effects.
internal-for: bill-code-review
---

# Generic Security Review

## Focus

Follow untrusted input to privileged reads, writes, execution, parsing, network access, and logs. Verify object-level authorization, tenant separation, secret handling, safe defaults, output encoding, and resistance to path, injection, and confused-deputy attacks.

## Ignore

- Hypothetical threats without attacker-controlled input or a reachable sensitive operation.

## Applicability

Use when changes cross trust boundaries or affect identity, authorization, secrets, or dangerous sinks.

## Project-Specific Rules

### Security Rules

- Require every changed `sensitive operation` to enforce authorization at its owning boundary; reject caller-only checks that permit bypass or exposure.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
