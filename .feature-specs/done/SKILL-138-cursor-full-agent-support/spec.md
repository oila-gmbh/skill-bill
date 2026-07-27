# SKILL-138 — Cursor Full Agent Support

## Outcome

Cursor becomes a first-class Skill Bill agent with the same supported product
surface as Claude Code and Codex: governed skills, MCP registration, native
subagents, terminal and desktop install/uninstall, runtime feature tasks and
goals, phase/model routing, delegated and parallel review, durable continuation,
telemetry attribution, validation, and documentation.

Cursor may be advertised as end-to-end supported only after the live Cursor CLI
gate in this spec passes. Compile-time wiring alone is insufficient.

## Background

Cursor's official contracts align with Skill Bill's provider abstractions:

- Global skills use `SKILL.md` under `~/.cursor/skills/` or
  `~/.agents/skills/`. Skill Bill will use `~/.cursor/skills/` so Cursor
  selection and cleanup remain provider-owned.
- Custom subagents are YAML-frontmatter Markdown files under
  `~/.cursor/agents/`; `name` and `description` are supported, with optional
  `model` and `readonly` fields.
- User MCP configuration is JSON at `~/.cursor/mcp.json` using `mcpServers`.
- The headless CLI is `agent --print`; automation supports `--force`,
  `--trust`, `--workspace`, `--model`, `--mode`, `--sandbox`,
  `--approve-mcps`, `--output-format stream-json`, and
  `--stream-partial-output`. Model parameters use bracket syntax such as
  `model-id[effort=high]`.

References:

- <https://cursor.com/docs/context/skills>
- <https://cursor.com/docs/subagents>
- <https://cursor.com/docs/context/mcp>
- <https://cursor.com/docs/cli/headless>
- <https://cursor.com/docs/cli/reference/parameters>
- <https://cursor.com/docs/cli/reference/permissions>

The Cursor CLI was not installed while this spec was prepared. Command/output
contracts require sanitized fixtures plus a gated live run against a recorded
Cursor CLI version before the support claim lands.

## Scope

1. Add `cursor` to every agent identity, schema, install, detection, desktop,
   selection, help, and persistence surface; install wrappers to
   `~/.cursor/skills`.
2. Render provider-neutral native agents as Cursor Markdown, link them into
   `~/.cursor/agents`, inventory/preflight/remove them, and expose CLI commands.
3. Register `skill-bill` in `~/.cursor/mcp.json`; extend install, uninstall,
   replay, desktop, and smoke-test behavior.
4. Add a Cursor command builder and stream-JSON decoder for runtime tasks,
   goals, continuation, phase routing, model/effort directives, and accounting.
5. Add governed Cursor review isolation, skill instructions, documentation,
   repository validation, and live end-to-end parity evidence.

## Acceptance Criteria

1. `cursor` is accepted everywhere `claude` and `codex` are accepted as an
   install or runtime agent, and all ordered supported-agent contracts agree.
2. Detection recognizes `~/.cursor`, manual selection accepts `cursor`, and the
   default Cursor skill target is `~/.cursor/skills` rather than the shared
   `~/.agents/skills` directory.
3. Existing generated `SKILL.md` wrappers install unchanged for Cursor; no
   Cursor-specific authored skill tree or committed wrapper is introduced.
4. Provider-neutral native-agent sources render to `cursor-agents/*.md` and
   link to `~/.cursor/agents/*.md` with valid Cursor frontmatter and unchanged
   governed bodies.
5. Cursor native links participate in inventory validation, integrity preflight,
   stale-link reconciliation, removal, uninstall, rollback, desktop previews,
   and generated-artifact exclusion/rejection.
6. Cursor native-agent path/link/unlink CLI commands exist and terminal/desktop
   installs report typed outcomes through the shared plan/apply path.
7. MCP registration mutates only Skill Bill's `mcpServers` entry in
   `~/.cursor/mcp.json`, preserves unrelated content, is idempotent, and
   unregisters cleanly.
8. `install.sh`, runtime install plan/apply, selection replay, `config.yaml`,
   both uninstall paths, and smoke tests cover Cursor skills, agents, and MCP.
9. `CursorAgentRunCommandBuilder` uses Cursor's documented non-interactive CLI,
   requested workspace, structured output, separate stdout/stderr, and no PTY.
10. Cursor JSONL decoding returns assistant result text without duplicate
    partial/buffered output, captures documented usage, bounds malformed input,
    and never treats malformed or empty output as a successful phase.
11. Cursor is absent from `RUNTIME_REFUSED_AGENTS`, registered in the headless
    adapters, and works for invoked-agent, override, per-phase, goal-child, and
    continuation routes.
12. Cursor supports model directives through `--model`; effort uses documented
    bracket parameters with deterministic merge and loud conflict handling.
13. Cursor review launches use a fresh context, explicitly select the installed
    specialist, deny unmediated shell/read/write/web/MCP operations, and expose
    lifecycle callbacks enforcing turn/result budgets.
14. Native-review preflight proves assigned Cursor workers resolve to current
    managed artifacts; missing, stale, malformed, replaced, or dangling links
    fail with the standard repair command.
15. Governed code-review, feature-task, and goal skills describe correct Cursor
    routing without provider switches in shared native-agent bodies.
16. Desktop first-run, results, removal previews, labels/resources, and packaged
    runtime assets include Cursor and remain exhaustive over domain enums.
17. README and maintainer docs state exact Cursor paths, behavior, generated
    boundaries, and support tier; `cursor-agents/` is forbidden committed output.
18. Unit, contract, schema, CLI, shell, desktop, snapshot, install, runtime,
    review-isolation, and removal tests cover Cursor success and rejection cases.
19. A live authenticated run records the Cursor version and proves install,
    discovery, MCP, runtime task, goal continuation, delegated/parallel review,
    cross-agent resume, and clean uninstall.
20. `skill-bill validate`, Gradle `check`, strict Agnix, agent-config validation,
    shell syntax, install smoke tests, and final `./install.sh` all pass.

## Non-Goals

- Cursor-specific authored copies of horizontal or platform-pack skills.
- Installing Cursor into `~/.agents/skills`.
- Cursor cloud agents, `/in-cloud`, `/babysit`, Agents Window, or SDK runtimes.
- Provider-private Cursor chat IDs as workflow continuation state.
- Undocumented Cursor environment-variable detection.
- Advertising Cursor parity before the live gate passes.

## Constraints

- Governed source remains `content.md`; `SKILL.md`, pointer files, and
  `cursor-agents/` remain generated install/cache output.
- Provider behavior uses renderer, command-builder, decoder, lifecycle,
  progress, and idle-policy strategies; no Cursor branch belongs in the process
  wait loop.
- MCP writes reuse standard atomic JSON merge behavior.
- Normal runtime approvals and governed review permissions are separate launch
  shapes; review may not inherit normal edit/MCP approvals.
- Tests use isolated homes and repositories and never mutate real Cursor config.

## Dependency Notes

Subtask 1 creates identity and path contracts. Subtask 2 adds native agents.
Subtask 3 consumes both for install/MCP/uninstall. Subtask 4 adds general runtime
execution after identity exists. Subtask 5 depends on all preceding work and
closes review governance, docs, validation, local install refresh, and live
parity.

## Validation Strategy

Each subtask runs its targeted tests. The final subtask runs all repository
gates and an opt-in authenticated Cursor harness. Structured-output fixtures
must be small, sanitized, and labeled with the producing Cursor version.

## Next Path

```bash
skill-bill goal SKILL-138
```
