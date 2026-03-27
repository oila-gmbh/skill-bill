---
name: bill-kotlin-code-review-testing
description: Use when reviewing test coverage quality, real test value, regression protection, and test reliability risks in Android, KMP, backend/server, and general Kotlin code.
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
- Missing tests for trivial mappers (e.g., `toUiModel()` that only copy properties)

## Applicability

Apply shared test-risk rules to all code. Apply Android/KMP-only rules only when Android/KMP signals are present. Apply backend/server-only rules only when backend/server signals are present.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-code-review-testing` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

### Shared Kotlin Testing
- Changed behavior and failure paths should be covered at the layer where regressions would surface first
- A test only adds value if it would fail on a meaningful regression in business behavior
- Prefer tests that validate real behavior over tests that only mirror implementation details
- Treat tautological tests as test gaps when they create false confidence without exercising real logic
- Mock only true external boundaries; over-mocking internal collaborators can hide regressions
- Coroutine tests should control time/dispatchers deterministically where ordering or retry behavior matters

### Unit Test Value Lens
- Flag tests that only instantiate a DTO/model, assign values, and assert the same values without logic in between
- Flag tests that only verify a stubbed return value or a mock interaction without asserting an externally meaningful outcome
- Flag tests that duplicate the implementation step-for-step and compare against the duplicated result
- Flag tests that only check `not null`, booleans, or collection size when those assertions are not tied to important behavior
- Do not request tests for trivial mappers, accessors, or generated code unless they enforce business rules, compatibility, or invariants
- Constructors, value objects, parsers, and serializers can still be worth testing when they validate, normalize, clamp, reject, or preserve a contract

### Android/KMP-Specific Rules

#### Test Runner
- JUnit4 (`@Test`, `@Before`, `@After`) rules apply only when the project already uses JUnit4
- Kotest assertions (`shouldBe`, etc.) alongside JUnit4 are acceptable when already established
- Do not suggest framework migration unless the change itself introduces a mismatch

#### Mocking
- If the project standard is MockK annotations, prefer `@MockK` over ad hoc `mockk()` creation
- `relaxedUnitFun = true` is OK where already allowed
- `relaxed = true` is a high-risk smell when it can hide real behavior
- Mock external dependencies only

#### Flow Testing
- Use Turbine for coroutine `Flow` testing when that is the project standard
- Verify emissions in order with `awaitItem()`
- Always cancel remaining events explicitly

#### Test Scope
- Shared test utilities may live in `core:testing` or an equivalent module
- Focus test effort on logic that adds value — not trivial mappings

### Backend/Server-Specific Rules
- Endpoint/controller/route changes need contract or integration tests when status codes, validation, auth context, or serialization changed
- Persistence changes need repository/integration tests around transactions, constraints, locking, and migration-sensitive behavior
- Retry, timeout, scheduler, consumer, and idempotency logic needs deterministic tests that control time and replay
- Prefer real serializers/request objects at API boundary tests; mock downstream systems, not the transport contract itself
- Verify negative-path coverage for malformed input, forbidden access, downstream failures, and duplicate delivery where relevant

## Output Rules
- Report at most 7 findings.
- Include a minimal test plan for top uncovered risks.
- Report weak or useless tests only when they materially reduce regression confidence.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix, which may be to rewrite or delete the test and replace it with a behavior-focused one.

## Output Table
| Area | Severity | Confidence | Evidence | Why it matters | Minimal fix |
|------|----------|------------|----------|----------------|-------------|
