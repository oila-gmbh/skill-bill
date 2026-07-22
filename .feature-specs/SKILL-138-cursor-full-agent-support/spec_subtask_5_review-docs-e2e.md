# SKILL-138 · Subtask 5 — Governed review, skill prose, docs, and live parity

Parent: [spec.md](./spec.md)

## Scope

### Governed Cursor review

- Give Cursor fresh-process review isolation and provider lifecycle callbacks.
- Invoke the assigned worker using Cursor's documented `/<name>` syntax in a
  clean process context.
- Prepare isolated `.cursor/cli.json` permissions denying `Shell`, `Read`,
  `Write`, `WebFetch`, and `Mcp`. Review must not use normal `--force` or
  `--approve-mcps`; use ask/sandbox behavior with prompt-contained evidence.
- Parse stream events incrementally for model turns, unique result bytes,
  termination, and completion usage.
- Extend preflight, assignment mapping, delegated/parallel launch, diagnostics,
  and accounting tests.

### Skills and documentation

- Update `bill-code-review` with Cursor routing, native subagent instructions,
  and CLI-delegated parallel review; do not claim stdin support without proof.
- Update `bill-code-review-parallel`, feature-task variants, and feature-goal
  support/examples so Cursor passes `--agent cursor` and is runtime-capable.
- Update README, capabilities, getting-started/team/internal architecture,
  source-generation, desktop, `AGENTS.md`, and native-agent docs with exact
  Cursor paths, commands, generated boundaries, and support tier.
- Run `./install.sh` after governed skill source changes.

### Live parity harness

Add/document an opt-in authenticated harness that records `agent --version`
without exposing credentials or mutating normal Cursor configuration. Prove:

1. Cursor-only install and discovery of a skill and native worker.
2. Skill Bill MCP startup from Cursor.
3. A small runtime feature task completing all phases.
4. A decomposed goal interruption and durable Cursor resume.
5. Delegated Cursor review and Cursor as a parallel lane.
6. A Claude/Codex-paused workflow resumed under Cursor.
7. Uninstall preserving unrelated Cursor skills, agents, and MCP servers.

## Acceptance Criteria

1. Cursor specialist launches use fresh context, assigned managed subagent,
   tool-denied workspace, bounded evidence, and no normal edit/MCP approvals.
2. Lifecycle callbacks enforce turn/result budgets and unique result admission;
   forbidden operations, malformed streams, termination, and provider failures
   are typed.
3. Delegated and parallel Cursor review produces owned, attributed findings and
   correct accounting/usage.
4. Governed skill content names Cursor correctly while native bodies remain
   provider-neutral and routing stays manifest-driven.
5. Docs state exact paths, commands, generated rules, support tier, and live
   requirement without overstating parity.
6. The live harness passes all seven scenarios and records the tested version
   before support claims are upgraded.
7. All repository gates and smoke tests pass, then `./install.sh` refreshes local
   staging.

## Non-Goals

- Cloud agents, editor UI automation, provider chat resume, or weakened review
  isolation.

## Dependencies

- depends_on: `[1, 2, 3, 4]`
- dependency_reason: Review/e2e need identity, native agents, MCP/install, and
  runtime execution.

## Validation Strategy

```bash
skill-bill validate
cd runtime-kotlin && ./gradlew check
npx --yes agnix --strict .
scripts/validate_agent_configs
bash -n install.sh && bash -n uninstall.sh
scripts/install_smoke_test.sh
scripts/agent_install_smoke_test.sh cursor
./install.sh
```

Run the authenticated Cursor harness separately and attach sanitized evidence.

## Next Path

Run `bill-feature-task` on this spec after subtasks 1–4.
