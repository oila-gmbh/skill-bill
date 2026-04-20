---
name: bill-create-skill
description: Use when scaffolding a new skill or platform skill set and syncing it to all detected local AI agents (Claude, Copilot, GLM, Codex, Opencode). Use when user mentions scaffold skill, create skill set, create skill, new skill, add skill, or sync skill to agents.
---

## Project Overrides

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

If `.agents/skill-overrides.md` exists in the project root and contains a matching section, read that section and apply it as the highest-priority instruction for this skill.

## Overview

This skill is the user-facing wrapper around `skill-bill new-skill`. You collect intent from the user, give a concise high-level preview of what will be created and where it will live, get approval, then call into the Python scaffolder to actually create files, edit manifests, wire sidecar symlinks, run the validator, and install into detected agents.

All filesystem work happens in `skill_bill/scaffold.py`; this skill never edits `SKILL.md`, `platform.yaml`, or symlinks directly. If an agent path changes, the source of truth is `skill_bill/install.py` — not this skill.

## Decision Tree

Internally, the scaffolder still maps skill requests to exactly one of these four kinds:

1. **horizontal** — capability works across stacks (e.g. `bill-pr-description`).
   - Destination: `skills/<name>/SKILL.md`.
2. **platform-override-piloted** — a platform-specific variant of an existing base family.
   - Shelled families (`code-review`, `quality-check`) → destination: `platform-packs/<slug>/<family>/<name>/SKILL.md`.
   - Pre-shell families (`feature-implement`, `feature-verify`) → destination: `skills/<platform>/<name>/SKILL.md`; the scaffolder emits an interim-location note saying "will move when piloted."
3. **platform-pack** — bootstrap a new platform with the baseline skill set.
   - Destination: `platform-packs/<slug>/platform.yaml` plus `platform-packs/<slug>/code-review/<bill-<slug>-code-review>/SKILL.md` and `platform-packs/<slug>/quality-check/<bill-<slug>-quality-check>/SKILL.md`.
   - Ask for the platform slug and whether the user wants just the baseline review path or also the code-review specialists.
   - `starter` → scaffold the pack root, baseline code-review, and default quality-check.
   - `full` → scaffold the starter set plus bare specialist stubs for every approved code-review area.
   - For known platforms such as `java` and `php`, the scaffolder infers routing signals from a built-in preset; ask for manual routing signals only when no preset exists and you do not have a defensible inference.
4. **code-review-area** — a specialist for one approved code-review area inside an existing platform pack.
   - Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.
   - Destination: `platform-packs/<slug>/code-review/<name>/SKILL.md` + manifest edits.
Governed add-ons are pack-owned supporting files, not skills. Author them through the dedicated `skill-bill new-addon` CLI flow rather than this skill.

Refuse to invent a new family or code-review area inline. New platforms are allowed only through the `platform-pack` kind. If the user asks for a new family or unapproved area, ask them to first update `skill_bill/constants.py::PRE_SHELL_FAMILIES` or the approved-area list in the same change they submit the skill in.

## Workflow

1. **Intent-first intake.** Never start by pasting a full taxonomy or asking the user to choose from every scaffold kind at once unless they explicitly ask for the raw payload format.
   - The default flow is interactive and contextual. Ask one question at a time.
   - First ask only:

     ```text
     Platform name:
     ```

   - Accept:
     - a real platform slug such as `java`, `php`, `kotlin`, `kmp`
     - `cross-stack` when the user wants a horizontal skill with no owning platform

   - After the platform answer, branch contextually:
     - If the answer is `cross-stack`, ask only for the horizontal skill details:
       - `Skill name:`
       - `What should it do?`
     - If the platform does not exist yet or the user clearly wants a new platform pack, ask only:

       ```text
       1. Baseline
       2. Baseline + Code Review Specialists
       ```

     - If the platform already exists and the request is still ambiguous, ask only for the relevant platform-scoped type:

       ```text
       1. Baseline
       2. Baseline + Code Review Specialists
       3. Code-Review Specialist
       4. Platform Override
       ```

   - Do not show the non-platform options after the user has already chosen a concrete platform unless they ask to switch to a cross-stack skill.
   - Do not restate the same menu in a second message. One question, then one follow-up question, then a summary.
   - Translate the answers into the internal `kind`, `family`, `area`, and `skeleton_mode` yourself.
   - For `platform-pack`, the baseline set means:
     - baseline `bill-<platform>-code-review`
     - baseline `bill-<platform>-quality-check`
   - Only ask follow-ups that are still missing:
     - skill name for horizontal, specialist, or override scaffolds
     - family for platform overrides
     - area for code-review specialists
     - description or display name only when the user explicitly wants to customize them before scaffolding
     - routing signals only for a new platform with no built-in preset and no defensible repo-marker inference

   If the user says “create a skill set for Java” or equivalent, interpret that as `platform-pack` immediately. Ask only the baseline/full follow-up and do not show the broader platform-scoped menu.

2. **Summarize the inferred request.** Before rendering the preview, restate what you inferred in plain language:
   - Keep it short: platform slug, baseline vs full, whether a built-in preset will be used, and the user-facing outcome.
   - End with one confirmation prompt such as `Proceed? yes | redo`.

3. **Preview at the right abstraction level.** Before calling the scaffolder, give a concise preview that covers:
   - which baseline skills or specialist stubs will be created
   - whether the scaffold uses a built-in routing preset
   - nothing else unless the user explicitly asks for file-level detail

   Do **not** render the generated `SKILL.md` wrappers, `content.md` TODO bodies, sidecar file contents, `platform.yaml` path, or placeholder disclaimers unless the user explicitly asks to inspect them. The default UX is a high-level inventory, not a file-by-file contract dump.

4. **Confirm or restart.** Keep the interaction minimal:
   - `yes` — accept the preview and proceed to scaffold.
   - `redo` — restart the intake from scratch.

   Do not offer an `edit <section>` menu by default. If the user wants to customize wording such as the description before scaffolding, handle that as a normal follow-up in plain language instead of exposing internal section-edit commands.

5. **Emit JSON payload.** When the user accepts, build a payload shaped like:

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

   Omit `routing_signals` for known platforms with built-in presets. Include it only when overriding a preset or introducing an unknown platform whose routing signals cannot be inferred confidently.

   The full schema (required keys, worked examples per kind, the loud-fail exception catalog) lives in the repo at `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.

6. **Subprocess into the scaffolder.** Write the payload to a tempfile, then invoke:

   ```bash
   skill-bill new-skill --payload <tempfile>
   ```

   The scaffolder handles: file creation, manifest edits with best-effort comment preservation, sibling supporting-file symlinks (driven by `scripts/skill_repo_contracts.py::RUNTIME_SUPPORTING_FILES`), the validator run, and auto-install to every detected agent under `~/.claude/commands`, `~/.copilot/skills`, `~/.codex/skills` (or `~/.agents/skills`), `~/.config/opencode/skills`, and `~/.glm/commands`.

7. **Report.** Surface the scaffolder output verbatim — it tells the user the final skill path, any manifest edits, sidecar symlinks, install targets, and any interim-location or skipped-agent notes. Never paraphrase the validator's loud-fail errors; copy them through.

## Rules

- Never duplicate content across agents — only the Python installer creates symlinks.
- The scaffolder is atomic. If the validator fails, every staged change is rolled back and the error is surfaced verbatim; do not try to "keep partial work."
- If no agents are detected, the scaffolder skips the install step and notes that the user should run `./install.sh` to set up agent paths. Do not synthesize agent paths by hand.
- Default to conversational guidance. The raw field template and JSON payload are implementation details, not the primary UX.
- Treat `platform-pack` as the one-shot bootstrap path for a new stack: platform slug plus specialist depth should be enough to get the initial set created.
- Default previews to a concise inventory of created paths and generated stubs. For governed skills, tell the user to put skill instructions only in sibling `content.md` files; do not imply they should edit scaffold-managed `SKILL.md` wrappers or `shell-ceremony.md`.
- Do not repeat the same intake block, menu, confirmation prompt, or `Execution mode` line twice. The default interaction should feel like one short question, one contextual follow-up, one short summary, and one confirmation.
- Do not front-load the full decision tree into the first reply. The user should not have to read the entire taxonomy before answering the first question.
- Governed review-family and quality-check skills use the same wrapper contract: `SKILL.md` must keep `## Descriptor`, `## Execution`, and `## Ceremony`, with sibling `content.md` and `shell-ceremony.md` beside it. The shared ceremony sidecar is not customizable per skill; it comes from the stored template and stays identical across governed skills in the family.
- When reporting a successful governed scaffold, explicitly say that authored skill instructions belong only in `content.md`. `SKILL.md` and `shell-ceremony.md` are scaffold-managed contract files.
- Adding a new pre-shell family requires updating `skill_bill/constants.py::PRE_SHELL_FAMILIES` and `skill_bill/scaffold.py::FAMILY_REGISTRY` in the same change.
