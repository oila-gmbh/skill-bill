---
name: bill-kotlin-quality-check
description: Run ./gradlew check and systematically fix all issues without using suppressions. Use when running Gradle checks, fixing lint errors, formatting issues, test failures, or deprecation warnings in Android/Kotlin projects. Fixes issues properly at the root cause instead of suppressing them.
---

# Kotlin Quality Check

Execute `./gradlew check` and systematically fix issues **only in files changed in the current unit of work**. Ignore pre-existing issues in untouched files.

## Execution Steps

1. **Determine changed files**: Use `git diff --name-only` (against the base branch or HEAD) to identify files changed in the current unit of work
2. **Run initial check**: Execute `./gradlew check` and capture complete output
3. **Filter to changed files**: From the check output, only address issues in files from step 1 — skip everything else
4. **Categorize issues**: Group by type (structural, formatting, lint errors, test failures, deprecations, etc.)
4. **Fix systematically**: For each issue category in priority order:
   - Mark todo as in_progress
   - Read affected files
   - Implement proper fixes (never suppress)
   - Mark todo as completed
5. **Verify fixes**: Re-run `./gradlew check` after all fixes
6. **Iterate if needed**: If new issues appear, repeat the process

## Fix Strategy

**Always Fix, Never Suppress:**
- ❌ Never use `@Suppress`, `@SuppressWarnings`, or lint suppressions
- ❌ Never add `// TODO` or `// FIXME` comments to defer issues
- ❌ Never use `#pragma` or similar directives to hide issues
- ✅ Implement proper solutions that address the root cause
- ✅ Refactor code to eliminate warnings
- ✅ Add missing tests or fix failing ones

**Priority Order:**
0. Structural issues (package/file location, file naming) - **Fix these first**
1. Formatting issues (ktfmt, detekt formatting rules)
2. Lint errors (unused imports, naming conventions, etc.)
3. Deprecation warnings (migrate to new APIs)
4. Logic issues (null safety, type issues, etc.)
5. Test failures (fix implementation or test logic)

**Structural Fixes (Priority 0):**

These issues require file operations and should be fixed before other issues:

- **`InvalidPackageDeclaration`**: Package doesn't match directory structure
  - Move file to correct directory matching the package declaration
  - Create directory structure if it doesn't exist
  - Example: `package com.example.feature.data` → file must be in `com/example/feature/data/`

- **`MatchingDeclarationName`**: File name doesn't match top-level declaration
  - Rename file to match the interface/class/object name
  - Example: `UserComponentManager.kt` containing `interface AccountComponentManager` → rename to `AccountComponentManager.kt`

- **After moving/renaming files:**
  - Verify package declarations are correct
  - Check for and fix any broken imports in other files
  - Re-run check to ensure no compilation errors introduced

**When to Ask User:**
- Architectural decisions (e.g., choosing between design patterns)
- Breaking API changes that affect multiple modules
- Test failures where business logic is unclear
- Security-related issues requiring policy decisions
- When multiple valid fix approaches exist with trade-offs

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-quality-check` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Code Style Guidelines

- Use 2-space indentation
- Avoid comments unless absolutely necessary — prefer self-describing code
- Use `.orEmpty()` instead of `?: ""`
- Never use fully qualified names in code
- Use string resources instead of hardcoded strings
- Repositories should not throw exceptions — return concrete types
- Never use `kotlin.Result`
- Never use `Any` type
- Use `DispatcherProvider` instead of `Dispatchers.*`
- Never use `relaxed = true` for mockk mocks (`relaxedUnitFun` is fine)

**LongParameterList fix strategies:**
- Extract related params into:
  - `data class` - for variables
  - `interface` - for navigation/action lambdas

## Output Format

Provide clear progress updates:
- Show issue count by category
- Report each fix with file path and line number
- Display final `./gradlew check` result
- Summarize all changes made
