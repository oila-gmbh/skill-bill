---
status: Ready
parent_spec: ./spec.md
subtask_id: 8
---

# SKILL-124 · Subtask 8 — JDBC retirement and hardening

## Scope

Complete the migration, prevent regression to manual persistence, and document
the new contributor workflow.

1. Inventory production `java.sql`, `PreparedStatement`, `ResultSet`, raw SQL,
   and schema/migration references in `runtime-infra-sqlite`.
2. Remove obsolete JDBC wrappers, row mappers, bind helpers, duplicated SQL
   constants, legacy schema code, and unused dependencies.
3. Keep only narrowly justified low-level seams for driver bootstrap, PRAGMAs,
   SQLDelight connection integration, or migration callbacks; centralize and
   allowlist them by package/file.
4. Add architecture tests that fail if ordinary repository code reintroduces
   `ResultSet.get*`, positional `PreparedStatement.set*`, ad hoc connection
   creation, generated types outside infrastructure, or committed generated
   output.
5. Update `runtime-kotlin/ARCHITECTURE.md`, module documentation, contributor
   guidance, and database-change instructions.
6. Run all focused and full validation, including CLI/MCP golden tests and
   desktop integration against an upgraded shared database.
7. Record final size/complexity evidence: removed manual mapping/binding sites,
   remaining allowlisted low-level sites, generated source boundary, and query/
   migration verification tasks.

## Acceptance Criteria (this subtask)

1. Ordinary production repository operations contain no handwritten
   `ResultSet.get*` mapping or positional `PreparedStatement.set*` binding.
2. Remaining direct JDBC/driver code is minimal, named by purpose, documented by
   a non-obvious compatibility reason, and protected by an explicit architecture
   allowlist.
3. SQLDelight/generated types do not leak into ports, domain, application, CLI,
   MCP, or desktop feature modules.
4. Architecture tests reject manual-row-mapping regression, ad hoc database
   connections, duplicate migration ownership, and committed generated output.
5. Documentation tells maintainers where to edit schema, queries, migrations,
   and adapters; how to regenerate/verify; and how existing databases are
   adopted.
6. CLI, MCP, desktop, workflows, telemetry, review, learning, work-list, install,
   and migration suites pass against fresh and upgraded databases.
7. The complete maintainer validation suite passes.

## Non-Goals

- Removing the underlying SQLite JDBC driver when SQLDelight's JVM driver still
  requires it.
- Introducing a second ORM or query framework.
- Product behavior changes or schema redesign after parity is achieved.
- Performance work unrelated to regressions caused by this migration.

## Dependencies

- Subtasks 1 through 7 must be complete.

## Validation Strategy

- Run architecture/import scans and focused database checks first.
- Run:

  ```bash
  skill-bill validate
  (cd runtime-kotlin && ./gradlew check)
  npx --yes agnix --strict .
  scripts/validate_agent_configs
  ```

- Verify a clean build produces no tracked generated files and a second build is
  configuration-cache/incremental-build clean.
- Exercise a real prior-version database through CLI, MCP, and desktop read
  paths after migration.

## Next Path

Run `bill-feature-task` on
`.feature-specs/SKILL-124-sqldelight-runtime-persistence/spec_subtask_8_jdbc-retirement-hardening.md`.
