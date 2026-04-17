---
name: bill-kotlin-code-review-security
description: Use when reviewing secrets handling, auth/session safety, sensitive data exposure, and transport/storage security in Kotlin code. Use when user mentions secrets, auth tokens, encryption, sensitive data, or security review in Kotlin code.
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

Use this specialist for shared security risks across Kotlin libraries, app layers, and backend services. Favor issues that stay security-relevant regardless of platform; leave transport- or UI-specific nuances to route-specific specialists.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-code-review-security` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

### Shared Kotlin Security
- No secrets, tokens, passwords, or private keys in code, logs, tests, or repo config
- Sensitive identifiers and personal data must not be logged or exposed without explicit need and protection
- New code paths must preserve auth/authz guarantees and avoid bypassable feature-flag checks
- Enforce authn/authz at trusted boundaries; do not trust caller-supplied role, tenant, or actor identifiers without verification
- Secrets/config must come from env vars, vaults, or secure local config excluded from version control
- Do not expose stack traces, internal exception messages, or other sensitive failure details to untrusted callers
- Avoid logging raw auth headers, session cookies, full request bodies, or other high-risk payloads without explicit redaction
- Verify authenticity and integrity checks for new external entry points, signed callbacks, or inter-service trust boundaries
- Verify that sensitive stored data receives the protection level the contract or platform requires

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

## Description
This content file is a platform-pack specialist area review module for
`bill-kotlin-code-review-security`. The baseline orchestrator delegates a single specialist area here.
The sections above define the specialist playbook; the sections below satisfy
the shell+content contract v1.0.

## Specialist Scope
Scoped to one approved code-review area. Does not cover other areas.

## Inputs
Review scope, changed files, detected stack signals, active learnings,
`review_session_id`, `review_run_id`, and the `orchestrated` flag.

## Outputs Contract
Findings in the shared Risk Register format
`- [F-###] <Severity> | <Confidence> | <file:line> | <description>`, plus
specialist-specific action items consumed by the baseline orchestrator.

## Execution Mode Reporting
Report `Execution mode: inline` or `Execution mode: delegated` per the
shell's output contract.

## Telemetry Ceremony Hooks
Specialist reviews never call `import_review` or `triage_findings` directly;
the baseline orchestrator owns lifecycle telemetry per
`telemetry-contract.md`.
