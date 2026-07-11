# SKILL-114 Subtask 3 - PHP Pack Depth

## Scope

Independently rebuild the PHP pack so it expresses PHP runtime and ecosystem
expertise rather than translated Go/backend prose. Preserve all ten areas and
cover modern supported PHP behavior with framework rules gated by detected
ownership.

Required depth includes PHP type/coercion/null/truthiness semantics,
exceptions and error handlers, references/copy-on-write, generators/fibers,
request-worker lifetime and mutable static/container state, Composer/autoload
and dependency behavior, HTTP/CLI/queue execution models, Symfony/Laravel and
PSR boundaries where detected, Doctrine/Eloquent/PDO transactions and query
shape, serialization/upload/template/path/process risks, PHPUnit/Pest and
static-analysis evidence, production workers/retries/shutdown/telemetry, and
server-rendered/component UI and accessibility behavior.

Deepen quality checking around Composer scripts and lock state, syntax,
formatting, PHPStan/Psalm, tests, framework checks, dependency/security audits,
generated/autoload state, extension/version matrices, and targeted-to-full
escalation.

## Acceptance Criteria

1. All ten PHP specialists meet the substance gate with concrete PHP runtime,
   framework, standard, toolchain, and failure consequences.
2. Correctness and architecture address PHP-specific coercion, truthiness,
   error/exception, request-worker lifetime, container/static state,
   autoloading, boundaries, and async/fiber concerns where applicable.
3. API, persistence, security, reliability, performance, and testing contain
   applicability-gated PDO/Doctrine/Eloquent, Symfony/Laravel/PSR,
   serialization/template/upload/process, worker, cache, queue, static-analysis
   and test failure modes.
4. UI and UX/accessibility cover PHP-owned templates and component frameworks,
   form/error behavior, escaping boundaries, keyboard/focus/semantics,
   localization, progressive enhancement, and server/client state handoff.
5. The quality checker discovers repository-owned commands and covers Composer,
   syntax, format, static analysis, tests, framework validation, dependency
   security, generated/autoload state, and supported runtime matrices.
6. PHP's shared-shingle result is at most 35%, every corresponding pair is at
   most 65%, and the PHP/Go pair passes without suppressions or synonym-only
   rewrites.
7. Manifest metadata, agents, routing, tests, and boundary history reflect the
   substantive PHP surface and remain manifest-driven.
8. PHP pack tests, `skill-bill validate`, and relevant Gradle checks pass.

## Non-Goals

- No claim that one PHP framework represents the language.
- No shared backend rubric that erases PHP execution-model differences.
- No Go content edits.

## Dependency Notes

Depends on subtask 1. Independent of other pack elevations.

## Validation Strategy

Run the maintained-pack audit for PHP, focused PHP pack tests, `skill-bill
validate`, and the relevant Gradle suite.

## Next Path

Proceed independently; subtask 10 waits for completion.
