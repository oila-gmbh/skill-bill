# SKILL-107 Subtask 6: Governance Doc Reconciliation

**Parent:** [spec.md](spec.md)
**Depends on:** subtask 1 (required — pre-shell pruning and feature-task naming settled), subtask 2 (required — `feature_addon_usage` exists to document), subtask 3 (required — agent/ install exclusion exists before the carve-out sentence describes it).
**Covers:** audit findings 1, 5 (remaining doc claims), 6, 9e, 9f, 11b.

Note: `CLAUDE.md` is a symlink to `AGENTS.md` — edit `AGENTS.md`.

## Scope

- **Finding 1 — Skill Authoring section of `AGENTS.md`:** rewrite the scaffold-kind catalog to exactly `horizontal | platform-pack | add-on`. Remove `platform-override-piloted`, `code-review-area`, `skeleton_mode`, and `specialist_areas` (runtime rejects them: `ScaffoldPolicyConstants.kt:38-52,171` `RetiredScaffoldKindError`; `ScaffoldPayloadMapPlatformPackPolicy.kt:18-29` `InvalidScaffoldPayloadError`). Align the section with `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md` as updated by subtask 1.
- **Finding 5 (doc remainder):** sweep `AGENTS.md` for any remaining claim that `skills/kotlin/` or `skills/kmp/` exist or are protected; align with the post-subtask-1 removal model (packs removable via `--allow-shipped`; `.bill-shared` protected on every axis).
- **Finding 6:** add the iOS pack to the `README.md` platform catalog (line ~69 prose and the ~80-86 list) and to `docs/capabilities.md:34` "today" list including example skill names/signals, matching the pattern used for go/php/python.
- **Finding 9e:** reconcile the authored-sidecar contradiction — `AGENTS.md` says skill dirs contain "only `content.md` plus optional `native-agents/`" while `docs/skill-source-generation.md:219` blesses authored sidecars (e.g. `compose-guidelines.md`). Explicitly sanction authored sidecars in BOTH documents with the same rule (what qualifies, what stays forbidden — the generated-organization files like `patterns.md`/`reference.md` stay banned).
- **Finding 9f:** add a carve-out sentence to the `AGENTS.md` taxonomy for pack `agent/` boundary-memory directories (`platform-packs/<slug>/agent/history.md|decisions.md`): repo-tracked, never installed (behavior implemented in subtask 3).
- **Finding 11b:** soften the `AGENTS.md` runtime-contract recipe wording so `x-coherence-checks` is required "where applicable" (chosen resolution; `feature-task-runtime-phase-output-schema.yaml` legitimately has none — do not add a block there).
- **Post-subtask-2 accuracy:** update the `AGENTS.md` Add-ons and Platform Packs sections to mention `feature_addon_usage` alongside `addon_usage` and the shell contract version `1.2`.

## Acceptance criteria

1. `AGENTS.md` Skill Authoring lists exactly `horizontal`, `platform-pack`, `add-on` as scaffold kinds; `grep -n "platform-override-piloted\|code-review-area\|skeleton_mode\|specialist_areas" AGENTS.md` returns nothing.
2. `grep -n "skills/kotlin\|skills/kmp" AGENTS.md` returns nothing that asserts existence or protection of those directories.
3. `README.md` platform catalog and `docs/capabilities.md` both list the iOS pack with example skill names/signals consistent with the other packs; `grep -c "ios" README.md` is nonzero in the catalog section.
4. `AGENTS.md` and `docs/skill-source-generation.md` state the same authored-sidecar rule; neither claims "only content.md plus native-agents/" without the sidecar carve-out.
5. `AGENTS.md` taxonomy contains the pack `agent/` boundary-memory carve-out (tracked in repo, excluded from installs).
6. `AGENTS.md` recipe step for `x-coherence-checks` reads "where applicable"; `orchestration/contracts/feature-task-runtime-phase-output-schema.yaml` is unchanged.
7. `AGENTS.md` documents `feature_addon_usage` and shell contract version `1.2` consistently with subtask 2's landed state.
8. All four validators pass: `skill-bill validate`, `scripts/validate_agent_configs`, `npx --yes agnix --strict .`, `(cd runtime-kotlin && ./gradlew check)`.

## Non-goals

- No code changes; docs and catalogs only.
- Findings 9a–9d, 11a, 11c belong to subtask 7.
- No restructure of `AGENTS.md` beyond the listed sections; keep diffs surgical.

## Validation strategy

```bash
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
(cd runtime-kotlin && ./gradlew check)
```

Tests: none (doc-only; nothing testable). Gradle check still runs because some tests pin doc-adjacent contract facts.

## Risk notes

- `AGENTS.md` is loaded into every agent session; wording here IS product behavior for prose skills. Keep statements verifiable against the landed subtasks, not aspirational.
- If subtask 3 or 2 shifted during implementation, re-verify the carve-out and `feature_addon_usage` sentences against actual code before writing them.

## Handoff

Run bill-feature-task on `.feature-specs/SKILL-107-audit-fixes/spec_subtask_6_governance-doc-reconciliation.md`.
