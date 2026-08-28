# SKILL-218 · Subtask 1 — Cursor delegated parent fan-out

## Scope

Make the runtime-launched Cursor delegated parent name and see its selected
specialists.

In scope:

- Cursor-only delegated parent prompt fragment: one `/<logicalWorkerName>`
  per selected lane, all named in a single instruction, plus the existing
  routed rubric and assigned-bundle bodies.
- Copy or link each selected specialist's installed Cursor agent file into
  `{reviewLaunchDirectory}/.cursor/agents/` before `agent --print` starts.
  `reviewLaunchDirectory` is the evidence-endpoint directory already used as
  `--workspace`.
- Keep Claude's `reviewFanOut` `Agent`/`Task` tools and Claude/Codex parent
  prompt text free of Cursor `/name` syntax.
- Update `../../../orchestration/review-delegation/PLAYBOOK.md` Cursor section so the
  CLI parent path matches this launch (named `/name` plus project agents in
  the isolated workspace). Do not claim a live CLI canary.
- Tests: Cursor delegated prompt contains `/` plus each logical worker name;
  those files exist under the launch `.cursor/agents/`; Claude delegated
  command still lists `Agent` and `Task`; inline Cursor does not copy unused
  specialists or emit specialist `/name` lines.

## Acceptance Criteria

1. A Cursor delegated parent prompt contains one `/<logicalWorkerName>` line
   per selected specialist, in a single instruction that names every selected
   lane.
2. Before that parent starts, each selected specialist's installed Cursor
   agent file is present at
   `{reviewLaunchDirectory}/.cursor/agents/<logicalWorkerName>.md`.
3. A Claude delegated parent prompt does not gain `/name` invocation lines,
   and its command still enables `Agent` and `Task` when `reviewFanOut` is
   true.
4. A Codex delegated parent launch is unchanged by this skill.
5. Cursor inline still launches one `bill-code-review-inline` parent with no
   specialist `/name` fan-out and without copying specialist agent files for
   unused lanes.
6. Missing, dangling, or undeclared selected specialists still fail
   `MissingInstalledNativeAgentError` before the parent starts. The run does
   not resolve to inline.

## Non-Goals

- Sibling AgentRun processes per specialist.
- A live Cursor CLI canary in CI.
- Changing Claude, Codex, or Junie builders.
- Changing native-agent frontmatter rendering.

## Dependency Notes

Depends on SKILL-182 (Cursor playbook section and Cursor-native frontmatter)
and SKILL-159 (no external delegated lifecycle). Both are on `main`.

## Validation Strategy

- `./gradlew compileKotlin` from `runtime-kotlin` for buildability, then the
  pack collect-all gate for the full check.
- Runner test: Cursor delegated parent prompt names each selected specialist
  with `/<logicalWorkerName>`; the bound endpoint directory contains those
  agent files under `.cursor/agents/`.
- Command-builder test: Claude delegated `reviewFanOut` still lists `Agent`
  and `Task`; Cursor builder still has no `--agent` flag.
- Inline Cursor test: no specialist `/name` lines and no unused-lane agent
  copies.
- Preflight still throws `MissingInstalledNativeAgentError` when a selected
  native agent is missing.

## Next Path

Feature complete. If a live `agent --print` parent still cannot spawn the
named lanes, open a follow-up for sibling AgentRun fan-out rather than
widening this subtask.
