# SKILL-37 native-agent-codegen

Status: Complete

## Sources

- User briefing supplied to pre-planning subagent on 2026-05-07.

## Acceptance Criteria

1. Define one provider-neutral source file per native subagent.
2. Generate Codex TOML, OpenCode Markdown, and Junie Markdown from that source.
3. Make install consume generated provider-specific artifacts or ensure install regenerates current provider files before linking.
4. Keep installed/generated agent files self-contained.
5. Add validation so generated artifacts cannot drift from source.
6. Avoid dirtying the repo during normal install.

## Non-Goals

- Runtime Markdown includes/imports.
- Hand-maintaining duplicate provider agent bodies.
- Changing agent behavior beyond preserving current prompts in generated form.

## Consolidated Spec

The user wants Skill Bill native subagent definitions to be maintained from a single provider-neutral source per subagent type, with codegen generating provider-specific agent files for all supported providers. The install script should run codegen before installing/linking native agents. Provider output must remain self-contained because runtime include/reference support is not portable. The user specifically wants to avoid updating duplicate Codex/OpenCode/Junie files every time one agent body changes.

## Implementation Notes

- Provider-neutral sources live under `native-agents/<name>.md` with `name`, `description`, and one self-contained markdown body.
- Generated artifacts remain checked in under `codex-agents/`, `opencode-agents/`, and `junie-agents/`.
- `skill-bill render` refreshes generated provider artifacts from source.
- Native-agent install links generated artifacts and performs a read-only drift check instead of rewriting tracked files during normal install.
- `scripts/validate_agent_configs` rejects source/artifact drift, missing generated files, orphan generated files, and filename/name mismatches.
