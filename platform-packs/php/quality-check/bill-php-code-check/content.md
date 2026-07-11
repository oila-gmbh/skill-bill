---
name: bill-php-code-check
description: Discover and run repository-owned PHP validation, formatting, analysis, tests, framework checks, audits, and runtime-matrix checks without suppressions.
internal-for: bill-code-check
---

# PHP Quality Check

## Purpose

Execute the repository's authoritative PHP checks, repair only failures belonging to the scoped work, and report any command that cannot run with its residual risk.

## Execution Steps

1. Determine the requested scope from the branch diff and list changed files before selecting commands.
2. Discover the project's own quality commands from the build file, repository wrapper, and CI configuration, in that order, before falling back to PHP defaults.
3. Inspect owned configuration such as `phpunit.xml`, `pest.php`, `phpstan.neon`, `psalm.xml`, `phpcs.xml`, `.php-cs-fixer.php`, `ecs.php`, `pint.json`, `rector.php`, `deptrac.yaml`, Symfony config, and Laravel bootstrap files.
4. Prefer the discovered wrapper, Composer script, or container command; use direct `vendor/bin` tools only when repository evidence establishes that convention.
5. Determine files in scope and run the pack's quality-check entrypoint through the discovered commands, using narrow or changed-file checks when they preserve repository configuration and bootstrap behavior.
6. Attribute every failure to changed work, a pre-existing condition, or an environmental blocker; never claim ownership merely because a full command reported it.
7. Apply the priority fix ladder below to scoped failures without new ignores, baselines, or weakened settings.
8. Re-run each targeted failing command after repair, then escalate to the repository-authoritative full suite when shared code, configuration, generated state, or cross-cutting behavior changed.
9. Report commands, outcomes, pre-existing failures, blockers, and residual risk. A missing dependency, service, credential, extension, or maintainer decision is a blocker, not an implicit pass.

### Required PHP Coverage

- Validate Composer metadata and lock consistency through an owned script or `composer validate --strict`; invalid constraints or a stale `composer.lock` cause reproducibility failures.
- Run PHP syntax validation using the repository wrapper or a safe changed-file `php -l`; syntax errors must fail before later analyzers.
- Run the configured formatter or style verifier, such as `composer lint`, `vendor/bin/php-cs-fixer fix --dry-run --diff`, `vendor/bin/phpcs`, `vendor/bin/ecs check`, or `vendor/bin/pint --test`.
- Run PHPStan or Psalm through the owned script, for example `composer phpstan`, `vendor/bin/phpstan analyse`, or `vendor/bin/psalm`; do not add baseline entries to hide changed-code type failures.
- Run PHPUnit or Pest through the project bootstrap, such as `composer test`, `vendor/bin/phpunit`, or `vendor/bin/pest`, preserving database, cache, and extension requirements.
- Run detected framework validation, such as `bin/console lint:container`, `bin/console lint:yaml config`, Laravel route/config tests, or the repository's framework check script.
- Run dependency security through the owned wrapper or `composer audit`; a reachable advisory remains a reported security failure unless the repository's policy determines otherwise.
- Verify generated and autoload state with repository commands such as `composer dump-autoload --strict-psr` when namespaces, files, generated containers, proxies, caches, or class maps changed.
- Exercise the supported PHP version, extension, dependency, and database matrix through declared CI jobs or local matrix commands when compatibility surfaces changed.

### Scope and Escalation

- Use targeted syntax, formatter, analyzer, and test selections only when they preserve the same configuration as the full command.
- Run the full suite after changes to `composer.json`, `composer.lock`, autoload mappings, analyzer configuration, framework bootstrap, shared fixtures, or cross-module behavior.
- Distinguish a pre-existing full-suite failure from changed-file failure with reproducible evidence; do not modify unrelated files to make the scoped work appear green.
- If a required service or runtime matrix is unavailable, record the exact blocked command and the correctness, security, or compatibility risk left unverified.

## Fix Strategy

Use this priority-ordered fix ladder and never suppress a failure:

1. Repair Composer constraints, PSR-4 namespaces, file locations, generated state, and autoload failures.
2. Apply the repository formatter, then fix remaining syntax and style violations at their source.
3. Correct PHPStan, Psalm, type, deprecation, and architecture failures without suppressions or baseline growth.
4. Fix behavioral, integration, and framework test failures with production changes plus meaningful regression proof.
5. Resolve dependency audit failures according to repository policy, escalating version or compatibility trade-offs for a maintainer decision.

Never skip required scripts silently, edit snapshots mechanically, relax configuration, add ignore rules, or install a replacement toolchain merely to pass. After every repair, re-run targeted checks. Run the full suite when targeted checks cannot establish safety or shared configuration and behavior changed.
