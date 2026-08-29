# SKILL-220 Subtask 2: Inline Fully-Qualified Name Sweep

## Intended Outcome

Resolve P-03. Call sites and type positions qualify types as
`java.time.Instant.parse(...)` or `skillbill.workflow.Foo` instead of
importing the type. Replace inline fully-qualified names with imports so a
reader sees the simple name and the compiler, ktlint, and later enforcement
share one rule.

## Scope

Sweep production and test Kotlin under:

- `../../../runtime-kotlin` (every module, including `src/test` and `src/repoTest`)
- `../../../intellij-plugin`
- `../../../runtime-kotlin/build-logic`

Replace inline FQNs with `import` plus the simple name. Extension receivers
written as `private fun java.sql.PreparedStatement.bindOwnership` become
`import java.sql.PreparedStatement` and `private fun PreparedStatement.bindOwnership`.
Where two types share a simple name, use `import a.Foo as FooA` rather than
leaving an inline FQN.

A 2026-08-29 scan found ~338 production hits in 109 files and ~780 test hits
in 128 files (excluding string literals). Heaviest production files include
`FeatureTaskRuntimeRunLoop`, `GoalRunnerWorkflowStores`, and
`FeatureTaskRuntimeGoalContinuationRecorder`. Sweep the whole tree, not only
those files.

## Keep fully-qualified forms

- `package` and `import` lines
- String literals (logger names, resource paths, architecture allow-list
  tables, `ARCHITECTURE.md` inventories)
- Generated sources
- Compiler-required disambiguation after an import alias was tried and
  failed; record that site in the commit message

## Applicable Principles

- Types are imported and referenced by simple name.
- A surface is declared once; parallel hand-maintained qualification styles
  are a defect.
- Mechanical hygiene does not change behavior.

## Acceptance Criteria

1. No production or test Kotlin file in the scoped trees contains an inline
   fully-qualified type or callable reference of the form
   `java.|javax.|jakarta.|kotlin.|kotlinx.|org.|com.|dev.|skillbill.` with
   two or more remaining dots after the first identifier, except the keep
   list above.
2. No extension receiver, supertype, type argument, or `typealias` RHS uses
   an inline FQN when an import would compile.
3. Existing architecture-test string tables and `ARCHITECTURE.md` FQN
   inventories are unchanged in content (they are documentation of names, not
   Kotlin type positions).
4. `../../../scripts/validate` passes with no test assertion changed — only imports
   and simple-name substitutions.
5. No test is added by this subtask. Subtask 7 adds the enforcement scanner.

## Failure And Recovery Behavior

Unchanged. This subtask rewrites references only.

## Non-Goals

- Banning FQNs inside comments or KDoc (optional cleanup, not required).
- Changing the SKILL-52.3 adapter/infra inline-FQN leak guard; that guard
  stays and is complementary.
- Package moves (subtask 1) or file splits (subtasks 4–6).

## Dependency Notes

Runs after subtask 1 so the sweep lands on settled packages. Must not run
concurrently with subtask 1. Lands before oversized splits so extracted
collaborators copy short-name style.

## Validation Strategy

`../../../scripts/validate`. Re-run the inline-FQN scan used in the parent audit
(excluding string literals, import/package lines, and comments) and confirm
it reports only keep-list hits. No assertion edits.

## Next Path

Subtask 3 binds failure identity, exhaustive dispatch, and typed parse
boundaries.
