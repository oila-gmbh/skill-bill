# SKILL-220 Subtask 7: Principle Enforcement And Build-Convention Promotion

## Intended Outcome

Resolve P-09 and P-10. Every structural rule this program applies is currently
a convention a reviewer must catch by eye, which is why the tree drifted from
it. Make each enforceable rule fail the build when violated, hoist repeated
module build configuration into convention plugins, and state plainly which
rules are not mechanically enforceable.

## Scope

### Architecture tests

Reuse the existing source-scanning architecture-test infrastructure under
`runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/`. Scan
declarations, not single physical lines, so a wrapped declaration cannot
escape the guard. Do not duplicate tests that already pin the module graph,
raw-map allow-lists, schema-validator ownership, or CLI/MCP import bans.

Add architecture tests for:

- No package holds files from two unrelated noun families, expressed as a
  bound on loose files in a package that already has subpackages (the
  clustering subtask 1 established).
- No production file exceeds the 500-line ceiling subtasks 4–6 establish.
  List any file granted an explicit exemption with its reason (the expected
  set is empty).
- Every in-scope `*Failure` hierarchy's case-to-code mapping is total and
  injective (may wrap the conformance test subtask 3 added).
- No `error()`, `require()`, or bare `throw` reports malformed external
  input at named parse boundaries.
- No inline fully-qualified type or callable reference in production or test
  Kotlin under `runtime-kotlin`, `intellij-plugin`, and
  `runtime-kotlin/build-logic`, with the keep-list from subtask 2 (string
  literals, import/package, generated sources, documented aliases). This
  complements the existing SKILL-52.3 adapter/infra leak guard; it does not
  replace it.
- No module build file re-applies configuration a convention plugin sets.

For each new test, prove it against a deliberately violating fixture as well
as against the clean tree.

Record in the test file which principles are deliberately not enforced
mechanically and why (comment quality, naming taste, deeper noun-family
relatedness, open harness/capability keys if subtask 3 left them open).

### Build convention (P-09)

- Hoist the repeated `update-snapshots` Test `systemProperty` from
  `runtime-infra-fs/build.gradle.kts` and `runtime-contracts/build.gradle.kts`
  into `skillbill.jvm-library` / `configureKotlinJvm()`.
- Audit every module build file for anything else a convention already sets
  or should set. Confirm the remainder is genuinely local.

## Applicable Principles

- All replaceable implementations require conformance tests.
- Before writing a test, name the realistic bug it would catch.
- Architecture tests are the named exception to "assert observable behavior,
  never implementation structure"; each must justify itself by the
  regression it prevents.
- Every module starts from a convention plugin; a module build file owns
  only what is genuinely local.
- When a module-local build workaround generalizes, promote it and delete
  the local copy.

## Acceptance Criteria

1. Each enforceable rule above has one test that fails against a violating
   fixture and passes against the tree.
2. Each test names, in one line, the regression it prevents. A test that
   cannot name one is not added.
3. Tests scan declarations rather than physical lines, so a wrapped
   declaration cannot evade them.
4. The line-ceiling test uses 500 lines and lists any exemption with its
   reason.
5. The inline-FQN scanner fires on a synthetic `java.time.Instant.now()` (or
   equivalent) fixture with no import, and does not flag the same text
   inside a string literal, KDoc, or `import` line.
6. The rules deliberately left to review are listed with their reasons.
7. No test duplicates a guard the existing architecture suite already
   provides.
8. Total added test count stays proportionate: one test per rule, not one
   per violation found.
9. No module build file re-applies configuration a convention plugin already
   sets. The `update-snapshots` property is set once in convention code.
10. `scripts/validate` passes, and the suite fails when any single rule is
    violated locally.

## Failure And Recovery Behavior

A violation fails `check` with a message naming the file, the rule, and what
to do instead. A guard that reports only that something is wrong is
incomplete.

## Non-Goals

- Enforcing subjective rules such as comment quality or naming taste.
- Adding a line or branch coverage gate.
- Replacing detekt or ktlint rules with hand-written scanners where the tool
  already covers the rule.
- Enforcing rules the tree does not yet satisfy; each guard lands only after
  its subtask.
- Replacing the SKILL-52.3 adapter/infra FQN leak guard.

## Dependency Notes

Runs after subtasks 1 through 6. A guard written against the old structure
would encode the violation it is meant to forbid.

## Validation Strategy

For each guard, introduce the violation locally, confirm the specific
failure message, then revert. Run `scripts/validate` on the clean tree.

## Next Path

Subtask 8 reconciles the documentation.
