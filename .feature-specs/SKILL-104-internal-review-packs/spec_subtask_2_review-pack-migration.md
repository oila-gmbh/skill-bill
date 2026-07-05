---
status: Pending
---

# SKILL-104 Subtask 2 - Review-pack migration and call-site rewrite

Parent spec: [spec.md](spec.md)
Issue key: SKILL-104

Read the parent spec's **Pinned Decisions** before starting. PD2, PD4, PD5, and PD7 bind this subtask directly.

## Scope

Flip all 34 review-pack skills to internal and rewrite every call site that resolves one of them standalone to the sidecar file-read contract. Prose/frontmatter work, plus the composition-instruction renderer only if the generated KMP-baseline text names a standalone skill path. Zero mechanism changes — subtask 1 owns those.

### Step 1: Frontmatter (34 files)

Add `internal-for: bill-code-review` to the frontmatter of each `content.md` (PD2 — flat, all parented to `bill-code-review`, including the 4 stack entries):

- `platform-packs/ios/code-review/bill-ios-code-review/content.md` + the 10 `bill-ios-code-review-{api-contracts,architecture,performance,persistence,platform-correctness,reliability,security,testing,ui,ux-accessibility}/content.md`
- `platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md` + the 8 `bill-kotlin-code-review-{api-contracts,architecture,performance,persistence,platform-correctness,reliability,security,testing}/content.md`
- `platform-packs/kmp/code-review/bill-kmp-code-review/content.md` + the 2 `bill-kmp-code-review-{ui,ux-accessibility}/content.md`
- `platform-packs/php/code-review/bill-php-code-review/content.md` + the 10 `bill-php-code-review-{api-contracts,architecture,performance,persistence,platform-correctness,reliability,security,testing,ui,ux-accessibility}/content.md`

### Step 2: Call-site inventory and rewrite (PD5)

Build the inventory first, then rewrite each site. Known sites from spec preparation — treat this as a starting inventory, not an exhaustive one; sweep before editing (see Validation):

1. `orchestration/review-delegation/PLAYBOOK.md` — "read the delegated skill file as the primary rubric" (Claude Code section line 43, Codex section line 53, Copilot section line 32): re-point to the sibling sidecar `<skill-name>.md` inside `bill-code-review`'s installed directory. Keep the delegation model itself untouched.
2. The routed-dispatch prose in `bill-code-review`'s rendered shell (Execution Contract: "pass along ... the delegated skill name and rendered runtime instructions"): wherever the shell or its source templates resolve the routed pack's instructions, resolve from the entry sidecar `bill-<slug>-code-review.md`. Locate the authoring source of these shell sections (shell renderer / `orchestration/` contracts) rather than editing installed output.
3. Stack orchestrator rubric reads — e.g. `bill-kotlin-code-review/content.md` Step 6 ("Read each specialist skill file as the primary rubric for that lane"), and equivalents in the iOS/PHP/KMP entries: specialist rubric = sibling sidecar file.
4. KMP baseline layer — the generated Review Composition instructions (rendered from `platform-packs/kmp/platform.yaml` by the scaffold renderer; see `PlatformPackCompositionTest`, `ScaffoldBaselineLayerPayloadTest`): if the generated text directs the runtime to a standalone `bill-kotlin-code-review` skill location, change the renderer text to the sibling sidecar read. If it only names the skill (identity), leave it (PD4).
5. `skills/bill-code-review/content.md` lane-2 instructions — the Claude lane keeps `/bill-code-review` (listed, PD5); verify no lane text tells an agent to invoke a hidden skill as a slash command.
6. `skills/bill-code-review-parallel/content.md` and `skills/bill-code-check/content.md` — sweep for standalone resolutions of the 34; rewrite matches the same contract.
7. Wiring/contract tests that assert Skill-tool or standalone-path dispatch of review skills (analogue of SKILL-102's `FeatureSpecSkillWiringContractTest` miss) — update assertions to the sidecar contract.

### Mentions that are NOT call sites — do not touch

- Specialist-selection tables and signal→specialist mappings inside stack entries (identity strings for spawn targets and sidecar resolution) (PD4).
- `routed_skill = bill-<slug>-code-review` in `orchestration/stack-routing/PLAYBOOK.md` and the shell Routing Rules (frozen contract) (PD4).
- `native-agents/agents.yaml` agent names (PD6).
- Telemetry payload values, `review_stats`/`import_review`/`triage_findings` metadata, and learnings keys (PD4).
- The lane-2 `/bill-code-review` slash-command instruction (still listed).

## Acceptance Criteria

1. All 34 `content.md` files carry `internal-for: bill-code-review` and nothing else changed in their frontmatter (PD2).
2. A rendered-output sweep (staged wrappers + installed prose, not just repo sources) finds no instruction to resolve any of the 34 via the Skill tool, a slash command, or a standalone skills-dir path (PD5).
3. Every rewritten call site reads the sibling sidecar `<skill-name>.md` co-located with `bill-code-review`'s `SKILL.md`, matching the wording contract established by SKILL-102's file-read dispatch sentences (PD5).
4. Identity strings enumerated under "Mentions that are NOT call sites" are byte-unchanged (PD4).
5. The KMP baseline layer resolves through the sibling sidecar (renderer or prose, wherever the text actually lives), and the composition tests cover the new wording.
6. `skill-bill validate` passes with all 34 opted in — including the extended sidecar-reference validation from subtask 1.
7. Maintainer validation passes: `./gradlew check`, `skill-bill validate`, `agnix --strict`, `scripts/validate_agent_configs`.

## Non-Goals

Docs/README/getting-started updates (subtask 3); any Kotlin mechanism change beyond the composition-renderer text in Step 2.4; quality-check skills (PD7).

## Dependency Notes

Depends on subtask 1 (the mechanism must accept and stage pack internals before any skill opts in, or `skill-bill validate` and install break on this branch).

## Validation Strategy

Before editing: grep sweep for all 34 names across `skills/`, `platform-packs/`, `orchestration/`, `runtime-kotlin/` prose/templates, and the *rendered* staging output of a from-source install — classify every hit as call site vs identity mention. SKILL-102's post-merge fix was a missed call site whose phrasing didn't match the grep; sweep by skill name, not by phrasing. After editing: re-render, re-sweep, run the full maintainer validation set, and scratch-install to confirm sidecars resolve at the paths the rewritten prose names.

## Next Path

[Subtask 3 — Docs, surface reconciliation, verification, records](spec_subtask_3_docs-surface-verification.md)
