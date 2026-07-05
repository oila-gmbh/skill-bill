---
name: bill-php-code-check
description: Run PHP project quality checks and systematically fix issues in changed files without suppressions, including tests, linting, static analysis, architecture checks, and dependency audits.
internal-for: bill-code-check
---

# PHP Quality Check

## Execution Steps

1. Determine changed files using `git diff --name-only` against the relevant base.
2. Discover the project's own quality commands before running checks.
3. Run the project's quality-check commands and capture complete output.
4. Filter the results to issues in changed files only.
5. Categorize issues by type: structural, formatting, lint, static analysis, architecture checks, tests, security or audit failures.
6. Fix systematically by category in priority order.
7. Re-run the quality-check commands after all fixes.
8. Iterate if new issues appear.

## Command Discovery

- Use local project standards and established repo command conventions before choosing PHP defaults
- Inspect `composer.json` scripts first; prefer repo-owned Composer scripts over bare tool invocations
- If Composer scripts are insufficient, inspect `Makefile`, `justfile`, CI workflows, and local tool configuration before choosing commands
- Common PHP tool config files include `phpunit.xml`, `phpunit.xml.dist`, `pest.php`, `phpstan.neon`, `phpstan.neon.dist`, `psalm.xml`, `psalm.xml.dist`, `phpcs.xml`, `phpcs.xml.dist`, `.php-cs-fixer.php`, `ecs.php`, `pint.json`, `rector.php`, and `deptrac.yaml`
- Prefer existing project wrappers, containers, or scripts when the repo clearly defines them; do not invent a new quality stack
- If a tool config exists but no wrapper command exists, run the matching `vendor/bin` or configured tool only when the repo convention is clear
- Use globally installed tools only when project guidance or repository convention explicitly points there
- Do not install new tools or rewrite quality configuration as the default way to make checks pass

## Fix Strategy

### Always Fix, Never Suppress

- Never add suppressions, baseline entries, or ignore rules as the default fix
- Never rewrite PHPStan, Psalm, Rector, Deptrac, PHPCS, or formatter baselines just to hide current failures
- Never add `TODO` or `FIXME` comments to defer issues
- Never skip required project scripts silently
- Implement proper solutions that address the root cause
- Refactor code to eliminate warnings
- Add missing tests or fix failing ones

### Priority Order

0. Structural issues such as PSR-4 autoload, class/file location, or namespace mismatch
1. Formatting issues
2. Lint errors
3. Static analysis issues such as PHPStan, Psalm, type errors, or dead code
4. Architecture or boundary issues such as Deptrac
5. Test failures
6. Security or dependency audit failures

### Structural Fixes

- PSR-4 or autoload mismatch:
  Move the file to match the declared namespace, or fix the namespace to match the intended path.
- File name does not match the top-level class, interface, trait, or enum:
  Rename the file to match the declaration and fix broken imports or usages afterwards.
- After moving or renaming files:
  Verify namespaces, rebuild autoload metadata if the project requires it, and re-run checks.

### When to Ask the User

- Architectural decisions with meaningful trade-offs
- Breaking API changes that affect multiple modules
- Test failures where the business logic is unclear
- Security-related issues requiring policy decisions
- Cases where multiple valid fix approaches exist and the repo does not make the preference obvious

### PHP-Specific Guidance

- Follow the project's formatter and coding-standard rules
- Prefer the project's wrapper command over bare tools when one exists
- If the repo defines both fixer and verifier commands, run fixers before read-only analyzers when that reduces churn
- Common PHP quality commands may include PHPUnit or Pest tests, PHP syntax linting, Pint, PHP-CS-Fixer, PHPCS, ECS, PHPStan, Psalm, Rector, Deptrac, Composer validate, and dependency audit
- If a required command cannot be run, report that explicitly with the reason
