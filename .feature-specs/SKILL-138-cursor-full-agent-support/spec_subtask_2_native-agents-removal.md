# SKILL-138 · Subtask 2 — Cursor native agents, inventory, CLI, and removal

Parent: [spec.md](./spec.md)

## Scope

- Add `NativeAgentProvider.Cursor("cursor-agents", "md")`, rendering valid
  YAML-frontmatter Markdown and targeting `~/.cursor/agents`.
- Activate it only for a Cursor home/agent directory and update exhaustive
  provider mappings and validators.
- Implement Cursor link/unlink, `CURSOR_AGENTS_KIND`, apply installer, port
  adapters, generation, ownership, reconciliation, and rollback.
- Add Cursor to inventory schema/validation and review preflight; reject stale,
  malformed, replaced, wrong-generation, or digest-mismatched artifacts.
- Add `cursor-agents-path`, `link-cursor-agents`, and
  `unlink-cursor-agents` CLI commands and runtime-surface registration.
- Include Cursor in skill/platform removal execution, desktop mappings/labels,
  and uninstall primitives; provider loops must be exhaustive.
- Treat `cursor-agents/` as generated output in repository validation, add-on
  discovery, packaging exclusions, fixtures, and project instructions.

## Acceptance Criteria

1. A provider-neutral source renders valid Cursor agent frontmatter, filename,
   description, and byte-equivalent governed body.
2. Install links selected workers into `~/.cursor/agents`, preserves user files,
   replaces only managed links, and inventories digest/source/target.
3. Reinstall is idempotent; generation changes reconcile stale links; integrity
   violations fail preflight with the repair command.
4. Cursor path/link/unlink commands are registered, continuation-guarded,
   platform-aware, and covered by runtime-surface tests.
5. Removal preview/execution and desktop mapping include Cursor with typed
   failure handling.
6. Repo validation rejects committed Cursor output and packaging excludes it
   while retaining provider-neutral sources.
7. Snapshot, operations, apply, inventory, preflight, CLI, removal, validation,
   and packaging tests pass.

## Non-Goals

- MCP registration, terminal orchestration, or Cursor process execution.

## Dependencies

- depends_on: `[1]`
- dependency_reason: Installers and mappings need subtask 1's enums/paths.

## Validation Strategy

```bash
cd runtime-kotlin && ./gradlew :runtime-infra-fs:test :runtime-cli:test \
  :runtime-core:test :runtime-desktop:jvmTest
```

## Next Path

Run `bill-feature-task` on this spec after subtask 1.
