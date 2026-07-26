# SKILL-52.3 Subtask 2 - Domain Effect Purity

Parent spec: [.feature-specs/SKILL-52.3-runtime-hexagon-leak-closure/spec.md](./spec.md)
Issue key: SKILL-52.3
Subtask order: 2 of 5
Depends on: none (independent of subtask 1; sequence after it for a clean branch)
Branch model: same-branch, commit per subtask

## Purpose

Make `runtime-domain` effect-free: remove non-deterministic entropy, system
clock reads, and JVM logging from the innermost layer so domain functions are
pure and deterministic. These are injected concerns that belong to adapters or
composition.

## Scope

In scope:

- `runtime-domain/.../telemetry/TelemetryConfigRules.kt:9` —
  `"install_id" to UUID.randomUUID().toString()` inside
  `defaultLocalTelemetryConfig()`. Replace with an injected id source: pass the
  install id (or an `() -> String` / value-typed id) into the factory from the
  adapter/composition seam that already owns environment data. The default
  config builder must become deterministic given its inputs.
- `runtime-domain/.../telemetry/TelemetryRemoteStatsRuntime.kt:14,22` — default
  args `today: LocalDate = LocalDate.now(ZoneOffset.UTC)`. Remove the
  clock-reading default; require callers (application/adapter) to pass the date.
  Application/adapter code may read the clock and pass the value in.
- `runtime-domain/.../domain/skillremove/SkillRemove.kt` — `java.util.logging.Logger`
  usage (`log.info(...)` at execute begin/success/failure). Choose the smallest
  behavior-preserving option:
  - route through a domain-owned logging/observation port injected by
    composition; or
  - move the log statements to the adapter that drives skill-remove
    (`runtime-infra-fs` skill-remove ports / CLI) and have the domain return the
    typed outcome it already produces.
  Preserve the path-sanitized message content if logging is retained anywhere.
- Audit `runtime-domain` for any other entropy/clock/process reads introduced
  since the assessment (`UUID.`, `Random`, `.now(`, `currentTimeMillis`,
  `nanoTime`, `System.`) and remove them the same way.

Out of scope:

- `java.nio.file.Path` as inert data — explicitly retained per the existing
  decisions.md carve-out.
- Validator/parse/IO extraction (subtask 1).
- Changing telemetry config shape, install-id semantics, remote-stats output,
  or skill-remove behavior/exit semantics.

## Acceptance Criteria

1. `runtime-domain` main source contains no `UUID.randomUUID()`, no `Random`
   entropy source, and no `java.util.logging` import or usage.
2. `runtime-domain` main source contains no system-clock read
   (`LocalDate.now`, `Instant.now`, `System.currentTimeMillis`,
   `System.nanoTime`, `Clock.system*`); current time is supplied by callers.
3. `defaultLocalTelemetryConfig()` (and any other affected factory) is
   deterministic given its inputs; the install id is injected.
4. Telemetry config, remote-stats, and skill-remove behavior is unchanged —
   CLI/MCP output and persisted telemetry config remain byte-equivalent where
   golden tests exist.
5. A new or extended architecture test fails if `runtime-domain` reintroduces
   `UUID.randomUUID`, a clock read, or `java.util.logging`.
6. Focused telemetry, skill-remove, and architecture tests pass.

## Validation

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-domain:test)
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*Telemetry*')
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*SkillRemove*')
(cd runtime-kotlin && ./gradlew :runtime-mcp:test --tests '*Telemetry*')
```

## Implementation Notes

- The clock/id injection seam already exists conceptually: telemetry settings
  flow from `RuntimeContext.environment` (a `Map<String,String>` injected by
  composition). Prefer threading the id/date through the existing context/request
  models rather than introducing a new DI singleton.
- Add the new bans to the existing
  `domain and ports avoid JDBC HTTP and entrypoint frameworks` test or a sibling
  test in `RuntimeArchitectureTest.kt` so they are enforced by source-text scan
  (see subtask 5 for the scanning-hardening helper).
- If a logging port is chosen, wire a no-op default in `RuntimeContext` exactly
  as `UnconfiguredHttpRequester` / `NoopWorkflowGitOperations` are wired today,
  so existing tests that do not care about logging stay green.
