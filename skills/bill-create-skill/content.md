# Create Skill Content

## Overview

This skill is the user-facing intake wrapper around the `skill-bill` CLI. Its job is to translate plain-language skill requests into the right CLI flow, show a concise preview, get approval, and then delegate the filesystem work to the CLI.

Use the CLI as the source of truth:

- `skill-bill create-and-fill` for one new governed content-managed skill when the user wants to scaffold and immediately author the first `content.md` body.
- `skill-bill new` for horizontal skills, pre-shell overrides, platform packs, and any case where `create-and-fill` is not supported.
- `skill-bill show`, `edit`, `fill`, `doctor skill`, `validate`, and `render` when the user is refining or inspecting an existing governed skill.

All filesystem work happens in the CLI/scaffolder implementation; this skill never edits `SKILL.md`, `content.md`, `platform.yaml`, or symlinks directly. If an agent path changes, the source of truth is `skillbill.install` in `runtime-core` (driven by `runtime-cli` install commands) — not this skill.

## Decision Tree

Internally, the scaffolder maps skill requests to exactly one of four kinds:

1. **horizontal** — capability works across stacks (e.g. `bill-pr-description`). Destination: `skills/<name>/SKILL.md`.
2. **platform-override-piloted** — a platform-specific variant of an existing base family.
   - Shelled families (`code-review`, `quality-check`) → destination: `platform-packs/<slug>/<family>/<name>/SKILL.md`.
   - Pre-shell families (`feature-implement`, `feature-verify`) → destination: `skills/<platform>/<name>/SKILL.md`; the scaffolder emits an interim-location note saying "will move when piloted."
3. **platform-pack** — bootstrap a new platform with the baseline skill set.
   - Destination: `platform-packs/<slug>/platform.yaml` plus baseline code-review and quality-check skills.
   - User-facing intake asks whether to scaffold the approved code-review specialist stubs.
   - `starter` remains available for direct payload callers and for users who only want the baseline pair first.
   - For known platforms such as `java` and `php`, the scaffolder infers routing signals from a built-in preset; ask for manual routing signals only when no preset exists and you do not have a defensible inference.
4. **code-review-area** — a specialist for one approved code-review area inside an existing platform pack.
   - Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.
   - Destination: `platform-packs/<slug>/code-review/<name>/SKILL.md` plus manifest edits.

Governed add-ons are pack-owned supporting files, not skills. Author them through the dedicated `skill-bill new-addon` CLI flow rather than this skill.

Refuse to invent a new family or code-review area inline. New platforms are allowed only through the `platform-pack` kind. If the user asks for a new family or unapproved area, ask them to first update the pre-shell family registry in `skillbill.scaffold` (`runtime-core`) or the approved-area list in the same change they submit the skill in.

## Workflow

1. Intent-first intake. Never start by pasting a full taxonomy or asking the user to choose from every scaffold kind at once unless they explicitly ask for the raw payload format.

   The default flow is interactive and contextual. Ask one question at a time. First ask only:

   ```text
   Platform name:
   ```

   Accept a real platform slug such as `java`, `php`, `kotlin`, `kmp`, or `cross-stack` when the user wants a horizontal skill with no owning platform.

   After the platform answer, branch contextually:

   - If the answer is `cross-stack`, ask only for the horizontal skill details: `Skill name:` and `What should it do?`.
   - If the platform does not exist yet or the user clearly wants a new platform pack, infer `platform-pack` immediately and continue to the summary.
   - If the platform already exists and the request is still ambiguous, ask only for the relevant platform-scoped type:

     ```text
     1. Code-Review Specialist
     2. Platform Override
     ```

   Do not show the non-platform options after the user has already chosen a concrete platform unless they ask to switch to a cross-stack skill. Do not restate the same menu in a second message. One question, then one follow-up question, then a summary. Translate the answers into the internal `kind`, `family`, and `area` yourself.

   For `platform-pack`, the default set means: baseline `bill-<platform>-code-review`, baseline `bill-<platform>-quality-check`, and all approved code-review specialist stubs when the user says yes to the specialist prompt.

   Only ask follow-ups that are still missing: skill name for horizontal, specialist, or override scaffolds; family for platform overrides; area for code-review specialists; description or display name only when the user explicitly wants to customize them before scaffolding; initial `content.md` body for governed skills when the user wants concrete behavior captured immediately; routing signals only for a new platform with no built-in preset and no defensible repo-marker inference.

   If the user says "create a skill set for Java" or equivalent, interpret that as `platform-pack` immediately. Ask whether to include the approved specialists, then continue.

2. Summarize the inferred request. Before rendering the preview, restate what you inferred in plain language: keep it short — platform slug, whether a built-in preset will be used, and the user-facing outcome. End with one confirmation prompt such as `Proceed? yes | redo`.

3. Choose the CLI path. Use `skill-bill create-and-fill` when all of the following are true: the request creates exactly one new content-managed skill; the resulting skill is a governed `code-review-area` or shelled `platform-override-piloted` (`code-review` or `quality-check`); and the user wants to capture initial authored behavior immediately.

   Use `skill-bill new` for `platform-pack`, `horizontal`, pre-shell platform overrides such as `feature-implement` / `feature-verify`, and any scaffold where immediate content authoring is not requested.

   If the user wants to change an existing governed skill instead of creating one, do not scaffold anything. Route them to `skill-bill show <skill-name>`, `skill-bill edit <skill-name>` or `skill-bill fill <skill-name>`, or `skill-bill doctor skill <skill-name>`.

4. Preview at the right abstraction level. Before calling the CLI, give a concise preview that covers which baseline skills or specialist stubs will be created, which CLI path will run (`create-and-fill`, `new`, or an edit/refinement path), whether the scaffold uses a built-in routing preset, and nothing else unless the user explicitly asks for file-level detail.

   Do not render the generated `SKILL.md` wrappers, starter `content.md` bodies, sidecar file contents, `platform.yaml` path, or placeholder disclaimers unless the user explicitly asks to inspect them. The default UX is a high-level inventory, not a file-by-file contract dump.

5. Confirm or restart. Keep the interaction minimal: `yes` accepts the preview and proceeds to scaffold; `redo` restarts the intake from scratch. Do not offer an `edit <section>` menu by default. If the user wants to customize wording such as the description before scaffolding, handle that as a normal follow-up in plain language instead of exposing internal section-edit commands.

6. Emit JSON payload. When the user accepts, build a payload shaped like:

   ```json
   {
     "scaffold_payload_version": "1.0",
     "kind": "...",
     "name": "bill-...",
     "platform": "...",
     "family": "...",
     "area": "...",
     "display_name": "...",
     "skeleton_mode": "full",
     "routing_signals": {
       "strong": ["..."],
       "tie_breakers": ["..."]
     },
     "description": "...",
     "content_body": "...",
     "subagent_specialists": ["foo-arch", "foo-perf"],
     "no_subagents": false
   }
   ```

   For orchestrator kinds (`horizontal`, `platform-override-piloted`, `platform-pack`), the scaffolder emits one Codex TOML stub and one OpenCode markdown stub per name listed in `subagent_specialists`. Stubs land at `<orchestrator-skill-dir>/codex-agents/<name>.toml` and `<orchestrator-skill-dir>/opencode-agents/<name>.md`, and the scaffolder injects a `## Subagent Spawn Runtime Notes` section into the orchestrator's `content.md`. Set `no_subagents: true` to opt out explicitly when an orchestrator should not delegate to native subagents (the two flags cannot both be set together). Specialist names must match `^[a-z][a-z0-9-]*$`, must be unique, and are NOT valid for leaf kinds such as `code-review-area` or `add-on`. After scaffolding, fill in the TODO placeholders in each generated stub before shipping.

   Omit `routing_signals` for known platforms with built-in presets. Include it only when overriding a preset or introducing an unknown platform whose routing signals cannot be inferred confidently. The full schema (required keys, worked examples per kind, the loud-fail exception catalog) lives in the repo at `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.

7. Subprocess into the CLI. Write the payload to a tempfile, then invoke exactly one of:

   ```bash
   skill-bill create-and-fill --payload <tempfile>
   skill-bill new --payload <tempfile>
   ```

   Choose `create-and-fill` only for the supported single-skill governed path described above. Otherwise use `new`. The CLI/scaffolder handles file creation, manifest edits with best-effort comment preservation, sibling supporting-file symlinks, the validator run, and auto-install to every detected agent.

8. Report. Surface the CLI output verbatim — it tells the user the final skill path, any manifest edits, sidecar symlinks, install targets, and any interim-location or skipped-agent notes. Never paraphrase the validator's loud-fail errors; copy them through.

## Rules

- Never duplicate content across agents; the installer creates symlinks.
- The scaffolder is atomic. If the validator fails, every staged change is rolled back and the error is surfaced verbatim; do not try to "keep partial work."
- If no agents are detected, the scaffolder skips the install step and notes that the user should run `./install.sh` to set up agent paths. Do not synthesize agent paths by hand.
- Default to conversational guidance. The raw field template and JSON payload are implementation details, not the primary UX.
- Default to the thinnest possible adapter behavior. This skill should gather intent, choose the right `skill-bill` command, and then get out of the way.
- Treat `platform-pack` as the one-shot bootstrap path for a new stack: create the baseline pair plus all approved specialists by default.
- Default previews to a concise inventory of created paths and generated stubs. For governed skills, tell the user to put skill instructions only in sibling `content.md` files; do not imply they should edit scaffold-managed `SKILL.md` wrappers or `shell-ceremony.md`.
- When the user wants to change an existing governed skill, send them through `skill-bill show`, `edit`, `fill`, or `doctor skill` instead of describing direct wrapper edits. Bulk migration and taxonomy changes are maintainer-only workflows.
- Do not repeat the same intake block, menu, confirmation prompt, or `Execution mode` line twice. The default interaction should feel like one short question, one contextual follow-up, one short summary, and one confirmation.
- Do not front-load the full decision tree into the first reply. The user should not have to read the entire taxonomy before answering the first question.
- Governed review-family and quality-check skills use the same wrapper contract: `SKILL.md` must keep `## Descriptor`, the canonical Execution pointer, and the canonical Ceremony pointer, with sibling `content.md` and `shell-ceremony.md` beside it. The shared ceremony sidecar is not customizable per skill; it comes from the stored template and stays identical across governed skills in the family.
- When reporting a successful governed scaffold, explicitly say that authored skill instructions belong only in `content.md`. `SKILL.md` and `shell-ceremony.md` are scaffold-managed contract files.
- Adding a new pre-shell family requires updating the pre-shell family registry and family registry under `skillbill.scaffold` in `runtime-core` in the same change.
