---
name: bill-php-code-review-testing
description: Use when reviewing test coverage quality, real test value, regression protection, and test reliability risks in PHP backend/server code. Use when user mentions test quality, PHPUnit, contract tests, test coverage, or test reliability in PHP.
---

# Testing Review Specialist

Review only test gaps that create real regression risk.

## Focus
- Missing tests for changed behavior and failure paths
- Brittle/flaky test patterns and false-confidence assertions
- Low-value, tautological, or coverage-padding tests that do not validate real behavior
- Contract drift between implementation and tests
- Inadequate negative-path coverage
- Missing integration points where unit-only tests are insufficient

## Ignore
- Test style preferences without risk impact
- Missing tests for trivial mappers, accessors, or glue code with no meaningful behavior

## Applicability

Apply shared test-risk rules to backend/server code. Apply the deeper concern-specific checks only when the changed code uses those mechanisms.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-php-code-review-testing` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

### Shared Backend Testing
- Changed behavior and failure paths should be covered at the layer where regressions would surface first
- A test only adds value if it would fail on a meaningful regression in business behavior
- Prefer tests that validate real behavior over tests that only mirror implementation details
- Treat tautological tests as test gaps when they create false confidence without exercising real logic
- Mock only true external boundaries; over-mocking internal collaborators can hide regressions
- Time, retries, duplicate delivery, and ordering-sensitive behavior should be tested deterministically when they matter
- Domain logic should be tested comprehensively in framework-free unit tests when the project architecture isolates domain behavior

### Unit Test Value Lens
- Flag tests that only instantiate a DTO/model, assign values, and assert the same values without logic in between
- Flag tests that only verify a stubbed return value or a mock interaction without asserting an externally meaningful outcome
- Flag tests that duplicate the implementation step-for-step and compare against the duplicated result
- Flag tests that only check `not null`, booleans, or collection size when those assertions are not tied to important behavior
- Do not request tests for trivial mappers, accessors, or generated code unless they enforce business rules, compatibility, or invariants
- Constructors, value objects, parsers, and serializers can still be worth testing when they validate, normalize, clamp, reject, or preserve a contract

### Backend/Server-Specific Rules
- Public boundary changes need contract or integration tests when status codes, validation, auth context, or serialization changed
- API and boundary tests should assert the real contract shape, not only loose structure, when contract drift would matter to clients or downstream systems
- When the local project requires exact API contracts, prefer full response/error assertions over partial JSON structure checks
- HTTP/API tests should cover full request-response behavior, including exact error contracts or exact response payloads when the local project standard requires them
- Persistence changes need repository or integration tests around transactions, constraints, locking, replay-sensitive behavior, and migration-sensitive behavior
- Persistence-backed integration tests should verify actual persistence effects, not only mocked repository interactions
- Retry, timeout, scheduler, consumer, outbox, and idempotency logic needs deterministic tests that control time, ordering, and duplicate delivery where relevant
- Outbox, after-commit, replay, and projector-sensitive flows should be tested where duplicate delivery, ordering, or missed dispatch would surface
- Queue, event, notification, and after-commit flows should be tested at the boundary where duplicate delivery, retries, or ordering regressions would surface
- Prefer real request parsing, serializers, and boundary objects at API/transport tests; mock downstream systems, not the contract itself
- Verify negative-path coverage for malformed input, forbidden access, downstream failures, duplicate delivery, and partial-failure paths where relevant
- Negative-path tests should cover not-found, validation, auth, authorization, and business-rule failures separately when they produce different contracts
- Public boundary tests should cover each meaningful outcome separately when the contracts differ: success, validation failure, authentication failure, authorization failure, not-found, and business-rule failure
- Multi-step workflows need tests that prove success and failure outcomes at the externally visible boundary, not only inside intermediate collaborators
- Boundary tests should verify persisted side effects or externally visible outcomes, not only response status or mock interactions
- Server-rendered or component-driven flows should test the user-visible behavior that could regress, not only helper internals
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
