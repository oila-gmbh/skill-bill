---
name: mdp-code-review-security
description: Review secrets handling, auth/session safety, sensitive data exposure, and transport/storage security.
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

## Project Overrides

If an `AGENTS.md` file exists in the project root, read it and apply its rules alongside the defaults below. Project rules take precedence when they conflict.

## Project-Specific Rules

### HTTP & Auth
- Verify request signing/authentication interceptors are used correctly
- Verify no credentials in `local.properties` or code — use external config or secrets management

### Logging
- No PII or tokens in log output
- Do not add debug logs unless actively debugging
- Use structured logging with consistent tags

### Storage
- Verify no sensitive data stored unencrypted
- Never rename DataStore files without migration (data loss risk)

### Feature Flags
- Verify feature flags don't expose sensitive logic paths

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
