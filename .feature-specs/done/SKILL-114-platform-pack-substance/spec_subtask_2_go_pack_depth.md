# SKILL-114 Subtask 2 - Go Pack Depth

## Scope

Rebuild the Go pack as an expert Go review surface rather than one half of a
generic backend template. Preserve all ten areas while grounding them in Go
language semantics, standard-library contracts, module/toolchain behavior,
common production frameworks, and reachable Go failure modes.

Coverage must include goroutine ownership and leaks, channel closure and
backpressure, context propagation, error identity/wrapping, nil/interface and
value-copy semantics, memory/escape/allocation behavior, `net/http`, RPC and
serialization contracts, `database/sql` and common query/ORM boundaries,
transactions and migrations, auth/input/template/path/process risks, tests and
race detection, graceful shutdown/queues/retries/observability, and Go-owned
web/TUI/template UI plus accessibility consequences. Framework-specific rules
must be applicability-gated rather than treated as universal Go behavior.

Deepen the quality checker around repository command discovery, workspace and
module modes, formatting, vet/static analysis, tests/race tests, vulnerability
checks, generation drift, build tags, cross-compilation where applicable, and
targeted-to-full escalation.

## Acceptance Criteria

1. All ten Go specialists meet the governed depth gate and name concrete Go
   constructs, APIs, tools, and observable failures; no generic backend rule is
   counted as Go-specific merely because “Go” was substituted into it.
2. Architecture, correctness, reliability, performance, and testing cover
   goroutine/scope ownership, channels, contexts, errors, memory/concurrency,
   shutdown, race detection, and test-value failure modes with clear lane
   boundaries.
3. API, persistence, and security cover concrete `net/http`/RPC/serialization,
   SQL/transaction/migration, template/input/path/process/dependency and
   authentication risks using applicability-gated ecosystem examples.
4. UI and UX/accessibility contain substantive guidance for Go-owned HTML
   templates/component frameworks, interactive CLI/TUI output, forms, focus,
   keyboard flow, semantics, localization, and error feedback; they are not
   generic frontend placeholders.
5. The Go quality checker covers discovered repo commands plus format, vet,
   static analysis, build, test/race, vulnerability, module/workspace,
   generation and build-tag concerns with safe fix ordering.
6. Go's shared-shingle result is at most 35%, every corresponding pair is at
   most 65%, and especially the Go/PHP pair passes without an exemption.
7. Go manifest focus metadata, native-agent descriptions, routing, tests, and
   `agent/history.md` reflect the completed substance without generated source
   artifacts.
8. Go pack tests, `skill-bill validate`, and the relevant Gradle checks pass.

## Non-Goals

- No exhaustive catalog of every Go web framework or ORM.
- No PHP edits except shared audit fixtures owned by subtask 1.
- No relocation of Go behavior into shared orchestration.

## Dependency Notes

Depends on subtask 1. May run in parallel with subtasks 3-7 and 9.

## Validation Strategy

Run the maintained-pack audit for Go, focused Go pack tests, `skill-bill
validate`, and the relevant `runtime-infra-fs` Gradle test suite.

## Next Path

Proceed independently; subtask 10 waits for completion.
