# Codex native subagent support (Phase 1: foundation + pilot)

- Issue key: SKILL-33
- Status: In Progress
- Date: 2026-05-02
- Sources:
  - User request: "are we using native subagents for codex?" → "lets support proper coded subagents"
  - User decision: pilot with `bill-kmp-code-review` first, defer feature-implement and other orchestrators to follow-up issues
  - Codex docs reference: https://developers.openai.com/codex/subagents (read 2026-05-02)

## Background

Skill-bill already installs skills to the Codex user skills directory with the existing legacy Agents skills fallback, but has no integration with Codex's native subagent feature. As a result, every multi-step orchestrator that uses Claude's `Agent` tool collapses into a single Codex conversation, blowing the context window and serializing work that should run in parallel.

Codex native subagents differ from Claude's `Agent` tool in three load-bearing ways the implementation must respect:

1. **Definition format.** TOML files in the Codex user agents directory or the Codex project agents directory. Required fields: `name`, `description`, `developer_instructions`. Optional: `model`, `model_reasoning_effort`, `sandbox_mode`, `mcp_servers`, `nickname_candidates`, `skills.config`.
2. **Invocation model.** Codex spawns subagents via natural-language steering, not a programmatic tool call. There is no equivalent of `Agent(subagent_type=…)` with structured args. Parent agents say "spawn the X agent" and Codex resolves by `name`.
3. **Runtime constraints.** `agents.max_threads` defaults to 6, `agents.max_depth` defaults to 1 (no nesting). Subagents inherit the parent's sandbox policy unless overridden per-agent.

This phase establishes the foundation (install path, TOML authoring) and validates the end-to-end pattern on one pilot orchestrator (`bill-kmp-code-review`). Other orchestrators (`bill-kotlin-code-review`, `bill-feature-verify`, `bill-feature-implement`) and the scaffolder (`bill-create-skill`) are explicit follow-up issues, not part of this spec.

## Acceptance criteria

1. `skill_bill/install.py` resolves a Codex agents directory with the legacy Agents agents fallback, mirroring the existing skills-path fallback model, and exposes it through the same install/uninstall primitives that already handle skills.
2. `install.sh` and `uninstall.sh` install and remove Codex subagent TOML files alongside skill installs when Codex is a selected agent.
3. `bill-kmp-code-review` ships TOML subagent definitions for every specialist it delegates to, authored under a canonical repo location (e.g. `platform-packs/kmp/code-review/bill-kmp-code-review/codex-agents/*.toml`), and those files are valid Codex agent files (required fields present, names unique, no Claude-only references in `developer_instructions`).
4. `bill-kmp-code-review`'s orchestrator skill content describes spawning subagents in runtime-neutral language (e.g. "spawn the `review-architecture` subagent") so both Claude (which maps the spawn to `Agent(subagent_type=…)`) and Codex (which resolves the name against an installed TOML) execute the same prose correctly.
5. Each TOML's `developer_instructions` block embeds the same return contract the corresponding Claude specialist prompt enforces today — for `bill-kmp-code-review` specialists this is the F-XXX Risk Register bullet format defined in `platform-packs/kmp/code-review/bill-kmp-code-review/specialist-contract.md`. Codex agents cannot follow sibling symlinks, so the shared contract content must be inlined into each TOML rather than referenced. No new JSON envelope is introduced.
6. The pilot keeps `agents.max_threads ≤ 6` and `max_depth = 1` in mind: the orchestrator's review fan-out either fits within 6 concurrent specialists or is explicitly chunked to do so.
7. User-facing docs (README, getting-started) list Codex subagents as a supported feature, describe the install path, and explain the natural-language-spawn caveat.
8. Automated coverage is updated for the new Codex agents install path and uninstall behavior. No new tests are required for the orchestrator runtime branching beyond what already exists.
9. `agent/history.md` (and the relevant boundary history files for the install primitive and the pilot skill) are updated per `bill-boundary-history` rules.

## Non-goals

- Codex subagent support for `bill-kotlin-code-review`, `bill-feature-verify`, `bill-feature-implement`, or any other orchestrator skill — separate follow-up issues.
- Scaffolding subagent TOML files from `bill-create-skill` — separate follow-up issue.
- Changing the Claude-side `Agent` tool integration or the existing `RESULT:` JSON contracts.
- Restructuring the existing Codex skills install path.
- Adding a runtime-detection helper that tries to identify whether the orchestrator is running on Claude or Codex. The skill prose stays runtime-neutral; each runtime interprets it natively.
- Enforcing the `RESULT:` contract on Codex with deterministic parsing guarantees beyond what `developer_instructions` can elicit. Codex's natural-language spawn model means contract adherence is best-effort, not tool-enforced.

## Open questions

None at draft time. All resolved via the linked Codex docs read on 2026-05-02.

## Consolidated spec

### Install path

Extend `skill_bill/install.py` so the agent registry for Codex returns both a skills directory (existing) and an agents directory (new). The agents directory uses the same fallback pattern as skills: prefer the Codex user agents directory, then fall back to the legacy Agents agents directory. Both `install.sh` and `uninstall.sh` must be able to symlink TOML files from the repo into that directory and remove them on uninstall. The install transaction model already used for skills should be reused — do not introduce a parallel transaction system.

### Subagent definition authoring

Subagent TOML files for `bill-kmp-code-review` live under `platform-packs/kmp/code-review/bill-kmp-code-review/codex-agents/`. One file per specialist, filename matches the `name` field (e.g. `review-architecture.toml`). Each file:

- Sets `name`, `description`, and `developer_instructions`.
- Embeds the same review prompt that the corresponding Claude specialist subagent uses today, adjusted only to remove Claude-tool-specific references.
- Instructs the subagent to return `RESULT: { … }` JSON matching the existing review specialist contract.
- Does not set `model` or `sandbox_mode` unless there's a concrete reason — let them inherit.

### Orchestrator prose

`bill-kmp-code-review`'s orchestrator content (currently uses Claude `Agent` tool prose) is rewritten so spawn instructions are runtime-neutral: "spawn the `review-architecture` subagent with this briefing" rather than "call `Agent(subagent_type='review-architecture', …)`". Briefings remain self-contained per the existing reference contract.

A short note in the orchestrator content (or in a sibling reference doc) explains the runtime interpretation:

- On Claude: each spawn maps to an `Agent` tool call with the matching subagent definition.
- On Codex: each spawn is a natural-language instruction; Codex resolves the agent by `name` against the installed TOML and runs it in a separate thread.

### Parallelism and depth

The pilot orchestrator already fans out to specialists in parallel where the prose says so. With `agents.max_threads = 6` on Codex, the existing fan-out either fits within 6 specialists per wave or the orchestrator explicitly chunks the wave (e.g. baseline-Kotlin specialists in one wave, KMP-specific specialists in the next). The spec does not require changing Claude-side parallelism — only verifying the wave size on Codex.

`max_depth = 1` means specialist subagents cannot themselves spawn further subagents on Codex. The current review specialists do not nest further, so this is not a blocker.

### Documentation

Update `README.md` and `docs/getting-started.md` to:

- List the Codex user agents directory and legacy Agents agents fallback as supported install targets.
- Describe in one paragraph that Codex spawns subagents via natural language and that skill-bill's TOML defs make the orchestrators work natively.
- Clarify that this phase covers `bill-kmp-code-review` only and that other orchestrators will follow.

### Tests

- Extend the install/uninstall tests (`tests/test_install_script.py`, `tests/test_uninstall_script.py`) to cover the Codex agents directory: detection, symlinking on install, cleanup on uninstall, and the legacy fallback when the primary Codex config directory is absent.
- Validate that every TOML file in `platform-packs/kmp/code-review/bill-kmp-code-review/codex-agents/` is parseable TOML and has the required fields. A minimal repo-side validator script is acceptable; no need for a full schema-validation framework.

### Out of scope (follow-ups)

- SKILL-XX: Codex subagent support for `bill-kotlin-code-review` and `bill-feature-verify`.
- SKILL-XX: Codex subagent support for `bill-feature-implement` (the hardest, because of sequential phase dependencies and fix-on-review respawn).
- SKILL-XX: `bill-create-skill` scaffolds Codex TOML defs alongside Claude subagent prompts when a new orchestrator skill is created.
