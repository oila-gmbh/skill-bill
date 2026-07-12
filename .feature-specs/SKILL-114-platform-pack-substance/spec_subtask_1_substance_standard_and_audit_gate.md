# SKILL-114 Subtask 1 - Substance Standard And Audit Gate

## Scope

Define and enforce the maintained-pack substance bar before editing individual
packs. The standard complements SKILL-112's structural conformance: it measures
effective coverage, enforceable platform knowledge, placeholder use,
quality-check depth, and cross-pack duplication.

Author the standard under `orchestration/review-orchestrator/`. Specify the
versioned normalization algorithm used for authored-content metrics, including
frontmatter/name normalization, excluded shared/generated text, five-word
shingling, corresponding-role matching, and deterministic rounding.

Add a repository audit implementation and tests that dynamically scan present
maintained packs. The report must show per pack and per specialist:

- physical and inherited effective areas
- substantive enforceable-rule count
- platform-specific failure-mode cluster count
- concrete mechanism/API/toolchain evidence
- forbidden generic placeholders
- quality-check presence and required sections
- shared-shingle percentage and highest corresponding-rubric similarity

Keep this a maintained-repository quality gate, not a loader restriction on
third-party packs. Loud failures name the pack, area, file, measured value, and
required threshold. Add rejection fixtures for a thin specialist, placeholder
rubric, missing effective area, shallow quality checker, duplicated pair, and
composition that falsely claims inherited coverage.

Move genuinely universal evidence, severity, output, scoping, attribution,
deduplication, and fix-contract rules into generated shared orchestration when
they are currently repeated in pack prose. Generate or parity-protect the
delegated `specialist-contract.md` subset so it cannot drift from its canonical
source. Do not centralize language/framework/backend behavior.

## Acceptance Criteria

1. A governed pack-substance standard defines effective area coverage, three
   failure-mode clusters, ten substantive enforceable rules, concrete platform
   evidence, placeholder rejection, quality-check depth, and duplication
   thresholds as independently testable requirements.
2. One versioned, deterministic authored-content audit reports every metric in
   the Scope and uses the same implementation in tests and maintainer
   validation; generated wrappers and shared pointer targets are excluded.
3. The audit fails a maintained pack above 35% shared normalized five-word
   sequences or a corresponding authored-rubric pair above 65% similarity,
   naming both files for pair failures.
4. Rejection fixtures cover thin, placeholder-driven, under-covered,
   shallow-quality-check, duplicated, and invalid-composition cases; an
   acceptance fixture proves a concise but substantive compositional overlay.
5. Universal review rules removed from pack-local templates are present in a
   shared generated contract consumed by affected baseline and specialist
   workers, with tests proving rendered behavior is not lost.
6. `specialist-contract.md` is generated from or exact-parity-tested against
   the canonical shared sections rather than relying only on a prose “keep in
   sync” instruction.
7. The scaffolder emits substantive TODO-bearing prompts that require concrete
   mechanisms and failure modes, but generated starter text cannot itself pass
   the full maintained-pack gate until authors fill it.
8. Existing packs may be represented as explicit temporary test baselines tied
   to SKILL-114 subtasks 2-9; no permanent exemption suppresses a failed metric.
9. `skill-bill validate` and `(cd runtime-kotlin && ./gradlew check)` pass.

## Non-Goals

- No platform-pack content overhaul in this subtask.
- No cross-platform backend content module.
- No runtime refusal to load structurally valid third-party packs.
- No hard-coded routing behavior for maintained platform slugs.

## Dependency Notes

First subtask; depends on nothing. Blocks every pack elevation and the final
gate.

## Validation Strategy

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Next Path

Proceed to any of subtasks 2-7 or 9; subtask 8 waits for subtask 7.
