# SKILL-107 Subtask 7: Pointer/Playbook Docs and Nitpicks

**Parent:** [spec.md](spec.md)
**Depends on:** subtask 2 (required — pointer generation docs must describe the post-`feature_addon_usage` model and the renamed `feature-task.yaml`), subtask 5 (required — 9a documents peak-hours-warner in its genericized form).
**Covers:** audit findings 9a, 9b, 9c, 9d, 11a, 11c.

## Scope

- **9a — third pointer family:** document the skill-class ceremony-pointer mechanism (`orchestration/skill-classes/*.yaml` matchers + `ceremony_lines`, example: `peak-hours-warner` via `feature-task.yaml` / `feature-launch-warning.yaml`) in `docs/skill-source-generation.md` (~lines 330-352 region) as a third pointer family alongside the existing two, and add ceremony pointers to the forbidden-generated-files lists in that doc (the `AGENTS.md` forbidden list already names ceremony pointers via `shell-ceremony.md`/`telemetry-contract.md`; extend only if a family-level statement is missing).
- **9b — delegation tiers:** in the review-delegation PLAYBOOK (`orchestration/shell-content-contract/PLAYBOOK.md` delegation section, currently covering copilot/claude/codex), add explicit one-line entries stating opencode and junie delegated review is intentionally unsupported (tiered support), so absence reads as a decision rather than an omission.
- **9c — wrong module citation:** fix `runtime-core` → actual location for `loadQualityCheckContent` in `orchestration/skill-classes/quality-check-shell.yaml:20,41` and `orchestration/shell-content-contract/PLAYBOOK.md:241`; the real seam is `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/platformpack/ShellContentLoader.kt:254` (`skillbill.scaffold.platformpack.ShellContentLoader`) — cite module `runtime-infra-fs`.
- **9d — codex lane specialist domains:** `skills/bill-code-review/content.md:113-131` hard-codes 6 specialist domains, dropping `api-contracts`, `persistence`, `ui`, `ux-accessibility`. Either make the codex lane route via the routed pack's declared areas (preferred if expressible in governed prose) or explicitly document the 6-domain approximation and why the other 4 areas are excluded. Pick one; leave no silent drop.
- **11a — .gitignore defense-in-depth:** add ignore patterns for generated outputs: `skills/**/SKILL.md`, provider agent output dirs (`**/claude-agents/`, `**/codex-agents/`, `**/opencode-agents/`, `**/junie-agents/`), and generated support pointers — WITHOUT ignoring any currently tracked file (verify with `git status --ignored` and `git ls-files -i -c --exclude-standard`).
- **11c — install-plan schema description:** `orchestration/contracts/install-plan-schema.yaml` `skills[]` description → "all standalone-installable skills".

## Acceptance criteria

1. `docs/skill-source-generation.md` describes the ceremony-pointer family: skill-class YAML location, matcher semantics, `ceremony_lines` injection, generated-not-committed status, with `peak-hours-warner` as the worked example reflecting its post-subtask-5 operator-config form.
2. The forbidden-generated-files guidance in `docs/skill-source-generation.md` names ceremony pointers as a family (not just two filenames).
3. The delegation PLAYBOOK contains explicit "intentionally unsupported" one-liners for opencode and junie delegated review.
4. `grep -n "runtime-core" orchestration/skill-classes/quality-check-shell.yaml orchestration/shell-content-contract/PLAYBOOK.md` returns nothing for the `loadQualityCheckContent` citations; both cite `runtime-infra-fs` / `skillbill.scaffold.platformpack.ShellContentLoader`.
5. `skills/bill-code-review/content.md` codex lane either derives specialist domains from the routed pack's declared areas or carries an explicit, reasoned approximation note covering all 10 approved areas; no undocumented 4-area drop remains.
6. `.gitignore` gains the defense-in-depth patterns; `git ls-files -i -c --exclude-standard` is empty (no tracked file newly ignored) and `git status` is clean of generated artifacts after `./install.sh`.
7. `orchestration/contracts/install-plan-schema.yaml` `skills[]` description reads "all standalone-installable skills"; no schema structural change and no contract version bump.
8. All four validators pass: `skill-bill validate`, `scripts/validate_agent_configs`, `npx --yes agnix --strict .`, `(cd runtime-kotlin && ./gradlew check)`.

## Non-goals

- No changes to `feature-task-runtime-phase-output-schema.yaml` (subtask 6 resolved 11b by softening the recipe wording).
- No renderer/runtime behavior changes; if 9d's preferred routing option would require runtime changes, take the documented-approximation option instead.
- No `AGENTS.md` edits (subtask 6 owns them) except a forbidden-list family statement if criterion 2 demands parity — keep it to one sentence if so.

## Validation strategy

```bash
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
(cd runtime-kotlin && ./gradlew check)
git ls-files -i -c --exclude-standard
./install.sh
git status --short
```

Tests: none (doc, prose, and repo-config only; nothing runtime-testable). `skill-bill validate` gates the `content.md` edit; the gitignore checks above are the acceptance gate for 11a.

## Risk notes

- 11a can silently ignore tracked legacy files if patterns are too broad; the `git ls-files -i -c` check is mandatory before commit.
- 9d touches a governed `content.md`; use `skill-bill fill`/`edit` CLI flows per AGENTS.md authoring rules rather than raw edits if validators require it, and run `./install.sh` after.

## Handoff

Run bill-feature-task on `.feature-specs/SKILL-107-audit-fixes/spec_subtask_7_pointer-and-playbook-docs.md`.
