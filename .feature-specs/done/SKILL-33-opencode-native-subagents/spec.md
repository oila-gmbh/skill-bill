# OpenCode native subagent support (Phase 1 pilot)

- Issue key: SKILL-33
- Status: Complete
- Date: 2026-05-02
- Parent: SKILL-33 (Codex native subagent support -- shipped in PR #91)
- Implementation: PR #92
- Sources:
  - `.feature-specs/SKILL-33-followups/spec_followup_1_opencode-native-subagents.md`
  - Discovery during SKILL-33 follow-up review on 2026-05-02 -- OpenCode is the second skill-bill-supported agent that has a native subagent feature with no current integration.
  - OpenCode docs: subagent definitions live in the OpenCode user agents directory (global) and the OpenCode project agents directory (per-project), as either markdown files (filename becomes agent name) or JSON entries under an `"agent"` key in `opencode.json`. Subagents can be invoked automatically by primary agents or manually by `@<name>` mention. Subagents create child sessions with their own context.

## Acceptance Criteria

1. `skill_bill/install.py` resolves the OpenCode user agents directory and exposes it through the same install/uninstall primitives that already handle skills, mirroring SKILL-33's `_codex_agents_path` shape (introduce a parallel `_opencode_agents_path` helper, `discover_opencode_agent_mds`, `install_opencode_agent_md`, `uninstall_opencode_agent_mds`).
2. `install.sh` and `uninstall.sh` install and remove OpenCode subagent markdown files alongside skill installs when OpenCode is a selected agent. Manifest-driven discovery walks `platform-packs/<slug>/**/opencode-agents/*.md` -- never hardcode a pack slug.
3. `bill-kmp-code-review` ships markdown subagent definitions for the 2 KMP-specific specialists at `platform-packs/kmp/code-review/bill-kmp-code-review/opencode-agents/*.md`. Filename matches the OpenCode agent name. The body embeds the F-XXX Risk Register bullet contract from `specialist-contract.md` inlined verbatim -- OpenCode does not follow sibling symlinks any better than Codex.
4. The orchestrator skill content needs no further changes -- `bill-kmp-code-review/content.md` was already made runtime-neutral in SKILL-33. Verify the existing "Subagent Spawn Runtime Notes" section covers OpenCode and append a one-paragraph note explaining how OpenCode resolves spawn-by-name against the OpenCode user agents directory (and that users can manually `@<name>` invoke).
5. Specialist fan-out fits within OpenCode's documented concurrency limits (research at planning time; conservative ceiling matches SKILL-33's <=6 statement). Verifiable one-line update in the orchestrator content if the limit differs from Codex.
6. User-facing docs (README, getting-started, getting-started-for-teams) cover the new OpenCode subagents install path and reference both Codex (already documented) and OpenCode in a single "native subagents" subsection.
7. Install/uninstall tests cover the new OpenCode agents directory. A markdown-validity check is added at `tests/test_opencode_agents_md.py` that walks `platform-packs/**/opencode-agents/*.md`, asserts each file has a non-empty body, the filename matches an internal `name:` frontmatter field if present, names are unique across the directory, and there are no Claude-only references in the body.
8. `agent/history.md` is updated per `bill-boundary-history` rules, citing SKILL-33 as the precedent so future runtimes following this pattern have a clear reference chain.

## Non-goals

- OpenCode subagent support for `bill-kotlin-code-review`, `bill-feature-verify`, `bill-feature-implement`, or any other orchestrator (deferred to follow-ups 2-3).
- JSON-style agent definitions in `opencode.json`. The pilot uses markdown only; if a user needs JSON they can hand-author it.
- Changing the existing OpenCode skills install path.
- Re-running SKILL-33's orchestrator-prose work -- the prose is already runtime-neutral and only needs an OpenCode-specific note appended.

## Consolidated Spec

### Background

Skill-bill installs skills to OpenCode, but has no integration with OpenCode's native subagent feature. As a result, every multi-step orchestrator that uses Claude's `Agent` tool collapses into a single OpenCode conversation -- the same context-pressure problem SKILL-33 fixed for Codex.

This phase establishes the OpenCode foundation (install path + manifest-driven discovery) and validates the end-to-end pattern on the same pilot orchestrator SKILL-33 used (`bill-kmp-code-review`). It deliberately mirrors SKILL-33's shape so the install-primitive code stays uniform across runtimes.

Key OpenCode-specific differences from Codex:

1. **Definition format.** Markdown files (recommended; filename becomes the agent name) or JSON entries under an `"agent"` key in `opencode.json`. The pilot uses markdown to match OpenCode's recommended approach and to keep the F-XXX Risk Register contract readable.
2. **Invocation model.** Primary agents invoke subagents automatically based on task requirements OR users `@<name>` mention them manually. Same runtime-neutral spawn-by-name model as Codex from the orchestrator's perspective.
3. **Session navigation.** Child sessions get keybind navigation in OpenCode's TUI (`<Leader>+Down`, etc.). Not relevant to the install primitive but worth noting in docs.

### Open questions

1. Does OpenCode's markdown agent file require frontmatter (e.g. `description:`, `model:`) or is the filename + body sufficient? Resolve at pre-planning by reading OpenCode's published agent-file schema.
2. Does OpenCode have a documented `max_threads` equivalent? If yes, capture it for AC #5; if not, default to "<=6 specialists per wave fits comfortably."

### Install primitive

Extend `skill_bill/install.py`. Reuse the existing `InstallTransaction` model and the manifest-driven discovery pattern introduced in SKILL-33. Concretely:

- Add `_opencode_agents_path(home)` returning the OpenCode user agents directory. No fallback path is required (OpenCode does not have a legacy Agents-style fallback for its own config).
- Add `discover_opencode_agent_mds(platform_packs_root)` walking `platform-packs/<slug>/**/opencode-agents/*.md`.
- Add `install_opencode_agent_md(...)` and `uninstall_opencode_agent_mds(...)` mirroring the Codex equivalents. Update `__all__`.
- Extend `detect_agents` so OpenCode appears with both its skills directory and its agents directory when present.

### Shell wiring

`install.sh` adds `install_opencode_agents_mds` (parallel to `install_codex_agents_tomls`). The conditional dispatch at the end of the AGENT_NAMES loop branches into both functions when `agent == codex` or `agent == opencode` respectively. Inline `shopt -s globstar` fallback covers the no-Python bootstrap case.

`uninstall.sh` adds `remove_opencode_agents_mds`. No fallback dirs to traverse -- OpenCode has a single canonical location.

### Subagent authoring

Each TOML authored in SKILL-33 has a markdown sibling. The body is whatever spawn-time content the specialist receives -- sourced from each specialist's `content.md`, with the F-XXX Risk Register contract from `specialist-contract.md` inlined verbatim. If OpenCode requires frontmatter, add it minimally (open question 1).

### Documentation

Replace the standalone "Codex agents" doc snippet with a "Native subagents" subsection that covers both Codex and OpenCode. Document each runtime's path, format, and invocation model. The natural-language-spawn caveat applies to both.

### Tests

- `tests/test_install_script.py`: assert the OpenCode user agents directory is created on install when opencode is selected; assert each markdown file under `platform-packs/**/opencode-agents/` ends up symlinked.
- `tests/test_uninstall_script.py`: seed markdown files in the OpenCode user agents directory and assert uninstall removes them.
- New `tests/test_opencode_agents_md.py`: parseability (it's just markdown -- assert non-empty body, no Claude-only references, name uniqueness across the directory). Mirror the structure of `tests/test_codex_agents_toml.py`.

### Out of scope (further follow-ups)

- Authoring OpenCode agents for the rest of the orchestrators -- see follow-up specs 2-3.
- Updating `bill-create-skill` to scaffold OpenCode files automatically -- see follow-up 4.
