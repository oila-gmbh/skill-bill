# SKILL-174 Subtask 2 - Intelligent heading walk for history/decisions

Parent spec: [.feature-specs/SKILL-174-planning-boundary-memory-discovery/spec.md](spec.md)
Issue key: SKILL-174

## Scope

Replace dumb first-N-byte prefix dumps of eligible `agent/history.md` and
`agent/decisions.md` with a runtime-owned intelligent walk:

1. **Programmatic index (no AI, no indexing tokens).** Parse each eligible file
   into a heading list and/or a `heading → content` map. Heading structure comes
   from the governed entry formats (`bill-boundary-history` /
   `bill-boundary-decisions`), newest-first where that is the file convention.
2. **Headings first.** Planning/preplanning receives the heading catalog (cheap).
   The agent walks headings and stops considering further headings once they are
   irrelevant to the current scope — relevance is the agent's judgment, not the
   indexer's.
3. **Bodies on demand.** After the agent selects relevant headings, runtime
   supplies only the content under those headings (deterministic map lookup).
   Unselected bodies never enter model context.

Primary surfaces:

- `FileSystemGoalPlanningContextDiscovery` (or its successor indexer/retriever)
- goal planning packet / prompt composition (`GoalPlanningSweep`,
  `GoalPlanningContextPromptFormatter`, shared packet versioning if wire shape
  changes)
- tests for parse, catalog bounds, selection → body resolution, and proof that
  unselected bodies are absent from prompts

### Authoritative walk detail (from design discussion)

All indexing is programmatic — not AI-involved, no tokens wasted on indexing.

- Headings can be programmatically parsed to a list, or associated as a map
  `heading → content`.
- The model reads headings first.
- When it decides which headings are relevant, it gets those bodies only.
- Do not dump full files or irrelevant bodies into the prompt up front.

## Acceptance Criteria

1. Eligible history/decisions discovery no longer uses dumb first-N-byte file
   prefixes as the planning payload for those files.
2. Runtime builds a programmatic heading catalog (and may materialize
   `heading → content`) for eligible non-excluded files with zero model calls in
   the indexer.
3. History parsing recognizes the `bill-boundary-history` entry heading form
   (`## [<date>] <feature-name>`); decisions parsing recognizes the
   `bill-boundary-decisions` entry heading form (`## [<date>] <title>`). Malformed
   regions do not invent headings.
4. Initial planning/preplanning model context carries headings (plus stable
   heading identities / source paths as needed), not full entry bodies.
5. After heading selection, runtime resolves and delivers bodies only for the
   selected headings; a test proves an unselected entry's body text does not
   appear in the composed planning prompt/context.
6. Catalog and resolved-body delivery remain bounded (explicit caps); budget
   exhaustion is deterministic and tested.
7. If the shared planning packet wire shape for `boundary_memory` changes,
   packet `VERSION` / migrate-on-read rules are updated so resume loud-fails or
   migrates cleanly — no silent drift.
8. `./gradlew build -x sourcesJar` and `detekt` pass.

## Non-Goals

- No AI relevance model inside the indexer ("stop when irrelevant" stays an
  agent behavior over the heading list).
- No recreation of pack `agent/` trees; exclusion from subtask 1 remains in force.
- No change to review-phase pack routing.
- No mandatory rewrite of every historical file in the monorepo to a new format
  beyond what the parser requires to index conforming entries.

## Dependency Notes

Depends on subtask 1 (exclusion list + packs removed) so the indexer never
treats `platform-packs/**/agent/**` as eligible.

## Validation Strategy

1. Parser unit tests: multi-entry history and decisions fixtures → expected
   heading list / map keys; malformed block ignored or loud-failed per chosen
   rule.
2. Prompt/composition test: catalog-only initial context; after selecting a
   subset of heading ids, only those bodies appear.
3. Regression: excluded pack paths still absent from catalogs.
4. Packet migrate/validate tests if `boundary_memory` shape or packet version
   changes.
5. `(cd runtime-kotlin && ./gradlew :runtime-infra-fs:test :runtime-application:test detekt)`.
6. `(cd runtime-kotlin && ./gradlew build -x sourcesJar)`.

## Next Path

Done for SKILL-174. Optional follow-up: touched-path allowlisting among
non-excluded module `agent/` trees when many catalogs compete.
