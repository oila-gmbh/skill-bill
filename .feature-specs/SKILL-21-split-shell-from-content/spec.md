---
issue_key: SKILL-21
feature_name: split-shell-from-content
feature_size: LARGE
status: In Progress
created: 2026-04-19
depends_on: SKILL-14 (shell contract — shipped), SKILL-15 (new-skill scaffolder — shipped)
---

# SKILL-21 — Split governance shell from author-owned content file

## Problem

Every SKILL.md in this repo mixes two different things under one roof:

1. **Governance shell** — frontmatter routing hints, the six required H2 sections, sidecar-file references, execution-mode reporting boilerplate, and telemetry ceremony hooks. This is infrastructure the contract enforces; it must exist and it must be structurally identical across specialists in a family.
2. **Author-owned skill body** — the actual prompt the agent executes (`## Setup`, `## Dynamic Specialist Selection`, `## Review Output`, etc. in the code-review case). This is what the skill *does*.

Today these two concerns live in the same file. A first-time reader who opens `platform-packs/kotlin/code-review/bill-kotlin-code-review/SKILL.md` sees 234 lines of which the majority is contract ceremony. The author-relevant prose is interleaved with boilerplate (`## Execution Mode Reporting`, `## Telemetry Ceremony Hooks`, duplicated scope summaries, sidecar pointers).

**Observed adoption symptom:** when the repo owner walks people through how skill-bill works, faces fall during the explanation — the governance model has to be unpacked before the listener reaches anything they'd recognize as "a skill." The artifact surface reinforces that load: opening a SKILL.md to understand the product drops the reader into infrastructure first, content second.

SKILL-15 already named this split conceptually by labeling two bands: **scaffolder-owned** (`## Execution Mode Reporting`, `## Telemetry Ceremony Hooks`) and **author-owned** (`## Description`, `## Specialist Scope`, `## Inputs`, `## Outputs Contract`). That distinction currently lives only inside the scaffolder; at rest, both bands share one file. This ticket operationalizes the distinction as a **file split**: governance in `SKILL.md`, author-owned skill body in a sibling `content.md` that the shell references.

## Why now

- **SKILL-14 through SKILL-20 have landed.** The shell+content contract is stable at v1.0, the scaffolder handles all four skill kinds, `bill-quality-check` is piloted, add-ons are shelled, platform-scaffolding is first-class, and built-ins have narrowed to `kotlin` and `kmp`. The governance layer is effectively complete. The remaining adoption barrier is surface complexity, not missing capability.
- **The scaffolder already knows how to synthesize SKILL.md from templates.** `skill_bill/scaffold_template.py` already renders the required sections from a `ScaffoldTemplateContext`. Extending that to also write a sibling `content.md` — and to point the generated SKILL.md at it — is an incremental move, not a rewrite.
- **Runtime governance does not need to change.** The loud-fail loader, manifest schema, routing playbooks, and telemetry contracts all continue to work unchanged. What changes is what a skill *file* looks like on disk and what an author interacts with when they create or edit one.
- **Multi-agent sync stays intact.** Because SKILL.md remains a real structured Markdown file, Copilot / Codex / OpenCode / GLM continue to consume skills exactly as they do today. This ticket does not trade away the multi-runtime story.

## Context (what a new-session implementer needs to know)

### What skill-bill is today

- **Governed shell + content architecture (SKILL-14).** `skills/bill-code-review/SKILL.md` and `skills/bill-quality-check/SKILL.md` are horizontal shells. Platform-specific content lives under `platform-packs/<slug>/code-review/<skill>/SKILL.md` and `platform-packs/<slug>/quality-check/<skill>/SKILL.md`.
- **Built-in packs (SKILL-20).** Only `kotlin` and `kmp` ship as first-party packs. Everything else is authored downstream via the scaffolder.
- **Versioned shell+content contract.** `orchestration/shell-content-contract/PLAYBOOK.md` defines the manifest schema, required files, required H2 sections, and loud-fail rules. Current `SHELL_CONTRACT_VERSION` is `"1.0"` (see `skill_bill/constants.py`).
- **Runtime loader.** `skill_bill/shell_content_contract.py` is the authority on contract validation. Named exceptions: `MissingManifestError`, `InvalidManifestSchemaError`, `ContractVersionMismatchError`, `MissingContentFileError`, `MissingRequiredSectionError`, `PyYAMLMissingError`. No silent fallback.
- **Scaffolder (SKILL-15) with band awareness.** `skill_bill/scaffold_template.py` distinguishes scaffolder-owned sections (`## Execution Mode Reporting`, `## Telemetry Ceremony Hooks`, rendered from templates) from author-owned sections (`## Description`, `## Specialist Scope`, `## Inputs`, `## Outputs Contract`, currently seeded from family-aware defaults and then expected to be hand-edited).
- **Installer / sync.** `install.sh` symlinks skill directories into detected agent roots (Claude Code, Copilot, Codex, OpenCode, GLM). The symlink points at the skill directory; every file inside it follows.

### Required contract sections today

| Family          | Required H2s (in `SKILL.md`)                                                                                                                   |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `code-review`   | `## Description`, `## Specialist Scope`, `## Inputs`, `## Outputs Contract`, `## Execution Mode Reporting`, `## Telemetry Ceremony Hooks`      |
| `quality-check` | `## Description`, `## Execution Steps`, `## Fix Strategy`, `## Execution Mode Reporting`, `## Telemetry Ceremony Hooks`                        |

Additional free-form H2s are permitted (and are where the actual skill body lives today — e.g. `## Setup`, `## Kotlin-Family Classification`, `## Dynamic Specialist Selection`, `## Review Output` in `bill-kotlin-code-review`).

### What the split looks like on disk after SKILL-21

```
platform-packs/kotlin/code-review/bill-kotlin-code-review/
  SKILL.md                       # generated shell — frontmatter + required H2s + reference to content.md
  content.md                     # user-authored skill body — free-form, no required sections
  stack-routing.md               # existing sidecar symlinks — unchanged
  review-orchestrator.md
  review-delegation.md
  telemetry-contract.md
  shell-content-contract.md
```

The required-H2 contract survives. Each required H2 in SKILL.md contains a concise, template-rendered body that summarizes the skill's shape (the `infer_skill_description`-style defaults SKILL-15 already produces). The **actual execution prose** moves into `content.md`. A new pointer in SKILL.md directs the agent to read `content.md` for the skill body.

### Band distinction after SKILL-21

| Band                | Location     | Required structure                     | Authority                  |
| ------------------- | ------------ | -------------------------------------- | -------------------------- |
| Frontmatter         | `SKILL.md`   | `name`, `description`, version stamps  | Scaffolder (from CLI input) |
| Governance shell    | `SKILL.md`   | Required H2 set per family             | Scaffolder template        |
| Author skill body   | `content.md` | **None** — completely free-form        | User                       |

### What stays the same

- Manifest-driven routing (`platform-packs/<slug>/platform.yaml`).
- Loud-fail discovery; named exceptions.
- Six required H2s for code-review, five for quality-check.
- Sidecar symlinks.
- Multi-agent installer / sync.
- Telemetry schema, MCP event shape, learnings pipeline.

### What changes

- Shell contract bumps to `"1.1"`.
- SKILL.md frontmatter gains `shell_contract_version` and `template_version` (or equivalent) for upgrade detection.
- The required H2 set gains a new section (tentatively `## Execution`) whose body points at `content.md`. Section added to both families' contracts.
- Validator requires `content.md` to exist as a sibling file for every governed skill and loud-fails with a named exception when it is missing.
- Scaffolder writes two files instead of one; the author-owned H2 seeds SKILL-15 used to drop into SKILL.md now land in `content.md` (and only `content.md`).
- CLI grows `skill-bill edit <name>` (opens `content.md` only) and `skill-bill upgrade` (regenerates SKILL.md from the current template without touching `content.md`).
- A one-shot migration script moves every existing SKILL.md's author prose into a new sibling `content.md` and regenerates the shell.

## Acceptance criteria

1. **Shell contract v1.1.** `SHELL_CONTRACT_VERSION` in `skill_bill/constants.py` is `"1.1"`. `orchestration/shell-content-contract/PLAYBOOK.md` documents the v1.1 rules: required `content.md` sibling, new reference H2, frontmatter version stamps, and the loud-fail exceptions. Packs still declaring `contract_version: "1.0"` fail loudly via `ContractVersionMismatchError` with a migration message pointing at this spec's migration script.

2. **SKILL.md frontmatter adds version stamps.** Every SKILL.md generated by the scaffolder (and every existing SKILL.md after migration) carries:
   - `shell_contract_version` — the contract version the shell was generated against (e.g. `"1.1"`).
   - `template_version` — a stable identifier (git SHA fragment, semver, or monotonic counter) for the scaffolder template used to render the shell. Bumped in `skill_bill/constants.py` whenever the template changes.
   Mismatch between an SKILL.md's `template_version` and the current template is not a runtime failure; it is an `upgrade`-actionable state surfaced by `skill-bill upgrade` and `skill-bill doctor`.

3. **Required `content.md` sibling.** For every governed skill (code-review or quality-check family), a `content.md` file must exist in the same directory as SKILL.md. Loader raises a new `MissingContentBodyFileError` (named exception, sibling to the existing catalog) with the resolved path when the sibling is absent. `content.md` has no H2 requirements, no frontmatter requirement, and no minimum length.

4. **SKILL.md references `content.md` via a new required H2.** Both content contracts (code-review and quality-check) gain `## Execution` as a required H2. Scaffolder emits a fixed body:
   ```
   ## Execution

   Follow the instructions in [content.md](content.md).
   ```
   This body is byte-identical across every skill. Missing `## Execution`, or a body that does not contain a Markdown link to `content.md`, raises `MissingRequiredSectionError` / a new `InvalidExecutionSectionError` (pick one; see open questions).

5. **Scaffolder writes both files.** `skill_bill/scaffold.py` is updated so that every skill-kind write path emits `SKILL.md` **and** `content.md`. The author-authored sections (`## Description`, `## Specialist Scope`, `## Inputs`, `## Outputs Contract` for code-review; `## Description`, `## Execution Steps`, `## Fix Strategy` for quality-check) are rendered into SKILL.md as **concise shape descriptors** — the same one-line-per-slot text `infer_skill_description`, `render_specialist_scope_section`, etc. already produce. The **free-form skill body** the user pastes (when authored through the LLM-guided flow) lands in `content.md`. Payload schema gains an optional `content_body: string` field; when present, the scaffolder writes it verbatim to `content.md` after trimming trailing whitespace; when absent, `content.md` is created with a minimal placeholder (`# <skill-name>\n\n<one-line description>\n`) that the author immediately edits.

6. **Scaffolder is still atomic.** Every acceptance behavior from SKILL-15 (manifest atomicity, validator-on-write, rollback on any failure) continues to hold. Rollback now also removes the sibling `content.md`. Half-scaffolded state remains impossible.

7. **`skill-bill edit <skill-name>` opens `content.md`.** New CLI subcommand. Locates the skill by name (resolving both `skills/` and `platform-packs/*/` layouts), opens `content.md` in `$EDITOR` (or prints the path when no editor is configured). Never opens `SKILL.md`. The help text explicitly says that `SKILL.md` is generated and not user-editable.

8. **`skill-bill upgrade` regenerates SKILL.md from the current template.** New CLI subcommand. Walks every governed skill, detects any whose `template_version` does not match the current template, and regenerates `SKILL.md` only — `content.md` is never touched. Supports `--dry-run` (print planned changes, do not write), `--skill <name>` (regenerate a single skill), and batch mode (default; prompts for confirmation with a diff summary unless `--yes` is passed). Rollback rules match the scaffolder's — any validator failure after regeneration reverts the affected SKILL.md from an in-memory snapshot.

9. **`skill-bill doctor` reports content/template drift.** The existing `doctor` command gains two new checks: (a) every governed skill has a `content.md` sibling; (b) every SKILL.md's `template_version` matches the current template. Drift is reported as a warning with the exact `skill-bill upgrade` command to resolve it; missing `content.md` is reported as an error (same as any other contract violation).

10. **Validator accepts the new layout.** `scripts/validate_agent_configs.py` and `skill_bill/shell_content_contract.py` both understand the new contract:
    - `## Execution` is recognized as a required H2.
    - Sibling `content.md` presence is required.
    - `content.md` needs no internal structure validated.
    - `shell_contract_version` and `template_version` are read from SKILL.md frontmatter; mismatched contract versions loud-fail, mismatched template versions warn.

11. **One-shot migration script.** `scripts/migrate_to_content_md.py` walks every governed skill (all governed skills under `skills/` and `platform-packs/*/`), and for each:
    1. Parses SKILL.md into frontmatter + H2 sections (reusing existing parsing utilities from `skill_bill/shell_content_contract.py` where possible).
    2. Identifies **free-form H2s** — any H2 not in the family's required set. These sections' bodies become `content.md`.
    3. Identifies author-edited content inside required H2s (detected by comparing against scaffolder template defaults via `scaffold_template.extract_scaffolder_owned` and equivalent helpers to be added for author-owned renderers). When a required section's body differs from the template default, the full section is appended to `content.md` after the free-form H2s.
    4. Rewrites SKILL.md from the current (v1.1) template, preserving the original `name` and `description` in frontmatter.
    5. Writes `content.md` with the extracted body.
    6. Runs the validator; rolls the affected skill back from an in-memory snapshot on any failure.
    7. Is idempotent: running twice yields the same on-disk result (or skips skills whose `content.md` already exists, unless `--force` is passed).

12. **Migration backup.** Before the first write, the migration script creates `_migration_backup/<timestamp>/` containing every SKILL.md it is about to modify. If any skill's migration fails validation, only that skill rolls back; the rest continue. A final summary prints successes, skips, and failures. Git commits are out of scope for the script — the user commits the result manually.

13. **Multi-agent sync copies both files.** `install.sh` continues to symlink the skill directory, which means `content.md` is automatically visible to every detected agent. Runtime behavior across Claude Code / Copilot / Codex / OpenCode / GLM remains identical — every agent sees both files. If any agent's install path copies individual files rather than directory-symlinking, that agent's install-path code is updated to include `content.md`.

14. **Template rendering preserves byte-for-byte stability.** The scaffolder's template output for SKILL.md is fully deterministic: same inputs (payload + template version) produce the same bytes on disk. `content.md` is deterministic only on the scaffold-path (user-provided body → file); `upgrade` never rewrites `content.md`.

15. **Tests cover the new surface.** `tests/test_scaffold.py` gains cases for:
    - Scaffolder writes both files for each of the four skill kinds.
    - Rollback: removing `content.md` on any post-write failure.
    - Scaffold with `content_body` absent → placeholder written.
    - Scaffold with `content_body` present → body written verbatim.
    
    A new `tests/test_migration.py` (or `tests/test_migrate_to_content_md.py`) covers:
    - Happy path: skill with default template → migrates cleanly; `content.md` holds free-form H2s; SKILL.md regenerates cleanly.
    - Skill with hand-edited required sections → edited content captured in `content.md`.
    - Idempotency: second run is a no-op.
    - Rollback: validator failure on one skill does not affect others.
    - Backup directory created before write.
    
    `tests/test_shell_content_contract.py` (or equivalent) gains cases for:
    - Missing `content.md` raises `MissingContentBodyFileError`.
    - Missing `## Execution` raises `MissingRequiredSectionError`.
    - `contract_version: "1.0"` raises `ContractVersionMismatchError` with a migration message.
    - `## Execution` body without a `content.md` link raises the designated exception.
    
    `tests/test_cli.py` (or equivalent) covers:
    - `skill-bill edit` opens `content.md`, never `SKILL.md`.
    - `skill-bill upgrade --dry-run` prints plan, makes no writes.
    - `skill-bill upgrade --skill <name>` regenerates only the target.
    - `skill-bill doctor` reports content/template drift with actionable messages.

16. **Full repo migration in the same PR.** The migration script runs over every governed skill in-repo as part of this PR. The committed result is: every SKILL.md regenerated from the v1.1 template, every skill has a `content.md` sibling, full test suite passes, `npx --yes agnix --strict .` passes, and `scripts/validate_agent_configs.py` passes.

17. **Docs updated.** README.md adds an "Authoring a skill" section that describes the split and shows a before/after — a one-line skill's content.md next to its generated SKILL.md. `AGENTS.md` adds a subsection explaining that SKILL.md is generated and `content.md` is the author-editable surface. `orchestration/shell-content-contract/PLAYBOOK.md` documents the v1.1 contract changes (new `## Execution` requirement, `content.md` sibling, version stamps, new exception). `docs/getting-started-for-teams.md` updates the authoring walkthrough.

18. **MCP tool surface unchanged.** The skill-bill MCP server (`skill_bill/mcp_server.py`) keeps its current tool set. No new tools are added, no event shape changes. This PR is authoring-surface work, not runtime-protocol work.

19. **Existing validation commands still pass.** `.venv/bin/python3 -m unittest discover -s tests`, `npx --yes agnix --strict .`, and `.venv/bin/python3 scripts/validate_agent_configs.py` all pass post-migration.

20. **No behavior change for agents.** After migration, running `/bill-code-review`, `/bill-kotlin-code-review`, `/bill-quality-check`, etc. must produce identical review output to pre-migration. The agent reads SKILL.md, follows the link to `content.md`, and executes the same instructions it would have executed from the single-file form. This is verifiable by running a code-review pass on a controlled diff before and after migration and confirming the output shape and recommendations do not materially shift.

## Non-goals

- **Registry-owned routing.** Proposed and considered during spec discussion; rejected because it would trade away multi-agent parity and require a runtime rewrite. The filesystem + manifest layout stays authoritative.
- **Making skills fully free-form (no contract).** The governance shell survives; this ticket reduces *visible* ceremony, not *enforced* ceremony. Validators and loud-fail rules remain.
- **Removing required H2 sections from SKILL.md.** The six (code-review) / five (quality-check) required H2s stay — they now carry concise metadata rather than full prose, but the structural contract is unchanged.
- **Bumping to a major contract version.** 1.0 → 1.1 is an additive change (one new required H2, one new required sibling file). Existing packs fail loudly on the version string, but the migration script resolves them mechanically. There is no semantics break that warrants 2.0.
- **Changing the MCP event shape, telemetry schema, or learnings pipeline.**
- **Changing the routing playbook or add-on semantics.** Pack discovery, routing signals, and add-on resolution are unchanged.
- **New skill kinds.** The four kinds from SKILL-15 (`horizontal`, `platform-override-piloted`, `platform-override-preshell`, `code-review-area`, `add-on`) are unchanged.
- **Renaming, merging, or deleting existing skills.** Migration regenerates them in place.
- **Adding a content.md linter or "is this a useful prompt" validator.** `content.md` is free-form by design; the validator stops at "does it exist."
- **A UI / editor integration.** `skill-bill edit` shells out to `$EDITOR`; no bespoke editor is shipped.
- **Backwards compatibility for v1.0 packs at runtime.** 1.0 packs loud-fail with a migration message. We do not support mixed-version repos.

## Open questions to resolve in planning

1. **Reference-section naming and exception shape.** Is the new required H2 `## Execution`, `## Skill Body`, or something else? If invalid (missing link to `content.md`), does that raise `MissingRequiredSectionError` (reused) or a new `InvalidExecutionSectionError`? Recommend: `## Execution` + new `InvalidExecutionSectionError` for specificity, but `MissingRequiredSectionError` with a well-composed message would also be valid.

2. **Template source of truth.** The SKILL.md template is currently assembled by Python functions in `skill_bill/scaffold_template.py`. Should it stay pure Python, or move to a `templates/SKILL.md.j2` (Jinja2) file rendered by a small runtime helper? Pure Python keeps dependencies minimal and is unit-testable today. Jinja2 is easier to read as a single artifact and invites template diffs in PRs. Recommend: stay Python for this PR (minimal disruption); revisit if template count grows.

3. **`template_version` identity.** Options: (a) a git SHA fragment of `scaffold_template.py`, (b) a semver string in `skill_bill/constants.py`, (c) a content-hash of the rendered template for a canonical payload. (b) is the simplest; bump it every time the template changes. Recommend (b).

4. **Author-edited required-section detection in the migration script.** The script needs to decide whether a required section in an existing SKILL.md is "template default" (safe to regenerate) or "author-edited" (must preserve into `content.md`). Options: (a) exact byte match against the current scaffolder default — simple but brittle if formatting drifted; (b) a normalized comparison (strip whitespace, collapse blank lines); (c) always treat existing content as edited and let the user clean up post-migration. Recommend: normalized compare with a `--strict` flag that uses byte match for CI.

5. **What if `content.md` is empty post-migration?** Some skills may have almost no free-form H2s because all useful content lived inside the six required sections (unlikely but possible). Should the script write an empty `content.md`, a placeholder stub, or fail the skill? Recommend: write a placeholder with a clear TODO so the validator passes; flag the skill in the migration summary for manual review.

6. **Where does `description` in frontmatter come from during `upgrade`?** Current SKILL.md frontmatter already carries `description`. `upgrade` should preserve whatever description is in the current SKILL.md (not regenerate it from scaffolder defaults). Confirm this is the intent.

7. **Agent behavior with pointer H2.** We need to confirm empirically that every supported runtime (Claude Code, Copilot, Codex, OpenCode, GLM) actually follows a `[content.md](content.md)` link inside `## Execution` and reads the file. Claude Code's sibling-file loading is proven (stack-routing.md, etc. already work this way). The other runtimes use the same symlink layout, so behavior should carry over, but planning should include a one-off smoke test on each agent before the repo-wide migration runs.

8. **`skill-bill edit` editor selection.** Does it respect `$VISUAL` then `$EDITOR` (POSIX convention), prompt interactively if neither is set, or error out with a hint? Recommend: `$VISUAL` → `$EDITOR` → print the path with a helpful note.

9. **Interaction with the scaffolder's LLM-guided flow (SKILL-15 Layer 3).** The `bill-skill-scaffold` skill currently asks for a free-form draft and structures it into the four author-owned sections. After SKILL-21, that draft goes into `content.md` unchanged, and the four author-owned sections get rendered to SKILL.md as shape descriptors from the scaffolder template. Is the LLM still asked to produce those descriptors, or does the scaffolder use inferred defaults exclusively? Recommend: inferred defaults exclusively — the LLM focuses on the free-form body, which is what users actually care about.

10. **Validator ordering.** When validating a pack post-migration, what's the right failure precedence if multiple things are wrong (e.g., wrong contract version AND missing `content.md`)? The existing loader raises the first named exception it hits; preserve that behavior and list exceptions in contract-version → manifest-schema → content-file → content-section → execution-link → content-sibling order.

## Scenario mock-ups (for context)

### Scenario A — authoring a new skill (post-SKILL-21)

```
User: /bill-skill-scaffold
Claude: What kind of skill?
  1. standalone / horizontal
  2. platform override of an existing base skill
  3. code-review specialist area
  4. governed add-on
User: 3

Claude: Which platform? (discovered)
  1. kmp   2. kotlin   3. create new platform
User: 2

Claude: Which area? (already-declared: architecture, performance, platform-correctness,
        security, testing, api-contracts, persistence, reliability)
User: Let me re-use platform-correctness? Actually it's already there.
Claude: All eight approved areas are declared for kotlin. Choose a different
        platform, or create a new platform pack.

User: 1 (create new area on a new platform)
...

User: Paste your draft — free-form. This goes directly into content.md.
      Terminator: line containing only `.`
User:
      Reviews logging hygiene in shared Kotlin code...
      [fifty lines of actual skill body]
      .

Claude: I'll scaffold:
  - platform-packs/<slug>/code-review/bill-<slug>-code-review-logging/SKILL.md
    (generated shell — not user-editable)
  - platform-packs/<slug>/code-review/bill-<slug>-code-review-logging/content.md
    (your draft — this is the skill body)
  - manifest update: platform-packs/<slug>/platform.yaml

Claude → skill-bill new-skill --payload <tempfile>

✅ Scaffolded SKILL.md + content.md
✅ Updated platform.yaml
✅ Validator passed
✅ Tests pass
```

### Scenario B — editing a skill

```
User: skill-bill edit bill-kotlin-code-review

(opens platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md
 in $EDITOR — never SKILL.md)
```

### Scenario C — contract template bump; upgrading

```
[maintainer bumps scaffold_template.py and TEMPLATE_VERSION in constants.py]

User: skill-bill doctor
⚠ 47 skills have outdated template_version (current: "2026.04.20", skills at "2026.04.15")
  Run `skill-bill upgrade --dry-run` to preview, then `skill-bill upgrade` to apply.

User: skill-bill upgrade --dry-run
[prints per-skill diff of SKILL.md; content.md untouched]

User: skill-bill upgrade
✅ Regenerated 47 SKILL.md files
✅ Validator passed
✅ content.md files untouched
```

### Scenario D — migration (one-shot, PR-time)

```
$ .venv/bin/python3 scripts/migrate_to_content_md.py
Walking governed skills under skills/ and platform-packs/...
  skills/bill-code-review/                         → migrate
  skills/bill-quality-check/                       → migrate
  platform-packs/kotlin/code-review/bill-kotlin-code-review/             → migrate
  platform-packs/kotlin/code-review/bill-kotlin-code-review-architecture/ → migrate
  ...
Writing backups to _migration_backup/2026-04-19T15-22-07/
Migrating 18 skills...
  ✅ bill-code-review: 6 free-form sections → content.md, SKILL.md regenerated
  ✅ bill-kotlin-code-review: 5 free-form sections + 2 edited required sections → content.md
  ...
  ⚠ bill-foo-code-review-bar: content.md would be empty; placeholder written
Validator: pass (217 tests, agnix strict, agent-configs validator all green)

Summary: 18 migrated, 0 failed, 1 flagged. Commit when ready.
```

## Files expected to change

Created:
- `content.md` in every governed skill directory (via migration script).
- `scripts/migrate_to_content_md.py` — one-shot migration tool.
- `tests/test_migration.py` (or `tests/test_migrate_to_content_md.py`) — migration coverage.

Modified:
- `skill_bill/constants.py` — bump `SHELL_CONTRACT_VERSION` to `"1.1"`; add `TEMPLATE_VERSION`; add `EVENT_*` constants if any new MCP events are needed (expected: none).
- `skill_bill/shell_content_contract.py` — recognize `## Execution`, require `content.md` sibling, add `MissingContentBodyFileError` (and optionally `InvalidExecutionSectionError`), add `shell_contract_version` + `template_version` parsing.
- `skill_bill/scaffold.py` — write `content.md` alongside SKILL.md; rollback includes `content.md`; payload schema gains optional `content_body` field.
- `skill_bill/scaffold_template.py` — render concise shape descriptors (re-use existing family-aware defaults); render the `## Execution` section; stop emitting the author-owned prose into SKILL.md.
- `skill_bill/scaffold_exceptions.py` (if present; else `scaffold.py`) — register new exception(s).
- `skill_bill/cli.py` — new `edit`, `upgrade`, extended `doctor` subcommands.
- `skill_bill/sync.py` — verify `content.md` is captured by the existing directory-symlink sync; update any per-file paths if they exist.
- `scripts/validate_agent_configs.py` — understand the new contract (sibling file + version stamps + `## Execution`).
- `orchestration/shell-content-contract/PLAYBOOK.md` — document v1.1 changes.
- `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md` — document the new `content_body` payload field.
- `tests/test_scaffold.py`, `tests/test_shell_content_contract.py`, `tests/test_cli.py` — new cases per AC 15.
- `README.md` — new "Authoring a skill" section; before/after example.
- `AGENTS.md` — note SKILL.md is generated; content.md is the author-editable surface.
- `CLAUDE.md` — mirror the AGENTS.md note if the two documents are kept in sync.
- `docs/getting-started-for-teams.md` — update the authoring walkthrough.
- `pyproject.toml` — unlikely to change; verify no new deps are needed.
- Every `platform-packs/*/code-review/*/SKILL.md` — regenerated by migration script.
- Every `platform-packs/*/quality-check/*/SKILL.md` — regenerated by migration script.
- Every `skills/bill-*/SKILL.md` — regenerated by migration script.

Not modified:
- `install.sh` — directory-symlink install path already covers `content.md`; no changes expected unless AC 13's per-file-copy case applies to some agent.
- `skill_bill/mcp_server.py` — tool surface unchanged.
- `skill_bill/stats.py`, `skill_bill/learnings.py`, `skill_bill/triage.py` — telemetry and learnings pipelines unchanged.
- `orchestration/stack-routing/PLAYBOOK.md` — discovery-driven, unaffected.

## Feature flag

N/A. This is tooling and on-disk layout change. There is no runtime user-facing flag.

## Backup / destructive operations

The migration script rewrites every governed SKILL.md in the repo. Precautions:

- **Automatic backup.** Script writes `_migration_backup/<timestamp>/` before the first rewrite.
- **Per-skill rollback.** Validator failure on any individual skill rolls that skill back from an in-memory snapshot; other skills continue.
- **Git is the ultimate rollback.** The PR author commits the migration as a single commit. If something is wrong after merge, `git revert` restores the previous state; a prior backup tag (following the `v0.x-pre-shell-split` precedent from SKILL-14) is recommended: `v1.0-pre-content-split`.
- **Idempotency.** Running the script twice on an already-migrated tree is a no-op by default; `--force` re-runs and overwrites.

No destructive git operations (force-push, reset --hard, branch deletion) are required.

## Validation strategy

Route via `bill-quality-check` (auto-routes to the agent-config checker for this repo):

```bash
.venv/bin/python3 -m unittest discover -s tests
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```

All three must pass after the migration runs.

Additionally, before the repo-wide migration commits:
1. Run `/bill-kotlin-code-review` against a small fixture diff on the pre-migration branch; capture the output.
2. Run the same command against the post-migration branch; diff the output.
3. Confirm the outputs are materially identical (same specialists selected, same risk register shape, findings consistent within LLM variance). Document the comparison in the PR description.

## References

- SKILL-14 spec: `.feature-specs/SKILL-14-code-review-shell-pilot/spec.md` — shell+content architecture parent. This ticket extends the same split to the file layer.
- SKILL-15 spec: `.feature-specs/SKILL-15-new-skill-scaffolder/spec.md` — introduces the scaffolder-owned vs author-owned band distinction this ticket operationalizes.
- SKILL-16 spec: `.feature-specs/SKILL-16-quality-check-shell-pilot/spec.md` — quality-check contract (five H2s) this ticket also updates.
- SKILL-19 spec: `.feature-specs/SKILL-19-first-class-platform-scaffolding/spec.md` — first-class platform authoring flow this ticket builds on.
- SKILL-20 spec: `.feature-specs/SKILL-20-narrow-built-in-packs-to-kotlin-and-kmp/spec.md` — narrows the migration surface; only `kotlin` and `kmp` ship first-party.
- Shell+content contract: `orchestration/shell-content-contract/PLAYBOOK.md`.
- Scaffolder payload contract: `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.
- Scaffolder: `skill_bill/scaffold.py`, `skill_bill/scaffold_template.py`.
- Contract loader: `skill_bill/shell_content_contract.py`.
- Validator: `scripts/validate_agent_configs.py`.
- CLI: `skill_bill/cli.py`.
- Motivation note: observed adoption friction when explaining the system. The rewrite option (registry-owned routing, fully free-form skills) was considered and rejected in favor of this surface-level split; the governance model survives, the authoring surface shrinks.
