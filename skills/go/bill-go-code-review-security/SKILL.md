---
name: bill-go-code-review-security
description: Use when reviewing security risks in changed Go backend/service code including auth/session safety, secrets handling, trust boundaries, sensitive data exposure, injection, file handling, and output encoding. Use when user mentions auth, secrets, TLS, injection, SSRF, or security review in Go.
---

# Security Review Specialist

Review only exploitable or compliance-relevant issues.

## Focus
- Secret leakage (keys/tokens/credentials)
- Auth/authz logic gaps and session/token misuse
- Sensitive logging (PII/token leakage)
- Insecure transport/storage assumptions
- Security regressions from new code paths

## Ignore
- Non-security style comments

## Applicability

Apply shared security rules to all changed code. Apply the deeper surface-specific checks only when the changed code
uses those mechanisms.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-go-code-review-security` section,
read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or
replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

### Shared Security
- No secrets, tokens, passwords, or private keys in code, logs, tests, or repo config
- Sensitive identifiers and personal data must not be logged or exposed without explicit need and protection
- New code paths must preserve auth/authz guarantees and avoid bypassable feature-flag checks
- Treat all external input as untrusted and validate or constrain it at the boundary
- Watch for SSRF, command execution, path traversal, SQL injection, template injection, unsafe deserialization, and object-level access control gaps
- Temporary debug code, bypass flags, test credentials, or relaxed verification paths must not ship
- Cross-tenant or cross-account data access must be impossible unless the contract explicitly permits it

### Backend/Server-Specific Rules
- Enforce authn/authz at entry points; do not trust client-supplied role, tenant, actor, or ownership identifiers without server-side verification
- Authorization must be enforced on every reachable path, including background-triggered, indirect, or alternate action paths
- Middleware chains, route groups, and gRPC interceptors must protect every reachable handler/service; watch for direct-handler registrations or alternate muxes that bypass auth, audit, or rate-limit middleware
- Secrets/config must come from env vars, vaults, secret-management systems, or deployment configuration, not committed config files
- Do not expose stack traces, internal error details, or sensitive failure data in API responses
- Verify webhook signatures, internal-service auth, signed callbacks, or mTLS assumptions when new external entry points are introduced
- Avoid logging raw auth headers, session cookies, full request bodies, or other high-risk payloads by default
- Session cookies and similar browser-facing tokens must set `Secure`, `HttpOnly`, and the intended `SameSite` policy when the flow depends on them
- File upload, archive extraction, and file-path handling must not allow traversal, unsafe content execution, or unsafe trust in client-provided metadata
- Secret material and auth context must not bleed across boundaries accidentally
- Auth or tenant context should flow through `context.Context` or an equivalent explicit request scope, not package globals or ad hoc goroutine-local state
- SQL construction must use parameters/placeholders instead of string interpolation in security-sensitive paths
- `os/exec`, shell invocation, and subprocess argument construction must not allow command injection or accidental credential leakage; prefer explicit argv with `exec.CommandContext` over shell strings when possible
- Random values used for secrets, tokens, or capability URLs must use `crypto/rand`, not predictable randomness
- Outbound HTTP clients must constrain user-influenced targets to the intended hosts or networks and treat redirects, internal IP ranges, and metadata endpoints as SSRF-sensitive
- TLS listener exposure, `tls.Config`, and mTLS expectations must match the intended trust boundary; do not quietly serve a weaker parallel listener or verification mode
- HTML/template output, redirects, and generated documents must not allow unescaped user-controlled content to reach unsafe contexts

## Output Rules
- Report at most 7 findings.
- Include abuse scenario for each Major/Blocker.
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
