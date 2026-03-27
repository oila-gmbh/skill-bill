---
name: bill-code-review-security
description: Use when reviewing secrets handling, auth/session safety, sensitive data exposure, and transport/storage security in Android, KMP, backend/server, and general Kotlin code.
---

# Security Review Specialist

Review only exploitable or compliance-relevant issues.

## Focus
- Secret leakage (keys/tokens/credentials)
- Auth/authz logic gaps and session/token misuse
- Sensitive logging (PII/token leakage)
- Insecure storage/transport assumptions
- Security regressions from new code paths

## Ignore
- Non-security style comments

## Applicability

Apply shared security rules to all code. Apply Android/KMP-only rules only when Android/KMP signals are present. Apply backend/server-only rules only when backend/server signals are present.

## Project Overrides

If an `AGENTS.md` file exists in the project root, read it and apply its rules alongside the defaults below. Project rules take precedence when they conflict.

## Project-Specific Rules

### Shared Kotlin Security
- No secrets, tokens, passwords, or private keys in code, logs, tests, or repo config
- Sensitive identifiers and personal data must not be logged or exposed without explicit need and protection
- New code paths must preserve auth/authz guarantees and avoid bypassable feature-flag checks

### Android/KMP-Specific Rules

#### HTTP & Auth
- Verify request signing/authentication interceptors are used correctly
- Verify no credentials in `local.properties` or code — use external config or secrets management

#### Logging
- No PII or tokens in log output
- Do not add debug logs unless actively debugging
- Use structured logging with consistent tags

#### Storage
- Verify no sensitive data stored unencrypted
- Never rename DataStore files without migration (data loss risk)

### Backend/Server-Specific Rules
- Enforce authn/authz at entry points; do not trust client-supplied role, tenant, or actor IDs without server-side verification
- Secrets/config must come from env vars, vaults, or secret-management systems — not committed config files
- Do not expose stack traces, internal exception messages, or sensitive failure details in API responses
- Verify webhook signatures, internal-service auth, or mTLS assumptions when new external entry points are introduced
- Avoid logging raw auth headers, session cookies, full request bodies, or other high-risk payloads by default

## Output Rules
- Report at most 7 findings.
- Include abuse scenario for each Major/Blocker.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Table
| Area | Severity | Confidence | Evidence | Why it matters | Minimal fix |
|------|----------|------------|----------|----------------|-------------|
