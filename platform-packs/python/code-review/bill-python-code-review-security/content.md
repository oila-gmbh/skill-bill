---
name: bill-python-code-review-security
description: Review Python security risks across dependencies, auth, secrets, unsafe parsing, path/subprocess use, SSRF, uploads, and sensitive logging.
internal-for: bill-code-review
---

# Python Security Review

Focus on security-sensitive behavior introduced or changed by the diff.

## Review Focus

- Dependencies and supply chain: new packages, loosened version bounds, unpinned lockfile changes, extras, build backends, scripts, and dependency confusion risk.
- Auth and authorization: route guards, object-level permission checks, tenant isolation, session/token handling, and confused-deputy paths.
- Secrets: environment reads, config dumps, exception messages, logs, test fixtures, notebooks, and accidental credential persistence.
- Unsafe parsing and rendering: pickle, yaml loaders, XML/entity handling, template injection, Jinja/Django autoescape bypasses, markdown/HTML sanitization, and unsafe deserialization.
- Paths, files, and uploads: path traversal, symlink assumptions, temporary-file handling, archive extraction, content-type trust, file size limits, and cleanup.
- Network and subprocess boundaries: SSRF, shell injection, untrusted command args, environment propagation, timeout gaps, and overly broad outbound clients.
- Sensitive observability: logs, traces, metrics, and error payloads should not expose tokens, PII, credentials, or tenant data.

## Findings Standard

Report exploitable or policy-relevant risks with the trust boundary, attacker-controlled input, and likely impact. Avoid generic dependency warnings unless the diff creates or worsens the exposure.
