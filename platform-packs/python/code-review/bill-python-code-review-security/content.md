---
name: bill-python-code-review-security
description: Review Python security risks across dependencies, auth, secrets, unsafe parsing, path/subprocess use, SSRF, uploads, and sensitive logging.
internal-for: bill-code-review
---

# Python Security Review

Review exploitable trust-boundary and sensitive-data failures.

## Focus

- Supply chain, authentication, authorization, tenant isolation, secrets, parsing, rendering, paths, uploads, SSRF, subprocesses, sessions, and sensitive errors or logs

## Ignore

- Generic dependency warnings unless the diff creates or worsens a concrete exposure
- Hardening preferences without attacker-controlled input, a crossed trust boundary, or policy-relevant impact

## Applicability

Use this specialist for dependencies, routes, object permissions, tenant boundaries, tokens, parsing, templates, files, archives, network clients, subprocesses, browser sessions, forms, model hydration, and webhooks.

## Project-Specific Rules

### Trust Boundaries and Dangerous Execution

- Reject new dependency sources, names, extras, build backends, or scripts that permit dependency confusion, and require lockfile or version-bound changes to preserve reproducible, reviewed package resolution.
- Reject `eval`, `exec`, or dynamic imports whose expression, module, or attribute is derived from untrusted input; require a fixed allowlist and explicit dispatch boundary.
- Reject unsafe pickle, YAML, XML/entity, template, markdown/HTML, archive, upload, path, symlink, shell, subprocess, or SSRF handling when attacker input can reach the sink; specifically reject Jinja or Django autoescape bypasses and uploaded-file validation that trusts a client-supplied content type.
- Require route and object-level authorization, tenant isolation, and explicit delegation scope so confused-deputy paths cannot use the service's authority for an unprivileged caller.
- Require authentication credentials and tokens to validate their signature, expiry, issuer, audience, intended use, and revocation state before granting access; reject decoding or parsing that is treated as authentication.
- Require least-privilege outbound clients, safe temporary-file cleanup, and limits on uploaded size and extracted archive shape.
- Require subprocess calls to use controlled arguments and a minimal trusted environment plus explicit timeouts and termination cleanup; reject inherited secrets, attacker-controlled environment entries, and indefinitely running children.
- Reject secrets, tokens, PII, credentials, or tenant data in configuration dumps, exceptions, logs, traces, metrics, fixtures, notebooks, or persisted debug artifacts.

### Browser, Session, and Framework Features

- Reject Django `DEBUG=True` in deployed settings and exposed stack traces or error payloads that reveal secrets, filesystem paths, source, queries, or tenant data.
- Require CSRF protection for cookie-authenticated state changes, session rotation after authentication or privilege change to prevent fixation, and explicit expiry for signed or capability URLs.
- Reject Django `ModelForm` declarations with `fields="__all__"` where newly added model fields can become mass-assignable across the authorization boundary.
- Require pydantic models to validate untrusted hydration rather than using unchecked construction or raw dictionary assignment that bypasses validators.
- Require webhook signatures to be verified over the exact raw payload with the documented algorithm, secret, freshness, and replay boundary before parsing or processing.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
