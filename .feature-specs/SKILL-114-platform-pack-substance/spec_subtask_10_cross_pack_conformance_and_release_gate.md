# SKILL-114 Subtask 10 - Cross-Pack Conformance And Release Gate

## Scope

Close the program only after every maintained pack passes the same substantive
quality gate. Remove temporary metric baselines, run cross-pack coverage and
duplication checks, reconcile documentation/catalog/runtime claims, refresh
installed staging, and record evidence that future maintainers can compare.

Audit the resulting corpus for semantic lane gaps, duplicated universal prose,
framework overfitting, artificial threshold gaming, obsolete commands/APIs,
manifest/native-agent/pointer drift, composition behavior, and quality-check
routing. Review threshold outliers manually even when automated metrics pass.

## Acceptance Criteria

1. All temporary SKILL-114 metric baselines or exemptions are removed; every
   present maintained pack passes the normal repository substance gate.
2. Go, iOS, Kotlin, PHP, Python, Rust, and TypeScript each declare all ten
   areas; KMP proves all-ten effective coverage through Kotlin composition and
   its declared delta lanes.
3. Every pack declares quality-check content and routing tests prove that each
   dominant stack selects its own checker, including KMP without the historical
   Kotlin fallback.
4. The final audit reports at least three platform-specific clusters and ten
   substantive rules for every effective specialist, with zero forbidden
   generic platform-API placeholders.
5. Every pack reports at most 35% shared normalized five-word sequences and
   every corresponding authored-rubric pair reports at most 65% similarity;
   the final metrics are recorded in reusable boundary history.
6. Manual review confirms the thresholds were met through substantive platform
   knowledge and valid shared extraction, not synonym substitution, repeated
   API-name stuffing, or arbitrary rubric fragmentation.
7. README, capabilities, getting-started, source-generation, architecture,
   scaffold payload, schema/contract docs, and current AGENTS guidance describe
   the final coverage, KMP composition/quality behavior, and maintained-pack
   quality gate consistently.
8. Every pack has a high-signal `agent/history.md` SKILL-114 entry; reusable
   cross-pack learnings are recorded at the owning orchestration/runtime
   boundary rather than copied to all histories.
9. Render/install snapshots and provider-neutral native-agent validation pass;
   `./install.sh` completes and no generated wrapper, pointer, installed
   staging, or provider-specific output is committed.
10. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
    `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` all pass
    from a clean maintained worktree.

## Non-Goals

- No release tag or GitHub publication in this subtask.
- No weakening of thresholds to accommodate an unfinished pack.
- No runtime hard-coding of the eight current pack slugs.

## Dependency Notes

Depends on subtasks 2-9. Final program gate.

## Validation Strategy

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
./install.sh
git status --short
```

Also run the SKILL-114 maintained-pack audit in its strict machine-readable and
human-readable modes and retain the human-readable summary in boundary history.

## Next Path

Mark the SKILL-114 decomposition manifest complete when every criterion passes.
