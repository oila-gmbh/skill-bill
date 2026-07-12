---
name: bill-kotlin-code-review-security
description: Review Kotlin input, authorization, injection, secrets, authentication, deserialization, and dependency security failures.
internal-for: bill-code-review
---

# Security Review Specialist

## Focus

- Attacker-controlled data, identity boundaries, dangerous sinks, credentials, verification, and dependencies

## Ignore

- Hypothetical concerns without a reachable attacker-controlled path or exposure

## Applicability

Use for Kotlin code processing untrusted payloads, identities, URLs, files, commands, templates, or serialized objects.

## Project-Specific Rules

### Security Review Rules

- Require `kotlinx.serialization` DTO validation before domain use; syntactically valid hostile values can break state invariants or exhaust resources.
- Reject unrestricted polymorphic serializers in `SerializersModule`; attacker-selected subclasses can create unsafe deserialization paths.
- Verify object authorization at the service boundary using the trusted `Principal`; route-only checks risk direct-call authorization bypass.
- Require tenant predicates to derive from `SecurityContext`, never request fields; caller-controlled ownership risks cross-tenant data exposure.
- Reject concatenated SQL in `exec` or JDBC statements; untrusted fragments create injection and data-corruption risk.
- Require escaped output in `kotlinx.html`, Thymeleaf, or FreeMarker templates; raw interpolation can cause script injection and session exposure.
- Verify `Path.normalize` plus root containment before file access; unchecked `resolve` input risks traversal outside the allowed directory.
- Reject unvalidated destinations passed to `HttpClient`; permissive URL handling creates SSRF exposure to metadata or internal services.
- Require `ProcessBuilder` argument allowlists and never invoke `sh -c` with input; shell composition creates command-injection failure.
- Reject secrets, `Authorization` headers, cookies, and private payloads in `logger` calls; production logs can become credential exposure.
- Verify JWT signature, algorithm, issuer, audience, expiry, and rotation using `JWTVerifier`; decode-only authentication is an unsafe bypass.
- Require Gradle dependency verification or vulnerability checks for exposed libraries; stale `libs.versions.toml` entries can retain exploitable runtime risk.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
