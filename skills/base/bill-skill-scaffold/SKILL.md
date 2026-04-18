---
name: bill-skill-scaffold
description: Use when scaffolding a new skill or platform skill set and syncing it to all detected local AI agents (Claude, Copilot, GLM, Codex, Opencode). Use when user mentions scaffold skill, create skill set, create skill, new skill, add skill, or sync skill to agents.
---

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-skill-scaffold` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Overview

This skill is the user-facing wrapper around `skill-bill new-skill`. You collect intent from the user, preview the scaffolded output with synthesized markers, iterate on edits until the user approves, then call into the Python scaffolder to actually create files, edit manifests, wire sidecar symlinks, run the validator, and install into detected agents.

All filesystem work happens in `skill_bill/scaffold.py`; this skill never edits `SKILL.md`, `platform.yaml`, or symlinks directly. If an agent path changes, the source of truth is `skill_bill/install.py` — not this skill.

## Decision Tree

Ask the user enough to pick exactly one of the five kinds:

1. **horizontal** — capability works across stacks (e.g. `bill-pr-description`).
   - Destination: `skills/base/<name>/SKILL.md`.
2. **platform-override-piloted** — a platform-specific variant of an existing base family.
   - Shelled families (`code-review`, `quality-check`) → destination: `platform-packs/<slug>/<family>/<name>/SKILL.md`.
   - Pre-shell families (`feature-implement`, `feature-verify`) → destination: `skills/<platform>/<name>/SKILL.md`; the scaffolder emits an interim-location note saying "will move when piloted."
3. **platform-pack** — create a new platform pack root with a generated baseline code-review skill and a default quality-check skill.
   - Destination: `platform-packs/<slug>/platform.yaml` plus `platform-packs/<slug>/code-review/<bill-<slug>-code-review>/SKILL.md` and `platform-packs/<slug>/quality-check/<bill-<slug>-quality-check>/SKILL.md`.
   - Ask for platform slug plus `skeleton_mode`.
   - `starter` → scaffold the pack root, baseline code-review, and default quality-check.
   - `full` → scaffold the starter pack plus bare specialist stubs for every approved code-review area.
   - For known platforms such as `java`, the scaffolder infers routing signals from a built-in preset; ask for manual routing signals only when no preset exists.
4. **code-review-area** — a specialist for one approved code-review area inside an existing platform pack.
   - Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.
   - Destination: `platform-packs/<slug>/code-review/<name>/SKILL.md` + manifest edits.
5. **add-on** — a stack-owned supporting asset, not a standalone skill.
   - Destination: `skills/<platform>/addons/<name>.md` (flat, no sub-directory).

Refuse to invent a new family or code-review area inline. New platforms are allowed only through the `platform-pack` kind. If the user asks for a new family or unapproved area, ask them to first update `skill_bill/constants.py::PRE_SHELL_FAMILIES` or the approved-area list in the same change they submit the skill in.

## Workflow

1. **Paste step.** Ask the user to paste:
   - skill name when the kind needs one (validate the `bill-...` prefix)
   - one-line description
   - kind (from the decision tree)
   - platform / family / area / display name / skeleton mode as required by the kind
   - routing signals only for a new platform with no built-in preset

   If the user says “create a skill set for Java” or equivalent, interpret that as `kind=platform-pack`. Ask one follow-up:
   - `starter` — baseline Java pack only
   - `full` — baseline Java pack plus all approved review-area skeletons

2. **Preview with synthesized markers.** Before calling the scaffolder, render a preview that shows the final destination path and the generated contract shape:
   - For review-family skills, preview the six required H2 headings with synthesized defaults:
     - `## Description`
     - `## Specialist Scope`
     - `## Inputs`
     - `## Outputs Contract`
     - `## Execution Mode Reporting` *(scaffolder-owned, byte-identical across specialists in a family)*
     - `## Telemetry Ceremony Hooks` *(scaffolder-owned, byte-identical across specialists in a family)*
   - For quality-check skills, preview the five required H2 headings:
     - `## Description`
     - `## Execution Steps`
     - `## Fix Strategy`
     - `## Execution Mode Reporting`
     - `## Telemetry Ceremony Hooks`
   - For `platform-pack`, preview the generated manifest path plus the baseline `bill-<platform>-code-review` skill and the default `bill-<platform>-quality-check` skill that will be scaffolded together.
   - For `platform-pack` with `skeleton_mode=full`, also preview the list of approved specialist stubs that will be created.

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
      "display_name": "...",
     "skeleton_mode": "starter | full",
     "routing_signals": {
       "strong": ["..."],
       "tie_breakers": ["..."],
       "addon_signals": ["..."]
     },
     "description": "..."
   }
   ```

   Omit `routing_signals` for known platforms with built-in presets. Include it only when overriding a preset or introducing an unknown platform.

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
- Review-family skill bodies must include all six required H2 sections; quality-check skill bodies use the five-section contract. The scaffolder-owned sections (`## Execution Mode Reporting`, `## Telemetry Ceremony Hooks`) are not customizable per skill — they come from the stored template and are identical across specialists in a family.
- Adding a new pre-shell family requires updating `skill_bill/constants.py::PRE_SHELL_FAMILIES` and `skill_bill/scaffold.py::FAMILY_REGISTRY` in the same change.
