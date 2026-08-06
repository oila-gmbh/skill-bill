# SKILL-148 · Subtask 2: IntelliJ plugin architecture foundation

## Scope

Create the isolated IntelliJ Platform plugin project and its reusable status feature
architecture. Adapt the starter project's module and state-management principles to an
IntelliJ JVM plugin: thin platform entry points, inward dependency direction, explicit
project/application lifetimes, repository ports, immutable ViewModel state, and
lightweight persistence behind a port.

The initial module/package shape may remain physically compact when separate Gradle
modules would add cost without an enforced boundary, but dependency rules must be
machine-checked and packages must make future extraction straightforward.

## Acceptance Criteria

1. A top-level IntelliJ plugin build uses the IntelliJ Platform Gradle Plugin 2.x, Java/Kotlin toolchains compatible with the selected IDE baseline, centralized dependency versions, reproducible repositories, and tasks for tests, packaging, `runIde`, and Plugin Verifier.
2. Plugin metadata uses a stable plugin ID and documents the supported IntelliJ IDEA products/build range; the initial plugin has no unnecessary Java, Kotlin, Android, or other language-plugin dependency.
3. The architecture defines domain status/value types, a status repository port, a lightweight preference/cache port, an application refresh/polling coordinator, a project-scoped ViewModel, IntelliJ infrastructure adapters, and a composition root with dependencies pointing inward.
4. An architecture test rejects presentation-to-infrastructure shortcuts and any plugin import of Skill Bill runtime persistence implementations, workflow engines, filesystem manifest parsers, JDBC, or SQLite APIs.
5. The project-scoped ViewModel exposes immutable `StateFlow<SkillBillStatusUiState>`, maps domain outcomes exhaustively, accepts explicit refresh/lifecycle intents, derives goal/work and current-subtask elapsed durations from authoritative start timestamps through an injected clock, and contains no process, JSON, filesystem, or IntelliJ status-bar rendering code.
6. The CLI adapter executes the subtask 1 command with an explicit canonical project root off the Event Dispatch Thread, enforces timeout and output-size bounds, coalesces overlapping polls, validates contract version before mapping, and converts non-zero exits or malformed output into typed domain failures.
7. Polling starts only while its project service/widget consumer is active, uses a configurable conservative interval, cannot overlap, and is cancelled with all child processes when the IntelliJ project is disposed.
8. Preference persistence stores only settings such as CLI executable and refresh interval; an optional last-known display cache is marked with its observation time and can produce only a stale UI state, never an authoritative active state.
9. No token, prompt, phase artifact, raw stderr, absolute sensitive path, or unbounded process output is persisted or shown; diagnostics retain enough typed context for troubleshooting without leaking command output.
10. Unit tests cover exhaustive mapping, deterministic elapsed-time ticking, wall-clock rollback, absent legacy start timestamps, timeout, cancellation, process failure, incompatible contract, malformed JSON, cache fallback, refresh coalescing, and isolation between two project-scoped graphs.
11. `ARCHITECTURE.md` and contributor documentation map the adapted starter principles to IntelliJ services, domain/application/data/presentation ownership, persistence policy, source-of-truth rules, and the future tool-window extension point.

## Non-Goals

- Rendering or registering the status-bar widget.
- Creating a tool window or Compose UI.
- Adding relational persistence.
- Calling workflow mutation commands.
- Publishing or signing the plugin.
- General-purpose DI code generation; explicit constructor wiring or a small composition
  root is sufficient for the first feature.

## Dependency Notes

- Depends on subtask 1's versioned IDE status schema, CLI command, and JSON fixtures.
- Subtask 3 depends on the project service, ViewModel, UI state, and test fakes created
  here.

## Validation Strategy

- Run plugin pure unit tests and architecture tests without launching an IDE.
- Run CLI-adapter integration tests against recorded schema-valid fixtures and a bounded
  fake process.
- Run Gradle configuration-cache checks where supported.
- Build the plugin archive and run Plugin Verifier against the declared baseline.
- Run `git diff --check`.

## Next Path

Proceed to subtask 3 to expose the architecture through the first visible IDE surface.
