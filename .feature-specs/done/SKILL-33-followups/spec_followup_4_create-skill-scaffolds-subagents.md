# bill-create-skill emits subagent defs for Codex and OpenCode

- Issue key: SKILL-33
- Status: Complete
- Date: 2026-05-02
- Parent: SKILL-33 (Codex install primitive) and follow-ups 1–3
- Implementation: PR #96
- Sources:
  - SKILL-33 spec, non-goals: "Scaffolding subagent TOML files from `bill-create-skill` — separate follow-up issue."
  - The `bill-create-skill` skill scaffolds new orchestrator skills today. After this issue, it also emits the matching Codex TOML and OpenCode markdown subagent definitions so authors don't have to add them by hand.

## Background

Once Codex and OpenCode subagents exist as first-class artifacts in skill-bill (via SKILL-33 and follow-ups 1–3), the natural next step is for `bill-create-skill` to emit them automatically when scaffolding a new orchestrator skill. Today the scaffolder produces `SKILL.md`, `content.md`, and supporting files; it does not produce subagent definitions.

This issue updates the scaffolder so a single `bill-create-skill` run produces a skill that ships natively on Claude, Codex, and OpenCode out of the box.

This issue depends on SKILL-33 + follow-ups 1–3 because the scaffolder must know:

1. The repo conventions for where TOML and markdown subagent files live (`<skill-dir>/codex-agents/`, `<skill-dir>/opencode-agents/`).
2. The required field set for each format (Codex TOML: `name`, `description`, `developer_instructions`; OpenCode markdown: depends on follow-up 1's open-question resolution).
3. The runtime-neutral orchestrator-prose conventions established in SKILL-33's "Subagent Spawn Runtime Notes" section.

## Acceptance criteria

1. `bill-create-skill`, when scaffolding a new orchestrator skill that delegates to specialist subagents, prompts the author for the specialist names (or accepts them as an argument) and emits one Codex TOML stub and one OpenCode markdown stub per specialist.
2. Each emitted Codex TOML stub contains the required fields (`name`, `description` placeholder, `developer_instructions` placeholder) and includes a commented pointer to the specialist-contract pattern (where to inline the F-XXX Risk Register bullet contract).
3. Each emitted OpenCode markdown stub contains the conventions resolved by follow-up 1 (frontmatter if required, body placeholder, F-XXX contract pointer).
4. The scaffolder updates the orchestrator's `content.md` to include the runtime-neutral "Subagent Spawn Runtime Notes" section verbatim from SKILL-33's pattern, with the orchestrator's specialist names substituted in.
5. The scaffolder verifies that the emitted files satisfy the manifest-driven discovery (i.e. they land under `<skill-dir>/codex-agents/*.toml` and `<skill-dir>/opencode-agents/*.md` so the install primitive picks them up automatically).
6. Scaffolder tests are extended (`tests/test_scaffold.py`) to assert that a scaffolded orchestrator skill emits the expected subagent files for both runtimes, with valid contents that pass the existing `tests/test_codex_agents_toml.py` and `tests/test_opencode_agents_md.py` checks.
7. User-facing scaffolder docs are updated to describe the new prompts/arguments and the expected output structure.
8. `agent/history.md` is updated per `bill-boundary-history` rules.

## Non-goals

- Generating subagent definitions for SPECIALIST skills (the leaves) — only the orchestrators that delegate to them. Specialist skills already have their own `SKILL.md` + `content.md`; the scaffolder copies their content into the parent orchestrator's TOML/markdown when needed.
- Backfilling subagent definitions for orchestrators that already exist (the existing orchestrators get their subagent definitions via SKILL-33 + follow-ups 1–3, not via re-running the scaffolder).
- Inferring specialist names automatically from the orchestrator's `content.md`. The author supplies them; the scaffolder does not parse prose to discover delegation.

## Open questions

1. Should the scaffolder emit Codex + OpenCode files unconditionally, or only when the user opts in? Default proposal: unconditional, since shipping subagent definitions is now the expected baseline for orchestrator skills. Make it suppressible with a `--no-subagents` flag for cases where the author wants to author them by hand.
2. Where in the scaffolder's interactive flow should specialist-name collection live? Proposal: a single comma-separated prompt after the skill description, with a sane default of "(none — this is a leaf skill, no subagents emitted)" when the author confirms the skill does not delegate.

## Consolidated spec

### Scaffolder changes

Read the current scaffolder implementation (likely in `skill_bill/` or `runtime-kotlin/`) at pre-planning time. The change-set is:

- Add a specialist-collection step (interactive prompt or CLI argument).
- For each specialist, emit a Codex TOML stub at `<skill-dir>/codex-agents/<specialist-name>.toml` with required fields filled in as placeholders.
- For each specialist, emit an OpenCode markdown stub at `<skill-dir>/opencode-agents/<specialist-name>.md` with placeholders.
- Inject the runtime-neutral "Subagent Spawn Runtime Notes" section into the new orchestrator's `content.md` with the specialist names listed.

### Stub contents

Stubs must be valid enough to pass the existing TOML/markdown validity tests (parseable, required fields present, name = filename) but ship with placeholder bodies that clearly indicate "fill this in." Authors must NOT be expected to ship a scaffolded orchestrator without filling the stubs in; the scaffolder prints a clear next-step message after generation.

### Tests

Extend `tests/test_scaffold.py` to:

- Run the scaffolder against a synthetic orchestrator with two specialists.
- Assert that the expected files are created at the expected paths.
- Assert that each emitted file passes the existing validity tests (`tests/test_codex_agents_toml.py` and `tests/test_opencode_agents_md.py` rules — invoke the same assertion logic, do not duplicate it).

### Documentation

Update `bill-create-skill`'s `content.md` (or `SKILL.md` if that's the authored surface) to describe the new prompts, the new files emitted, and the expected next steps (filling in the stubs).

### Boundary history

Append a single entry citing SKILL-33 + follow-ups 1–3 as the install-primitive and authoring-convention precedents that this scaffolder change builds on.
