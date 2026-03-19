---
name: bill-code-review-testing
description: Review test coverage quality, regression protection, and test reliability risks in Android, KMP, backend/server, and general Kotlin code.
---

# Testing Review Specialist

Review only test gaps that create real regression risk.

## Focus
- Missing tests for changed behavior and failure paths
- Brittle/flaky test patterns and false-confidence assertions
- Contract drift between implementation and tests
- Inadequate negative-path coverage
- Missing integration points where unit-only tests are insufficient

## Ignore
- Test style preferences without risk impact
- Missing tests for trivial mappers (e.g., `toUiModel()` that only copy properties)

## Applicability

Apply shared test-risk rules to all code. Apply Android/KMP-only rules only when Android/KMP signals are present. Apply backend/server-only rules only when backend/server signals are present.

## Project Overrides

If an `AGENTS.md` file exists in the project root, read it and apply its rules alongside the defaults below. Project rules take precedence when they conflict.

## Project-Specific Rules

### Shared Kotlin Testing
- Changed behavior and failure paths should be covered at the layer where regressions would surface first
- Prefer tests that validate real behavior over tests that only mirror implementation details
- Mock only true external boundaries; over-mocking internal collaborators can hide regressions
- Coroutine tests should control time/dispatchers deterministically where ordering or retry behavior matters

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
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Table
| Area | Severity | Confidence | Evidence | Why it matters | Minimal fix |
|------|----------|------------|----------|----------------|-------------|
