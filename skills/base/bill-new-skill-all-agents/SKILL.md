---
name: bill-new-skill-all-agents
description: Use when creating a new skill and syncing it to all detected local AI agents (Claude, Copilot, GLM, Codex, Opencode). Use when user mentions create skill, new skill, add skill, or sync skill to agents.
---

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-new-skill-all-agents` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Overview

This skill is the user-facing wrapper around `skill-bill new-skill`. You collect intent from the user, preview the scaffolded output with synthesized markers, iterate on edits until the user approves, then call into the Python scaffolder to actually create files, edit manifests, wire sidecar symlinks, run the validator, and install into detected agents.

All filesystem work happens in `skill_bill/scaffold.py`; this skill never edits `SKILL.md`, `platform.yaml`, or symlinks directly. If an agent path changes, the source of truth is `skill_bill/install.py` — not this skill.

## Decision Tree

Ask the user enough to pick exactly one of the four kinds:

1. **horizontal** — capability works across stacks (e.g. `bill-pr-description`).
   - Destination: `skills/base/<name>/SKILL.md`.
2. **platform-override-piloted** — a platform-specific variant of an existing base family.
   - Shelled family (`code-review`) → destination: `platform-packs/<slug>/code-review/<name>/SKILL.md`.
   - Pre-shell family (`quality-check`, `feature-implement`, `feature-verify`) → destination: `skills/<platform>/<name>/SKILL.md`; the scaffolder emits an interim-location note saying "will move when piloted."
3. **code-review-area** — a specialist for one approved code-review area inside an existing platform pack.
   - Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.
   - Destination: `platform-packs/<slug>/code-review/<name>/SKILL.md` + manifest edits.
4. **add-on** — a stack-owned supporting asset, not a standalone skill.
   - Destination: `skills/<platform>/addons/<name>.md` (flat, no sub-directory).

Refuse to invent a new family, area, or platform inline. If the user asks for something not in the lists above, ask them to first update `skill_bill/constants.py::PRE_SHELL_FAMILIES`, the approved-area list, or the platform-pack roster in the same change they submit the skill in.

## Workflow

1. **Paste step.** Ask the user to paste:
   - skill name (validate the `bill-...` prefix)
   - one-line description
   - kind (from the decision tree)
   - platform / family / area as required by the kind

2. **Preview with synthesized markers.** Before calling the scaffolder, render a preview that shows the final destination path and the six required H2 headings with synthesized defaults:
   - `## Description`
   - `## Specialist Scope`
   - `## Inputs`
   - `## Outputs Contract`
   - `## Execution Mode Reporting` *(scaffolder-owned, byte-identical across specialists in a family)*
   - `## Telemetry Ceremony Hooks` *(scaffolder-owned, byte-identical across specialists in a family)*

   The scaffolder-owned sections always render identically across every specialist in the same family — do not invite edits on those.

3. **Iterate.** Offer three choices:
   - `yes` — accept the preview and proceed to scaffold.
   - `edit <section>` — take a natural-language edit for one authored section. Re-render the preview.
   - `redo` — restart the paste step from scratch.

4. **Emit JSON payload.** When the user accepts, build a payload shaped like:

   ```json
   {
     "scaffold_payload_version": "1.0",
     "kind": "...",
     "name": "bill-...",
     "platform": "...",
     "family": "...",
     "area": "...",
     "description": "..."
   }
   ```

   The full schema (required keys, worked examples per kind, the loud-fail exception catalog) lives in the repo at `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.

5. **Subprocess into the scaffolder.** Write the payload to a tempfile, then invoke:

   ```bash
   skill-bill new-skill --payload <tempfile>
   ```

   The scaffolder handles: file creation, manifest edits with best-effort comment preservation, sibling supporting-file symlinks (driven by `scripts/skill_repo_contracts.py::RUNTIME_SUPPORTING_FILES`), the validator run, and auto-install to every detected agent under `~/.claude/commands`, `~/.copilot/skills`, `~/.codex/skills` (or `~/.agents/skills`), `~/.config/opencode/skills`, and `~/.glm/commands`.

6. **Report.** Surface the scaffolder output verbatim — it tells the user the final skill path, any manifest edits, sidecar symlinks, install targets, and any interim-location or skipped-agent notes. Never paraphrase the validator's loud-fail errors; copy them through.

## Rules

- Never duplicate content across agents — only the Python installer creates symlinks.
- The scaffolder is atomic. If the validator fails, every staged change is rolled back and the error is surfaced verbatim; do not try to "keep partial work."
- If no agents are detected, the scaffolder skips the install step and notes that the user should run `./install.sh` to set up agent paths. Do not synthesize agent paths by hand.
- The skill body must include all six required H2 sections. The scaffolder-owned ones (`## Execution Mode Reporting`, `## Telemetry Ceremony Hooks`) are not customizable per skill — they come from the stored template and are identical across specialists in a family.
- Adding a new pre-shell family requires updating `skill_bill/constants.py::PRE_SHELL_FAMILIES` and `skill_bill/scaffold.py::FAMILY_REGISTRY` in the same change.
