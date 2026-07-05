---
name: bill-php-code-review-security
description: Use when reviewing PHP security risks including auth, authorization, secret handling, sensitive logging, uploads, template rendering, deserialization, and credential exposure.
internal-for: bill-code-review
---

# Security Review Specialist

Review only exploitable or compliance-relevant issues.

## Focus

- Secret leakage
- Auth/authz logic gaps and session/token misuse
- Sensitive logging
- Insecure storage or transport assumptions
- Security regressions from new code paths

## Ignore

- Non-security style comments

## Applicability

Use this specialist for PHP code at trust boundaries: auth/authz, sessions, input and output handling, file/network/process APIs, secrets, uploads, templates, background entry points, and scripts that handle credentials or untrusted data.

## Project-Specific Rules

### Shared Security

- No secrets, tokens, passwords, or private keys in code, logs, tests, or repo config
- Sensitive identifiers and personal data must not be logged or exposed without explicit need and protection
- New code paths must preserve auth/authz guarantees and avoid bypassable feature-flag checks
- Treat all external input as untrusted and validate or constrain it at the boundary
- Watch for SSRF, command execution, path traversal, SQL injection, template injection, unsafe deserialization, and object-level access control gaps
- Do not pass untrusted data into `unserialize`, PHP object hydration hooks, archive readers, template engines, shell commands, URL fetchers, or file APIs without an explicit allowlist and safe mode of use
- Temporary debug code, bypass flags, test credentials, or relaxed verification paths must not ship
- Cross-tenant or cross-account data access must be impossible unless the contract explicitly permits it

### Backend/Server-Specific Rules

- Enforce authn/authz at entry points; do not trust client-supplied role, tenant, actor, or ownership identifiers without server-side verification
- Authorization must be enforced on every reachable path, including background-triggered, indirect, or alternate action paths
- Secrets/config must come from env vars, vaults, or secret-management systems, not committed config files
- Do not expose stack traces, internal exception messages, or sensitive failure details in API responses
- Verify webhook signatures, internal-service auth, signed callbacks, or mTLS assumptions when new external entry points are introduced
- SSRF-sensitive HTTP/file fetches must restrict schemes, hosts, redirects, DNS rebinding, internal IP ranges, metadata services, and local stream wrappers according to the project's threat model
- Command execution must avoid shell interpretation where possible; when unavoidable, validate commands and arguments separately and preserve least privilege
- Avoid logging raw auth headers, session cookies, full request bodies, or other high-risk payloads by default
- File upload, archive extraction, and file-path handling must not allow traversal, unsafe content execution, or unsafe trust in client-provided metadata
- Secret material and auth context must not bleed across boundaries accidentally
- Output encoding and template rendering must not allow unescaped user-controlled content to reach server-rendered UI or generated documents

### Template / Output Encoding

- Blade, server-rendered templates, and generated HTML must escape user-controlled content unless raw output is explicitly required and proven safe
- Client-side JavaScript must not inject untrusted content into the DOM, HTML, script contexts, or URLs without safe encoding or sanitization

### Browser / Session Surface

- CSRF protection, same-site cookie assumptions, and session/auth flows must remain intact on every reachable state-changing browser path
- Session fixation, remember-me tokens, password reset flows, invite links, and capability URLs must rotate, expire, and scope credentials correctly
- Uploaded file metadata, MIME/type checks, extension checks, and storage paths must not be trusted independently of server-side verification

### Framework Feature Checks

- Livewire or similar server-driven component state, action methods, and emitted events must not trust client-mutated values without server-side authorization and boundary validation
- Mass-assignment, fill/update helpers, and model hydration must not allow unauthorized field writes
- Signed URLs, temporary links, reset/invite tokens, and other capability URLs must be validated, scoped, and expire correctly
- For Critical or Major findings, describe the abuse or exploit scenario explicitly
