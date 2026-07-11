---
name: bill-typescript-code-review-security
description: Use when reviewing TypeScript trust boundaries, runtime validation, auth, secrets, injection, process or file access, dependencies, and sensitive data.
internal-for: bill-code-review
---

# Security Review Specialist

## Focus

- Authentication, authorization, tenant isolation, sessions, tokens, and secrets
- Untrusted JSON, forms, headers, messages, URLs, paths, HTML, SQL, and subprocess input
- Runtime validation gaps hidden by TypeScript declarations or unchecked casts
- npm dependencies, lifecycle scripts, build plugins, lockfiles, and browser supply chain

## Ignore

- Type-style comments without an exploit or policy consequence
- Generic dependency concern without an affected version or reachable surface
- Speculative threats disconnected from changed trust boundaries

## Applicability

Use this specialist when changed TypeScript affects trust boundaries, authorization, untrusted inputs, secrets, injection sinks, or dependencies.

## Project-Specific Rules

### TypeScript Security Rules

- Verify `TypeScript authentication and sensitive-data APIs` preserve trust-boundary invariants; reject an authorization or data-exposure failure.
- Parse external values as `unknown` and validate them before privileged or domain use; compile-time types are not validation.
- Enforce authorization on every reachable entry point using trusted server-side actor and tenant context.
- Keep untrusted data out of shell interpretation, unrestricted paths, raw queries, HTML/script sinks, redirects, and dynamic imports.
- Check DOM rendering and framework escape hatches such as raw HTML for injection and URL-scheme risks.
- Do not expose secrets, tokens, personal data, stack traces, or internal errors through logs, responses, or browser bundles.
- Treat install scripts, build plugins, generators, transitive packages, and changed lockfiles as executable supply-chain inputs.
- Ensure browser code cannot receive server-only credentials through bundler environment substitution.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
