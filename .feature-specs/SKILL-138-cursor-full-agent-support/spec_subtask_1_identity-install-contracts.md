# SKILL-138 · Subtask 1 — Agent identity, schemas, paths, and desktop selection

Parent: [spec.md](./spec.md)

## Scope

- Add `CURSOR("cursor")` to `InstallAgent` and `NativeAgentProviderId`.
- Add Cursor to `NativeAgentLinkProvider`, `AgentSymlinkProvider`,
  `DesktopAgentSymlinkProvider`, and `FirstRunSetupAgent` (`Cursor`).
- Add it to `SUPPORTED_AGENTS`, `agentPaths`, detection, default targets,
  plan/apply fixtures, help, and persisted selections.
- Use `~/.cursor/skills` and detect `~/.cursor`; do not select shared
  `~/.agents/skills`.
- Add Cursor to the install-plan agent schema and schema/Kotlin parity tests.
- Extend install services/adapters, desktop first-run models/gateways/resources,
  and exhaustive mappings.
- Prefer deriving user-visible supported lists from `InstallAgent.supportedIds`
  where package boundaries permit.
- Keep Cursor out of `RUNTIME_REFUSED_AGENTS`. Add an execution-context marker
  only if verified; otherwise rely on governed `--agent cursor`.

## Acceptance Criteria

1. All domain, port, and desktop agent/provider enums contain Cursor and their
   mappings compile without fallback branches.
2. Domain IDs, runtime supported agents, schema IDs, and desktop IDs contain the
   same ordered Cursor entry.
3. Cursor path resolves to `~/.cursor/skills`; detection is positive for a
   Cursor home, negative otherwise, and manual selection works without it.
4. Plan/apply JSON round-trips Cursor targets, MCP intent, and saved selection,
   while unknown IDs still fail loudly.
5. Desktop first-run can detect, select, plan, apply, display, and persist Cursor
   through runtime plan/apply.
6. Cursor is runtime-eligible and no unverified environment marker is added.
7. Targeted domain, schema, install-plan, persistence, CLI, and desktop tests pass.

## Non-Goals

- Native agents, MCP mutation, process launching, or documentation.
- Creating Cursor directories during read-only detection.

## Dependencies

- depends_on: `[]`
- dependency_reason: Later provider integrations require these symbols/paths.

## Validation Strategy

```bash
cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-ports:test \
  :runtime-infra-fs:test :runtime-cli:test \
  :runtime-desktop:core:domain:jvmTest :runtime-desktop:core:data:jvmTest
```

## Next Path

Run `bill-feature-task` on this spec, then continue with subtask 2.
