---
name: bill-go-code-review-testing
description: Use when reviewing test coverage quality, real test value, regression protection, and test reliability risks in Go backend/service code. Use when user mentions test quality, t.Run, t.Parallel, fuzz test, flaky test, or test coverage in Go.
---

# Testing Review Specialist

Review only test gaps that create real regression risk.

## Focus
- Missing tests for changed behavior and failure paths
- Brittle/flaky test patterns and false-confidence assertions
- Low-value, tautological, or coverage-padding tests that do not validate real behavior
- Contract drift between implementation and tests
- Inadequate negative-path and concurrency coverage
- Missing integration points where unit-only tests are insufficient

## Ignore
- Test style preferences without risk impact
- Missing tests for trivial mappers, accessors, or glue code with no meaningful behavior

## Applicability

Apply shared test-risk rules to backend/service code. Apply the deeper concern-specific checks only when the changed
code uses those mechanisms.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-go-code-review-testing` section,
read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or
replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

### Shared Backend Testing
- Changed behavior and failure paths should be covered at the layer where regressions would surface first
- A test only adds value if it would fail on a meaningful regression in business behavior
- Prefer tests that validate real behavior over tests that only mirror implementation details
- Treat tautological tests as test gaps when they create false confidence without exercising real logic
- Mock only true external boundaries; over-mocking internal collaborators can hide regressions
- Time, retries, duplicate delivery, concurrency, and ordering-sensitive behavior should be tested deterministically when they matter
- Business logic should be tested comprehensively in package-focused unit tests when the project architecture isolates domain behavior
- Table-driven tests and subtests should keep case data explicit and copy loop variables before closures when the test body captures them

### Unit Test Value Lens
- Flag tests that only instantiate a struct, assign values, and assert the same values without logic in between
- Flag tests that only verify a stubbed return value or a mock interaction without asserting an externally meaningful outcome
- Flag tests that duplicate the implementation step-for-step and compare against the duplicated result
- Flag tests that only check `not nil`, booleans, or collection size when those assertions are not tied to important behavior
- Do not request tests for trivial mappers, accessors, or generated code unless they enforce business rules, compatibility, or invariants
- Parsers, encoders, decoders, validators, and command handlers can still be worth testing when they validate, normalize, clamp, reject, or preserve a contract

### Backend/Service-Specific Rules
- Public boundary changes need contract or integration tests when status codes, validation, auth context, or serialization changed
- API and boundary tests should assert the real contract shape, not only loose structure, when contract drift would matter to clients or downstream systems
- When the local project requires exact API contracts, prefer full response/error assertions over partial structure checks
- Persistence changes need repository or integration tests around transactions, constraints, locking, replay-sensitive behavior, and migration-sensitive behavior
- Persistence-backed integration tests should verify actual persistence effects, not only mocked repository interactions
- Timeout, cancellation, worker, retry, scheduler, consumer, and idempotency logic needs deterministic tests that control time, ordering, and duplicate delivery where relevant
- Concurrency-sensitive changes should trigger race-aware or ordering-aware tests when practical, and missing `go test -race` or repo-equivalent coverage should be treated as a real gap unless the project explicitly opts out
- Subtests using `t.Run()` and `t.Parallel()` must isolate mutable fixtures, environment overrides, and temp resources so parallelism does not create hidden races or order dependence
- Parsers, decoders, normalizers, and security-sensitive boundary code should get fuzz coverage when malformed input could crash the process or violate a contract
- `TestMain`, shared setup helpers, and `t.Cleanup()` usage should make failure and cleanup paths explicit so leaked goroutines, timers, sockets, or env mutations do not taint later tests
- Prefer real request parsing, codecs, serializers, and boundary objects at API/transport tests; fake downstream systems, not the contract itself
- Verify negative-path coverage for malformed input, forbidden access, downstream failures, duplicate delivery, and partial-failure paths where relevant
- Boundary tests should verify persisted side effects or externally visible outcomes, not only response status or mock interactions
- Feature-flag, permission-gated, and role-gated paths need explicit tests for both enabled and disabled or forbidden behavior when they change semantics

## Output Rules
- Report at most 7 findings.
- Include a minimal test plan for top uncovered risks.
- Report weak or useless tests only when they materially reduce regression confidence.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix, which may be to rewrite or delete the test and replace it with a behavior-focused one.

## Output Format

Every finding must use this exact bullet format for downstream tooling:

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Do NOT use markdown tables, numbered lists, or any other format for findings.

## Description
This content file is a platform-pack specialist area review module for
`bill-go-code-review-testing`. The baseline orchestrator delegates a single specialist area here.
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
