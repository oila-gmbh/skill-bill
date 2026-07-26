# SKILL-45 Subtask 5: Final Integration, Docs, and Validation

Parent spec: [spec.md](./spec.md)

Status: Complete

## Scope

Finish cross-boundary integration after the shared backend, CLI/install migration, desktop wizard, and packaging subtasks land. This subtask should close acceptance gaps, update user/developer documentation, record high-value boundary history, and run the full validation gate.

It should focus on integration defects and missing coverage rather than reopening architecture from earlier subtasks.

Likely files/boundaries:

- `README.md`
- `docs/**`
- `install.sh`
- `runtime-kotlin/runtime-core/**`
- `runtime-kotlin/runtime-cli/**`
- `runtime-kotlin/runtime-desktop/**`
- `runtime-kotlin/build-logic/**`
- Relevant `agent/history.md` or boundary history files if present for touched areas
- Test files needed to close acceptance gaps across install plan/apply, CLI, desktop state, and packaging lookup

## Acceptance Criteria

1. All parent feature acceptance criteria are traceably satisfied or have an explicit documented host/CI limitation for native package production.
2. Documentation explains how to build/install the desktop app on macOS, Windows, and Linux, including the Linux Arch-friendly artifact or fallback.
3. Documentation explains first-run wizard choices for agents, platform packs, telemetry, and MCP registration without changing governed source-shape rules.
4. `install.sh` usage remains documented and consistent with the reusable runtime install/apply path.
5. Windows symlink/elevation behavior is documented with clear fallback or failure guidance.
6. Tests cover install plan/apply, agent/platform selection, telemetry configuration, desktop wizard state, and packaged runtime lookup.
7. Boundary history is updated for high-value install/desktop/runtime packaging decisions where the repository has an established history file.
8. Full validation has been run or any blocked command is reported with the exact blocker.

## Non-Goals

- Do not introduce new feature scope beyond closing the parent acceptance criteria.
- Do not redesign skill authoring or platform-pack manifest contracts.
- Do not add a remote marketplace.
- Do not commit generated governed artifacts or packaged binary outputs.
- Do not change the supported-agent set unless a contract gap was found and already handled in an earlier subtask.

## Dependencies

Depends on Subtasks 1 through 4. This is the final verification and integration pass and should not run first.

## Validation Strategy

Run the full repository gate:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Use `bill-quality-check` as the primary quality-check entrypoint when fixing failures.

## Handoff Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_5_final-integration-docs-validation.md`.
