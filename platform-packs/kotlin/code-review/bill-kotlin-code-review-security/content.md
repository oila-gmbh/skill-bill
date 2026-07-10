---
name: bill-kotlin-code-review-security
description: Use when reviewing secrets handling, auth/session safety, sensitive data exposure, and transport/storage security in Kotlin code. Use when user mentions secrets, auth tokens, encryption, sensitive data, or security review in Kotlin code.
internal-for: bill-code-review
---

# Security Review Specialist

Review only exploitable or compliance-relevant failures.

## Focus

- Dangerous input sinks, authentication and authorization, tenant isolation, secrets, verification, and deserialization

## Ignore

- Non-security style feedback or hypothetical concerns without an attacker-controlled path

## Applicability

Use this specialist for Kotlin libraries and services that process untrusted data, identities, credentials, files, URLs, commands, templates, or serialized payloads.

## Project-Specific Rules

### Dangerous Sinks

- Require allowlisting before untrusted input reaches network destinations, process builders or shells, filesystem paths, SQL fragments, or template engines.
- Reject SSRF paths that let attackers reach internal services, command execution paths that permit argument or shell injection, and path traversal that escapes the intended root.
- Reject SQL or template injection wherever untrusted text is concatenated into executable syntax rather than passed through safe typed or parameterized APIs.
- Reject unsafe deserialization through `ObjectInputStream`, unrestricted kotlinx.serialization polymorphism, SnakeYAML `load`, or equivalent gadget-capable mechanisms.

### Identity and Verification

- Require object-level authorization at the trusted security boundary for every requested resource and enforce tenant/account isolation using trusted actor context, never caller-supplied ownership alone.
- Reject hand-rolled JWT validation that omits signature, algorithm, issuer, audience, expiry, or key-rotation verification.
- Reject temporary debug endpoints, actions, or other debug code; bypass flags; committed test credentials; relaxed TLS or signature verification; and feature flags that weaken authentication or authorization.
- Never expose secrets, auth headers, session cookies, private keys, sensitive payloads, or internal exception details in code, logs, tests, or responses.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
