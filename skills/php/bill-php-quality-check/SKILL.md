---
name: bill-php-quality-check
description: Run the project's canonical quality-check command(s) and systematically fix all issues without using suppressions. Use when running repo-defined checks, tests, linting, static analysis, architecture checks, or security validation in PHP/Laravel projects. Fixes issues properly at the root cause instead of suppressing them. Use when user mentions run PHP checks, PHPStan, PHP CS Fixer, Laravel Pint, or fix PHP lint issues.
---

# PHP Quality Check

This is the current PHP implementation behind the shared `bill-quality-check` router. Invoke it directly only when you already know the repo should use the PHP quality-check path.

Execute the project's quality-check flow and systematically fix issues **only in files changed in the current unit of work**. Ignore pre-existing issues in untouched files unless the change clearly exposes them.

## Execution Steps

1. **Determine changed files**: Use `git diff --name-only` (against the base branch or HEAD) to identify files changed in the current unit of work
2. **Run initial check**: Execute the project's quality-check commands and capture complete output
3. **Filter to changed files**: From the check output, only address issues in files from step 1 — skip everything else
4. **Categorize issues**: Group by type (structural, formatting, lint errors, static analysis, architecture checks, test failures, security/audit failures, etc.)
5. **Fix systematically**: For each issue category in priority order:
   - Mark todo as in_progress
   - Read affected files
   - Implement proper fixes (never suppress)
   - Mark todo as completed
6. **Verify fixes**: Re-run the quality-check command(s) after all fixes
7. **Iterate if needed**: If new issues appear, repeat the process

## Fix Strategy

**Always Fix, Never Suppress:**
- Never add suppressions, baseline entries, or ignore rules as the default fix
- Never add `// TODO` or `// FIXME` comments to defer issues
- Never skip required project scripts silently
- Implement proper solutions that address the root cause
- Refactor code to eliminate warnings
- Add missing tests or fix failing ones

**Priority Order:**
0. Structural issues (PSR-4 autoload, class/file location, namespace mismatch) when present
1. Formatting issues (formatter / coding-standard violations)
2. Lint errors (unused imports, naming conventions, etc.)
3. Static analysis issues (`phpstan`, `psalm`, type errors, dead code)
4. Architecture or boundary issues (`deptrac`, layering, modularity checks)
5. Test failures (fix implementation or test logic)
6. Security or dependency audit failures

**Structural Fixes (Priority 0):**

These issues require file operations and should be fixed before other issues:

- **PSR-4 / autoload mismatch**:
  - Move the file to match the declared namespace, or fix the namespace to match the intended path
  - Example: `App/Module/User/Application/Service/Foo` must live under the matching PSR-4 path

- **File name does not match top-level class/interface/trait/enum**:
  - Rename the file to match the declaration
  - Fix broken imports/usages after renaming

- **After moving/renaming files:**
  - Verify namespaces are correct
  - Rebuild autoload metadata if the project requires it
  - Re-run checks to ensure no new errors were introduced

**When to Ask User:**
- Architectural decisions (e.g., choosing between design patterns)
- Breaking API changes that affect multiple modules
- Test failures where business logic is unclear
- Security-related issues requiring policy decisions
- When multiple valid fix approaches exist with trade-offs

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-php-quality-check` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## PHP-Specific Guidance

- Follow the project's formatter and coding-standard rules
- Prefer the project's wrapper command over bare tools when one exists
- In Laravel projects, prefer Sail commands when the repo is clearly Sail-based; use `./vendor/bin/sail ...`
- If the repo defines both fixer and verifier commands, run fixers before read-only analyzers when that reduces churn
- In PHP-first repos, common quality commands may include tests, lint / lint:fix, `phpstan` / `psalm`, `deptrac`, and audit
- If a required command cannot be run, report that explicitly with the reason

## Output Format

Provide clear progress updates:
- Show issue count by category
- Report each fix with file path and line number
- Display the final quality-check result
- Summarize all changes made
- If a required command could not be run, report that explicitly with the reason
