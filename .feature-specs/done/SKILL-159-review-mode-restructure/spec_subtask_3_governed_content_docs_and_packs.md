# SKILL-159 Subtask 3 — Governed content, docs, and packs for the new mode set

## Scope

Rewrite every governed content surface so the product describes only the three
restructured modes, and remove dead SKILL-145 planning artifacts.

- `orchestration/review-delegation/PLAYBOOK.md`: either retire it (and every
  `platform.yaml` support-pointer entry plus `skill-classes/code-review-orchestrator.yaml`
  and `code-review-shell.yaml` references) or rewrite it as the contract for
  the renamed `delegated` fan-out mode; pointer names, manifest declarations,
  and renderer output must stay coherent whichever is chosen.
- `orchestration/skill-classes/code-review-shell.yaml` mode prose (the
  authoritative mode token text) and `code-review-orchestrator.yaml`.
- `orchestration/review-orchestrator/PLAYBOOK.md` and
  `specialist-contract.md`: specialist contract now backs `delegated`;
  remove external-process/worker-process language; keep the marked blocks that
  maintainer parity tests pin, updating tests in lockstep if wording moves.
- Skills content: `skills/bill-code-review/content.md` (mode semantics; add
  the single-prompt inline authored content from subtask 2's seam),
  `bill-feature/content.md`, `bill-feature-goal/content.md`,
  `bill-feature-task/content.md`, `bill-feature-task-runtime/content.md`,
  `bill-feature-task-prose/content.md` (+ `native-agents/agents.yaml`),
  `bill-feature-task-subtask-runner/content.md`.
- Platform packs: remove delegated-worker capacity/wave wording from
  `platform-packs/{go,kotlin,php,python,rust,typescript}/code-review/*/content.md`.
- Docs: `docs/review-telemetry.md` (drop provider-promotion/canary sections,
  keep mode telemetry under new names), `docs/capabilities.md`,
  `docs/getting-started.md`, `docs/getting-started-for-teams.md`,
  `docs/internal-skills-architecture.md`, `docs/skill-source-generation.md`,
  `README.md`.
- `docs/delegated-review/`: keep all artifacts; add a short preface to
  `decision.md` stating SKILL-159 removed the external subsystem and renamed
  the modes, so the historical text's vocabulary is not read as current.
- Delete the nine `.feature-specs/SKILL-145-delegated-code-review-reliability/spec_followup_*.md`
  files.
- Governed-content token tests (`InlineReviewDepthTierGovernedContentTest`,
  `FeatureSpecSkillWiringContractTest`, `FeatureFamilyRenderingIntegrationTest`)
  updated with the content they pin.
- Run `./install.sh` so local agent installs pick up the new staging hash.

## Acceptance Criteria

1. No installable governed content (skills, packs, skill-class YAMLs, generated pointers) references external-process delegated review, provider CLIs as review workers, capability matrices, canaries, or promotion gates.
2. All mode documentation across skills, packs, orchestration, docs, and README describes `delegated` as the default fan-out review, `inline` as the single-prompt review, and `auto` as first-pass-delegated / follow-up-inline.
3. The review-delegation support-pointer surface is coherent: manifests, skill-class YAMLs, renderer output, and installed staging agree on whether the pointer exists and what it points to; `skill-bill validate` and `scripts/validate_agent_configs` pass.
4. `bill-code-review` content defines the authored single-prompt inline review over the child-owned delta, producing findings in the existing F-XXX severity format.
5. `docs/delegated-review/decision.md` carries the SKILL-159 removal preface; the nine SKILL-145 `spec_followup_*.md` files are deleted; no live path references them.
6. Governed-content pinning tests pass against the rewritten content; `(cd runtime-kotlin && ./gradlew check)` and `npx --yes agnix --strict .` pass.
7. `./install.sh` completes and the installed catalog shows the new mode vocabulary.

## Non-Goals

- Runtime behavior changes (subtasks 1–2 own those).
- Rewriting the historical bodies of `docs/delegated-review/` beyond the preface.
- Pack history/decisions files (`agent/history.md`, `agent/decisions.md`) remain historical records.

## Dependency Notes

Depends on subtask 2 (final mode vocabulary and inline seam).

## Validation Strategy

`skill-bill validate`, `scripts/validate_agent_configs`,
`npx --yes agnix --strict .`, `(cd runtime-kotlin && ./gradlew check)` for the
content-pinning tests, and `./install.sh`.

## Next Path

Goal completion: single parent PR for SKILL-159.
