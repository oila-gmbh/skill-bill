---
issue_key: SKILL-15
feature_name: new-skill-scaffolder
feature_size: LARGE
status: Complete
created: 2026-04-17
depends_on: SKILL-14 (code-review shell+content pilot — shipped)
---

# SKILL-15 — New-skill scaffolder + auto-installer

## Problem

Adding a new skill to skill-bill today is a multi-step manual process:

1. Create a directory under the correct tree (`skills/base/` vs. `skills/<platform>/` vs. `platform-packs/<slug>/code-review/`).
2. Author a `SKILL.md` with six required H2 sections in the right order and exact heading spelling.
3. If it's a code-review area, edit `platform-packs/<slug>/platform.yaml` to add the area to `declared_code_review_areas` and the file path to `declared_files.areas`.
4. Create sibling-sidecar symlinks (`stack-routing.md`, `review-orchestrator.md`, `review-delegation.md`, `telemetry-contract.md`, `specialist-contract.md`, and — for the code-review shell — `shell-content-contract.md`) pointing at the canonical orchestration playbooks.
5. Update the README catalog count and the relevant section table.
6. Run the validator. If something drifted, fix it manually and re-run.
7. Run `./install.sh` and re-select agents + platform packs.

Every step is a chance to get something wrong. The validator catches most drift, but the error surfaces late and the fix is manual. First-time authors (forks, teams adopting the service) hit every one of these failure modes.

After SKILL-14, the shell+content contract makes the *shape* rules explicit and machine-readable. This ticket turns that shape into a one-shot authoring flow: free-form paste → LLM structuring → deterministic file scaffolder → automatic symlink install to detected agents.

## Why now

SKILL-14 landed the shell+content contract with named exceptions and a loud-fail loader. Authoring a new skill now has one source of truth for what "valid" means. The scaffolder is the natural next step because:

- The contract is strict enough that a deterministic scaffolder can satisfy it mechanically.
- Every future pilot (SKILL-16 for `bill-quality-check`, etc.) will need the same authoring primitives. Building the scaffolder first means those pilots become mostly-mechanical relocations of existing content.
- Publicly shipping skill-bill as a *service* (the target state described in SKILL-14 work) requires first-time authors to succeed. A 7-step manual flow is a rejection signal.

## Context (what a new-session implementer needs to know)

### What skill-bill is today

- **Governed shell + content architecture.** `skills/base/bill-code-review/SKILL.md` is a platform-independent shell. Platform-specific review content lives under `platform-packs/<slug>/` (six shipped packs: `kotlin`, `kmp`, `backend-kotlin`, `php`, `go`, `agent-config`).
- **Versioned shell+content contract.** `orchestration/shell-content-contract/PLAYBOOK.md` defines the manifest schema, required content files, required H2 sections, loud-fail rules, and discovery semantics. Current `SHELL_CONTRACT_VERSION` is `"1.0"`.
- **Runtime loader.** `skill_bill/shell_content_contract.py` is the authority on contract validation. Named exceptions: `MissingManifestError`, `InvalidManifestSchemaError`, `ContractVersionMismatchError`, `MissingContentFileError`, `MissingRequiredSectionError`, `PyYAMLMissingError`. No silent fallback.
- **Manifest-driven discovery.** `orchestration/stack-routing/PLAYBOOK.md` and `scripts/validate_agent_configs.py` both walk `platform-packs/*/platform.yaml` at runtime. No enumerated platform names survive in orchestration playbooks or validator constants.
- **Horizontal skills.** `skills/base/` holds 12 base skills (the shell + 11 horizontals including `bill-feature-implement`, `bill-quality-check`, `bill-pr-description`, etc.). Never touched by platform routing.
- **Pre-shell leftovers.** `skills/<platform>/bill-<platform>-quality-check/` and `skills/kmp/addons/` still live under `skills/` because AC 13 of SKILL-14 explicitly scoped only `bill-code-review` into the pilot. Future tickets will migrate `bill-quality-check` et al. onto the contract.
- **Install model.** `install.sh` presents selectable agents (Claude Code, Copilot, Codex, OpenCode, GLM) and selectable platform packs, then creates symlinks from each detected agent command root to repo files. The concrete install roots live in the installer code and are resolved per agent at runtime.

### Contract bands (locked in during spec discussion)

The six required H2 sections split conceptually into two bands:

| Band | Sections | Authority |
|---|---|---|
| **Author-owned** | `## Description`, `## Specialist Scope`, `## Inputs`, `## Outputs Contract` | Skill writer |
| **Scaffolder-owned** | `## Execution Mode Reporting`, `## Telemetry Ceremony Hooks` | Template in scaffolder |

The runtime loader still requires all six — this is an authoring distinction, not a contract change. No `SHELL_CONTRACT_VERSION` bump. The scaffolder owns exactly the two boilerplate sections; everything else the user authors (via the LLM-guided path).

### Three-layer design

The scaffolder is a three-layer sandwich:

1. **Layer 1: Script (`skill_bill/scaffold.py`)** — pure Python. Takes a structured JSON payload. Creates files, wires sidecars, updates manifests, runs validator, installs to detected agents, rolls back on any failure. Deterministic. No LLM. No prompts.
2. **Layer 2: CLI (`skill-bill new-skill`)** — thin wrapper. Supports `--payload <path-to-json>` for scripted use (CI, power users, non-Claude-Code runtimes) and `--interactive` for a 4-prompt fallback when no LLM is available.
3. **Layer 3: Skill (`bill-new-skill-all-agents`)** — primary UX. LLM walks the decision tree, accepts free-form paste, structures the paste into the four author-owned sections, shows a preview with synthesized sections marked, supports `yes / edit <section> / redo`, then invokes Layer 1 with a structured payload.

The script is the authority on shape. The LLM is the author's assistant.

### Decision tree the skill walks

```
Q1. What kind of skill?
    a) standalone / horizontal                 → skills/base/<name>/
    b) platform override of an existing base   → see Q2a
    c) code-review specialist area             → see Q2b
    d) governed add-on                         → skills/<platform>/addons/<name>.md

Q2a. (for b) Which base skill does it override?
     Look up pilot status from a registry in scaffold.py.
     - piloted family (bill-code-review) → platform-packs/<slug>/<family>/
     - pre-shell family (bill-quality-check etc.) → skills/<platform>/bill-<platform>-<capability>/

Q2b. (for c) Which platform? (discovered from platform-packs/*/ ∪ skills/*/)
     + "create new platform" option → see Q3.

Q2c. (for c) Which area? (approved set minus already-declared in the chosen pack)

Q3. (for "create new platform") Collect:
     - slug (kebab-case, validated)
     - display_name
     - contract_version (defaulted to current SHELL_CONTRACT_VERSION)
     - routing_signals.strong (list)
     - routing_signals.tie_breakers (list, optional)
     - routing_signals.addon_signals (list, optional)
     - governs_addons (bool)
     - declared_code_review_areas (optional list from approved set)

Q4. Paste draft (free-form, terminator = line containing only `.`).

Q5. Preview: LLM shows structured result with synthesized sections marked.
    User responds: yes | edit <section> | redo.
```

## Acceptance criteria

1. **Scaffolder script exists.** `skill_bill/scaffold.py` provides a `scaffold(payload: dict) -> ScaffoldResult` function. Pure Python, deterministic (same input → same on-disk result), no LLM dependencies.

2. **Payload schema is versioned.** The payload has a `scaffold_payload_version: "1.0"` field. A mismatch raises a named exception analogous to `ContractVersionMismatchError`. The schema is documented in `orchestration/shell-content-contract/PLAYBOOK.md` (or a new sibling playbook if the contract playbook grows unwieldy).

3. **Four skill kinds supported**, each with correct file layout:
   - **standalone/horizontal** → `skills/base/<skill-name>/SKILL.md`
   - **platform override (piloted family)** → `platform-packs/<slug>/<family>/<skill-name>/SKILL.md` with manifest update
   - **code-review specialist area** → `platform-packs/<slug>/code-review/<skill-name>/SKILL.md` with manifest update
   - **governed add-on** → `skills/<platform>/addons/<name>.md` (flat file, no directory)

4. **Pre-shell family detection.** The scaffolder reads a registry inside `skill_bill/scaffold.py` that lists which base skills have been piloted onto the shell+content contract. When a user authors an override for a pre-shell family, the scaffolder lands files at `skills/<platform>/bill-<platform>-<capability>/` and prints a clear note explaining the interim location. Registry is easy to update when a new pilot lands.

5. **Manifest updates are atomic.** When scaffolding a code-review area, the scaffolder updates `platform-packs/<slug>/platform.yaml` to add the area to `declared_code_review_areas` and its path to `declared_files.areas`. YAML write preserves existing key order, comments (best-effort), and formatting. If the write fails, the file is restored from an in-memory snapshot taken before the edit.

6. **Sidecar symlinks wired correctly.** For skills that require sidecars (all code-review-family skills today), the scaffolder creates the right set of symlinks to the canonical orchestration playbooks. The set is driven by `scripts/skill_repo_contracts.py::RUNTIME_SUPPORTING_FILES`.

7. **Scaffolder-owned sections are emitted from template.** The scaffolder writes `## Execution Mode Reporting` and `## Telemetry Ceremony Hooks` from a stored template in `skill_bill/scaffold.py` (or a fixture file under the scaffolder's package). The template is identical across specialists in a family. If the template ever changes, existing skills can be regenerated via a separate `skill-bill scaffold rewrite-boilerplate` subcommand (out of scope for this PR — just ensure the template is extractable for future use).

8. **Validator runs after scaffolding.** The scaffolder invokes `scripts/validate_agent_configs.py` (or calls the underlying validation functions directly). If validation fails, the scaffolder rolls back every file and symlink it created and returns the validation error verbatim.

9. **Auto-install to detected agents.** After validation passes, the scaffolder creates symlinks from detected agent command roots to the new skill files. Agent detection reads the known install-root registry for Claude Code, Copilot, Codex, OpenCode, and GLM, then checks for existing skill-bill symlinks. If no agents are detected, the scaffolder skips installation and prints a note directing the user to `./install.sh`.

10. **Install primitive is reusable.** The per-skill install logic is extracted either into `skill_bill/install.py` (Python module) or exposed via an `install.sh --add <path>` non-interactive subcommand. Both `install.sh` and the scaffolder call into the same primitive so they stay in sync. Pick one approach in planning; justify the choice.

11. **Rollback is atomic.** If any step fails (validator, symlink creation, manifest write), the scaffolder unwinds every change it made: delete created files, restore manifest to pre-edit state, remove any symlinks it created. No half-scaffolded state on disk.

12. **CLI entry point.** `skill-bill new-skill` supports at least these modes:
    - `--payload <path.json>` — non-interactive, load JSON and run.
    - `--interactive` — 4-prompt fallback (ask each of the 4 author-owned sections separately); no LLM involvement. Mostly for CI and runtimes without LLM access.
    - `--dry-run` — load payload, validate schema, print planned actions, do not write.

13. **Skill rewrite (`bill-new-skill-all-agents`).** The existing skill is rewritten to implement the LLM-guided flow:
    - Walks the decision tree with numbered options.
    - Accepts a free-form paste.
    - Structures the paste into the four author-owned sections.
    - Shows the structured preview with synthesized sections explicitly marked (e.g., `[synthesized from shell contract — override with edit <section>]`).
    - Supports `yes` / `edit <section> [with <new content>]` / `redo`.
    - On confirm, emits a JSON payload and invokes `skill-bill new-skill --payload`.
    - Reports script output verbatim on success or failure.

14. **Identical synthesized defaults.** When the LLM fills in `Inputs` or `Outputs Contract` from shell-contract defaults, the text is identical across specialists in a family. If tailored per-specialist defaults become necessary, they become a third contract band (template per specialist area), not an LLM invention. For this PR: one default text per contract slot, stored in the scaffolder's template.

15. **Migration awareness.** Scaffolder never creates a skill in a location that will later need relocation when a pre-shell family is piloted. When a user asks for an override of a pre-shell family, the scaffolder:
    - Places files at the pre-shell location (`skills/<platform>/...`).
    - Records the skill in a local migration manifest (optional — may defer to SKILL-16+).
    - Prints a clear note that the skill will move when the family's pilot lands.

16. **Tests cover all four kinds + rollback paths.** Unit tests in `tests/test_scaffold.py`:
    - Happy path for each of the four skill kinds.
    - Rejection: invalid payload → named exception with offending field.
    - Rejection: contract version mismatch.
    - Rollback: validator fails → no files remain on disk.
    - Rollback: manifest write fails → manifest restored.
    - Rollback: symlink creation fails → files + manifest + prior symlinks all reverted.
    - Idempotency: running scaffolder twice with identical payload produces the same on-disk result the second time (or fails loudly with "skill already exists").
    - Agent detection: fixture with no agents, fixture with one agent, fixture with all five.

17. **Docs updated.** `README.md` adds a brief "Adding skills" section pointing at the new workflow. `docs/getting-started-for-teams.md` updates the adding-a-new-platform-pack walkthrough to use the scaffolder. `AGENTS.md` adds a "New-skill authoring" subsection noting the scaffolder is the canonical path.

18. **No new base skills, no new platforms, no content changes to existing skills.** This PR builds infrastructure. It does not add a Rust pack, does not pilot `bill-quality-check`, does not change any existing SKILL.md content.

19. **Existing validation suite still passes.** `.venv/bin/python3 -m unittest discover -s tests` (217 tests) continues to pass. `npx --yes agnix --strict .` passes. `.venv/bin/python3 scripts/validate_agent_configs.py` passes.

## Non-goals

- Piloting `bill-quality-check`, `bill-feature-implement`, or `bill-feature-verify` onto the shell+content contract. That is SKILL-16 and follow-ups.
- Relocating example packs out of this repo to `skill-bill-examples`. That is a separate ticket.
- Changing the shell+content contract itself (no `SHELL_CONTRACT_VERSION` bump).
- Changing the approved `code-review` areas set.
- Adding new skill kinds beyond the four listed above.
- Building telemetry for the scaffolder (consider adding it, but treat it as optional in this PR).
- Changing MCP server, CLI telemetry commands, learnings pipeline, or any unrelated runtime behavior.
- Building a `scaffold rewrite-boilerplate` subcommand for regenerating scaffolder-owned sections across existing skills. Flagged as a future option in AC 7; out of scope for this PR.
- Supporting multi-skill batch authoring in the CLI (a single CLI invocation only creates one skill). The skill layer may orchestrate multiple invocations if needed.
- Adding a `--no-install` flag. Discussed and rejected: agent detection already covers the no-agents case; power users can unlink manually.

## Open questions to resolve in planning

1. **Install primitive location.** Python module (`skill_bill/install.py`) or shell subcommand (`install.sh --add`)? Python is testable and cleaner, but larger refactor. Shell is smaller diff, keeps install.sh as the authority. Planning must pick one and justify.
2. **Telemetry for the scaffolder.** Should `skill-bill new-skill` emit `new_skill_scaffold_started` / `_finished` MCP events? Useful for measuring adoption of the scaffolder and catching failed scaffold attempts, but adds MCP tool surface. Default position: add it; it's one event pair and matches the telemetry-contract pattern.
3. **Skill → script invocation mechanism.** When the skill invokes the scaffolder, does it run `skill-bill new-skill --payload <tempfile.json>` as a subprocess, or is there a cleaner Claude-Code-native way (write payload to file, print next-step instructions)? Subprocess is simplest; confirm it works from within the skill's runtime.
4. **Edit mode semantics.** When the user says `edit Specialist Scope`, does the LLM re-prompt for the new content, or does the user paste the replacement immediately? Both are reasonable; pick one for consistency. Recommend: user dictates the edit naturally (`edit Specialist Scope to add expect/actual`), LLM re-renders, shows preview.
5. **`bill-new-skill-all-agents` SKILL.md structure.** The existing skill has a different shape than what this rewrite implies. Planning should decide whether to replace its SKILL.md wholesale or layer the new flow on top. Recommend: wholesale replace; the existing skill's behavior is superseded.
6. **Idempotency details.** What does "skill already exists" mean? If the user scaffolds `bill-kmp-code-review-architecture` twice, does the second attempt fail loudly, overwrite the first, or prompt? Recommend: fail loudly with a specific error; overwriting is risky and the scaffolder should not pretend to be a bulk-editing tool.

## Scenario mock-ups (for context)

### Scenario A — add an area to an existing platform pack (happy path)

```
User: /bill-new-skill-all-agents

Claude: What kind of skill?
  1. standalone / horizontal
  2. platform override of an existing base skill
  3. code-review specialist area
  4. governed add-on
User: 3

Claude: Which platform? (discovered)
  1. agent-config  2. backend-kotlin  3. go
  4. kmp           5. kotlin          6. php
  7. create new platform
User: 4

Claude: Which area? (already-declared: ui, ux-accessibility)
  1. api-contracts   5. platform-correctness
  2. architecture    6. reliability
  3. performance     7. security
  4. persistence     8. testing
User: 2

Claude: Target:
  - platform-packs/kmp/code-review/bill-kmp-code-review-architecture/SKILL.md
  - manifest update: platform-packs/kmp/platform.yaml

  Paste your draft — free-form. I'll organize it into the 4 required sections
  and show you the result. Terminator: line containing only `.`

User:
  Reviews architectural boundaries in KMP. Focus on commonMain vs platform-
  specific source sets, expect/actual symmetry, and DI scope leaks. Not
  performance or UI — those have their own specialists.
  .

Claude: Here's the structured draft. Synthesized sections marked.

  ## Description
  Reviews architectural boundaries in KMP source sets — specifically the
  boundary between commonMain and platform-specific code, expect/actual
  symmetry, and DI scope hygiene across source sets.

  ## Specialist Scope
  - In: commonMain ↔ platform-specific boundary violations
  - In: expect/actual declaration symmetry
  - In: DI scope leaks between shared and platform code
  - Out: performance (bill-kmp-code-review-performance)
  - Out: UI composition (bill-kmp-code-review-ui)

  ## Inputs                    [synthesized from shell contract — ✎ to edit]
  - Review scope (files, commit range, staged diff, PR)
  - Dominant stack signals from shell
  - Applied learnings

  ## Outputs Contract          [synthesized from shell contract — ✎ to edit]
  - Findings in the shell's Risk Register format
  - Severity calibrated per architectural impact
  - Verdict contribution for the shell to merge

  (Execution Mode Reporting + Telemetry Ceremony Hooks appended from template.)

  Proceed, edit a section, or redo?
User: yes

Claude → skill-bill new-skill --payload <tempfile>

✅ Scaffolded platform-packs/kmp/code-review/bill-kmp-code-review-architecture/SKILL.md
✅ Updated platform-packs/kmp/platform.yaml
✅ Wired 5 sidecar symlinks
✅ Validator passed (48 → 49 skills)
✅ 217 tests pass
✅ Installed to detected agents: claude-code, copilot
```

### Scenario B — create new platform (Rust)

Same decision tree until Q2b's "create new platform" branch:

```
Claude: New platform slug (lowercase kebab-case): rust
Claude: Display name: Rust
Claude: Strong routing signals — one per line, terminator `.`:
User: Cargo.toml
      *.rs
      fn main
      .
Claude: Tie-breakers (optional): If Cargo.toml is present, prefer Rust.
        .
Claude: Governs any add-ons? [y/N]: n
Claude: Declare specialist areas now? [y/N]: y
Claude: Which areas? 3, 7, 8
        (performance, security, testing)

Claude: I'll scaffold:
  - platform-packs/rust/platform.yaml
  - platform-packs/rust/code-review/bill-rust-code-review/SKILL.md   (baseline)
  - platform-packs/rust/code-review/bill-rust-code-review-performance/SKILL.md
  - platform-packs/rust/code-review/bill-rust-code-review-security/SKILL.md
  - platform-packs/rust/code-review/bill-rust-code-review-testing/SKILL.md

  Going file by file. Paste the baseline draft first.
...
```

### Scenario C — horizontal base skill (simplest)

```
User: /bill-new-skill-all-agents
Claude: What kind?
User: 1
Claude: Skill name (kebab-case, must start with bill-): bill-adr-writer
Claude: Description for frontmatter (routing hint shown to LLM):
User: Use when capturing architecture decision records. Use when user mentions
      ADR, write ADR, capture decision.
Claude: Paste your draft — free-form.
User: [one paragraph]
      .
Claude: [preview]
User: yes
Claude: Scaffolded. Installed. 48 → 49 skills.
```

## Files expected to change

Created:
- `skill_bill/scaffold.py` — scaffolder core (Layer 1).
- `skill_bill/install.py` — install primitive (if we pick the Python-helper option; else updates to `install.sh`).
- `tests/test_scaffold.py` — scaffolder unit + integration tests.
- `tests/fixtures/scaffold/` — test fixtures for valid + invalid payloads.
- possibly `orchestration/shell-content-contract/PLAYBOOK.md` additions (payload schema doc) OR a sibling playbook for the scaffolder's payload schema.

Modified:
- `skill_bill/cli.py` — add `new-skill` subcommand (Layer 2).
- `skill_bill/__init__.py` — export scaffold primitives if needed.
- `skill_bill/constants.py` — add scaffold_payload_version, agent-path constants if not already present.
- `skills/base/bill-new-skill-all-agents/SKILL.md` — Layer 3 rewrite.
- `install.sh` — extract per-skill install primitive (if we pick the shell-subcommand option) or consume it from the Python helper.
- `README.md` — catalog count unchanged (scaffolder is not a new skill; it replaces behavior of an existing one), but add "Adding skills" section.
- `docs/getting-started-for-teams.md` — update "Adding a new platform pack" to use scaffolder.
- `AGENTS.md` — small "New-skill authoring" note.
- `pyproject.toml` — if any new dependencies needed (unlikely; stdlib + existing PyYAML should suffice).
- `scripts/validate_agent_configs.py` — only if the scaffolder needs new validator hooks (unlikely; existing loader should cover it).

Not modified:
- `orchestration/stack-routing/PLAYBOOK.md` — discovery-driven, no changes needed.
- Any existing platform pack content.
- Any horizontal skill.
- `skill_bill/shell_content_contract.py` — scaffolder consumes it, does not change it.

## Feature flag

N/A. This is tooling, not user-facing runtime behavior.

## Backup / destructive operations

N/A. This PR is additive. No destructive git operations expected beyond normal branch work.

## Validation strategy

`bill-quality-check` (auto-routes to `bill-agent-config-quality-check` for this repo). The three canonical commands must pass:

```
.venv/bin/python3 -m unittest discover -s tests
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```

## References

- SKILL-14 spec: `.feature-specs/SKILL-14-code-review-shell-pilot/spec.md` — architectural parent. The scaffolder's contract validation reuses the loader and named exceptions from SKILL-14.
- Shell+content contract: `orchestration/shell-content-contract/PLAYBOOK.md`.
- Contract loader: `skill_bill/shell_content_contract.py`.
- Existing installer: `install.sh`, `uninstall.sh`.
- Existing skill to rewrite: `skills/base/bill-new-skill-all-agents/SKILL.md`.
- Roadmap context: `docs/ROADMAP.md` — this ticket advances the "make authoring frictionless" theme under the service-pivot direction.
