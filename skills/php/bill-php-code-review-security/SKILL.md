---
name: bill-php-code-review-security
description: Use when reviewing security risks in changed PHP/backend code including auth/session safety, secrets handling, trust boundaries, sensitive data exposure, injection, file handling, and output encoding. Use when user mentions auth, XSS, CSRF, injection, upload safety, or security review in PHP.
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

Apply shared security rules to all changed code. Apply the deeper surface-specific checks only when the changed code uses those mechanisms.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-php-code-review-security` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

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
- Secrets/config must come from env vars, vaults, or secret-management systems, not committed config files
- Do not expose stack traces, internal exception messages, or sensitive failure details in API responses
- Verify webhook signatures, internal-service auth, signed callbacks, or mTLS assumptions when new external entry points are introduced
- Avoid logging raw auth headers, session cookies, full request bodies, or other high-risk payloads by default
- File upload, archive extraction, and file-path handling must not allow traversal, unsafe content execution, or unsafe trust in client-provided metadata
- Secret material and auth context must not bleed across boundaries accidentally
- Output encoding and template rendering must not allow unescaped user-controlled content to reach server-rendered UI or generated documents

### Template / Output Encoding
- Blade, server-rendered templates, and generated HTML must escape user-controlled content unless raw output is explicitly required and proven safe
- Client-side JavaScript must not inject untrusted content into the DOM, HTML, script contexts, or URLs without safe encoding or sanitization

### Browser / Session Surface
- CSRF protection, same-site cookie assumptions, and session/auth flows must remain intact on every reachable state-changing browser path
- Uploaded file metadata, MIME/type checks, extension checks, and storage paths must not be trusted independently of server-side verification

### Framework Feature Checks
- Livewire or similar server-driven component state, action methods, and emitted events must not trust client-mutated values without server-side authorization and boundary validation
- Mass-assignment, fill/update helpers, and model hydration must not allow unauthorized field writes
- Signed URLs, temporary links, reset/invite tokens, and other capability URLs must be validated, scoped, and expire correctly

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
