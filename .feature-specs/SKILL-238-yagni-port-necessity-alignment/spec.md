# SKILL-238: YAGNI / Port Necessity alignment

## Intended Outcome

Bring the repository in line with the documented **Port Necessity And
Deletion** rule (`docs/code-principles.md`): remove leftover speculative
surfaces, collapse same-module one-implementation role-port bags and thin
application forwarders, and shrink duplicated IDE CLI mutators — without
touching hexagonal `skillbill.ports.*` adapters that earn their place via the
composition-root module boundary, and without weakening governed contracts,
loud-fail seams, `contract_version`, or validator-backed rules.

This feature implements the cuts from the 2026-09-12 repo-scope
`bill-over-engineering-review` (YAGNI) investigation. Prior sweeps
(SKILL-34/132/162/175/200/210/232) already removed much speculative surface;
remaining debt is concentrated, not systemic.

## Acceptance Criteria

1. Confirmed dead leftovers from the investigation are removed: `scripts/split-runloop.py`, undeclared TypeScript SKILL-116 add-on scaffolds under `platform-packs/typescript/addons/`, the unused `jna` catalog entry, the empty `app/.../ampli/` tree, and either deletion or a single pointer stub for `docs/delegated-review/` (live contract remains `orchestration/review-delegation/`).
2. Same-module role-port interface + `Default*` pairs for feature-task phase gates, parallel code review, goal-planning sweep, and goal-runner boundaries are collapsed to data classes or direct constructor injection; DI bindings and affected tests compile and pass.
3. Thin application forwarders that only rename a single port/domain call (`UninstallFileSystemService`, and `InstallAgentService` / `SkillRemoveService` when still pure forwarders) are removed or inlined so CLI/MCP call the port or domain type directly.
4. IntelliJ and VS Code pause/stop CLI repositories share one parameterized mutator; dead `StatusClock.from` (or twin) and unused `defaultRefreshIntervalSeconds` helpers are removed in both extensions without deleting the live `StatusClock` test seam.
5. Hexagonal `skillbill.ports.*` with one FS/SQLite adapter, `GoalRunnerManifestStore` ISP slices cited as preferred shape in `docs/code-principles.md`, governed contracts, and validator-backed rules remain intact in intent.
6. Area `agent/history.md` entries record the sweep with reuse notes for touched boundaries.
7. No mechanical single-impl Port Necessity architecture-test census is required in this feature (explicitly deferred since SKILL-232); optional follow-up only.

## Constraints

- Prefer deletion over abstraction. Do not invent a second implementation to “justify” keeping an interface.
- Keep hexagonal ports that cross the composition-root module boundary even when they have one production adapter.
- Do not treat loud-fail typed errors, schema parity tests, or `contract_version` constants as over-engineering.
- Preserve IDE isolation from runtime SQLite / `runtime-kotlin` internals.
- Team-control-plane roadmap prose may be trimmed to shipped surfaces; do not implement unbuilt `skill-bill team export/sync` CLI in this feature.
- GLM uninstall cleanup past the 2026-08-02 window may be removed only after confirming no remaining seeds; keep Copilot historical sweep paths (SKILL-210).

## Non-Goals

- Collapsing hexagonal ports with one production adapter solely because they look single-impl.
- Collapsing `GoalRunnerManifestQueries` / `ExecutionCommands` / `ControlWrites` / `StateWrites` ISP slices on `GoalRunnerManifestStore`.
- Correctness, security, or performance refactors (route those to `bill-code-review`).
- Count-2 `internal` unused sweep (still deferred).
- Filling undeclared TypeScript add-on scaffolds; prefer delete.
- Authoring a Port Necessity architecture-test ratchet (follow-up only).
- Rewriting platform-pack authored content beyond deleting undeclared scaffolds.

## Validation Strategy

- Schema-valid decomposition manifest and acceptance-criteria extractable by the feature-task runtime from every spec.
- After subtask 1: path absence checks + `skill-bill` / install smoke expectations that reference kept paths.
- After subtask 2: `./gradlew` compile (and existing tests) for `runtime-engine`, `runtime-application`, `runtime-core`, `runtime-cli`, and touched infra modules.
- After subtask 3: IntelliJ plugin and VS Code extension builds succeed.

## Delivery Plan

1. Delete speculative leftovers (zero behavior change).
2. Collapse runtime-kotlin same-module YAGNI (behavior-preserving refactor).
3. IDE extension YAGNI parity (separate product surface).
