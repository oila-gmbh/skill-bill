---
name: bill-unit-test-value-check
description: Use when reviewing unit tests in a file, current changes, or a commit to flag low-value, tautological, or coverage-only tests that do not validate real behavior. Use when user mentions check test quality, review tests, tautological tests, weak tests, or coverage-padding.
---

# Unit Test Value Checker

## Project Overrides

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

If `.agents/skill-overrides.md` exists in the project root and contains a matching section, read that section and apply it as the highest-priority instruction for this skill.

## Supported Scope
- A specific unit test file
- The current staged and unstaged change list
- A specific commit SHA or ref

If the caller does not specify a scope, review the current staged and unstaged changes.

If the chosen scope contains no unit tests, say so clearly and stop.

## Workflow
1. Determine the requested scope: file, current changes, or commit.
2. Read the unit tests in scope and the production code they exercise.
3. For each test or test block, judge whether it would catch a meaningful regression in business behavior.
4. Classify findings as `Valuable`, `Weak`, or `Useless`.
5. Prefer a small number of high-signal findings over broad commentary.

## What Counts As Real Value
- Business rules, branching, calculations, invariants, normalization, and validation
- Error handling, retries, permission checks, boundary conditions, and fallback behavior
- State transitions and externally visible side effects
- Contract behavior at module or API boundaries
- Regression coverage for real bugs or realistic failure cases

## Low-Value Patterns To Flag
- Creating a data object, assigning fields, and asserting the same fields without any logic in between
- Instantiating a DTO, entity, or plain model and asserting getters echo constructor values when no validation or normalization exists
- Stubbing a collaborator and only asserting the same stubbed value is returned without testing any decision-making
- Verifying a mock interaction with no assertion about user-visible or system-visible outcome
- Asserting framework or library behavior instead of project logic
- Reproducing the implementation step-for-step inside the test and comparing the duplicated result
- Tests that only assert `not null`, `true`, `false`, or collection size without tying that assertion to meaningful behavior
- Testing trivial mappers, property accessors, generated code, or boilerplate solely to raise coverage numbers

## Cases That May Look Trivial But Can Still Be Valuable
- Constructors or factories that validate, normalize, trim, clamp, parse, or reject input
- Value objects whose equality, ordering, hashing, or parsing encode business rules
- Mapping or serialization code with compatibility or contract risk
- Wrapper types that enforce invariants or security-sensitive formatting

## Review Rules
- Do not reward quantity, coverage percentage, or test count.
- Do not suggest more tests unless you can name a concrete missing behavior that matters.
- Prefer deleting or rewriting useless tests over polishing them.
- Isolate specific low-value tests instead of dismissing an entire file unless the whole file is weak.
- Use `file:line` evidence for every finding.
- When confidence is not high, say why.

## Output
Provide:

- `Overall verdict`: `Strong | Mixed | Weak`
- `Scope reviewed`
- A 2-4 line summary focused on real value

Then include a table:

| Test or Area | Verdict | Confidence | Evidence | Why it adds or lacks value | Better test or action |
|--------------|---------|------------|----------|----------------------------|-----------------------|

Rules for output:
- Include at most 10 rows.
- Only report findings that materially affect confidence in the tests.
- End with:
  - `Keep:` strongest tests worth keeping
  - `Rewrite/Delete:` specific low-value tests
  - `Missing high-value cases:` only concrete business behaviors that are not covered
