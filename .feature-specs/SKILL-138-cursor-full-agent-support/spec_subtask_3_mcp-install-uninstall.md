# SKILL-138 · Subtask 3 — MCP, terminal install/uninstall, replay, and smoke tests

Parent: [spec.md](./spec.md)

## Scope

- Add Cursor to MCP register/unregister/config-path branches; target
  `~/.cursor/mcp.json` and reuse standard `mcpServers` merge semantics.
- Test absent, empty, populated, malformed, repeated, and unrelated-content
  cases with atomic typed failures.
- Add Cursor to `config.yaml`, installer selection/help/replay/summary, CLI help,
  desktop outcomes, and shell/Kotlin uninstall plans.
- Remove only managed Cursor skill links, native links, and Skill Bill's MCP
  entry; preserve all user-owned Cursor content.
- Extend install-plan/apply, shell delegation allowlists, smoke scripts, syntax,
  and cleanup tests.

## Acceptance Criteria

1. Cursor MCP register/unregister is correct, atomic, idempotent, and preserves
   unrelated keys and servers.
2. Cursor-only manual and detected installs apply skills, agents, and MCP to
   Cursor-owned paths.
3. Selection replay round-trips Cursor and malformed state fails loudly.
4. Shell and Kotlin uninstall remove only managed Skill Bill Cursor state and
   report partial failures accurately.
5. Installer prompts/help/summaries and smoke expectations include Cursor in
   canonical order.
6. Shell syntax, CLI equivalence, MCP, uninstall, architecture, and smoke tests
   pass with isolated homes.

## Non-Goals

- Running the Cursor model or changing non-Skill-Bill Cursor settings.

## Dependencies

- depends_on: `[1, 2]`
- dependency_reason: Orchestration consumes Cursor paths and native commands.

## Validation Strategy

```bash
bash -n install.sh && bash -n uninstall.sh
scripts/install_smoke_test.sh
scripts/agent_install_smoke_test.sh cursor
cd runtime-kotlin && ./gradlew :runtime-infra-fs:test :runtime-cli:test :runtime-core:test
```

## Next Path

Run `bill-feature-task` on this spec after subtasks 1 and 2.
