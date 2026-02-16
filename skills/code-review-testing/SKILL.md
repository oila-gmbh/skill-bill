---
name: code-review-testing
description: Review test coverage quality, regression protection, and test reliability risks.
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

