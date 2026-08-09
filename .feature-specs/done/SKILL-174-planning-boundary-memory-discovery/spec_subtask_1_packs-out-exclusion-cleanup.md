# SKILL-174 Subtask 1 - Packs out of planning; exclusion cleanup

Parent spec: [.feature-specs/SKILL-174-planning-boundary-memory-discovery/spec.md](spec.md)
Issue key: SKILL-174

## Scope

Make platform packs review-only for planning/preplanning, introduce the exclusion
list, delete excluded `agent/` trees (including every `platform-packs/*/agent/`),
and stop discovery from reading under excluded roots.

Today `FileSystemGoalPlanningContextDiscovery` still walks
`platform-packs/*/agent/history.md` then `agent/decisions.md` into
`boundary_memory`. Pack `platform.yaml` is already gone (SKILL-172); this subtask
removes the remaining pack boundary-memory path and hardens the contract so packs
cannot re-enter planning via stray `agent/` folders.

Primary surfaces:

- `runtime-infra-fs/.../FileSystemGoalPlanningContextDiscovery.kt` and tests
- checked-in exclusion list (new contract/config — explicit repo-owned prefixes)
- delete `platform-packs/*/agent/` (and any other excluded `agent/` trees found)
- docs/skills that still claim pack history/decisions or `platform.yaml` are
  planning discovery inputs (at least `skills/bill-feature-goal/content.md`,
  `AGENTS.md` taxonomy wording if it still implies pack `agent/` is planning
  memory)

## Acceptance Criteria

1. Goal planning/preplanning discovery never reads any path under `platform-packs/`
   into `boundary_memory`, `validation_guidance`, or any other planning packet
   field.
2. A checked-in exclusion list includes at least the full `platform-packs/` root
   and is applied as a discovery gate (prefix deny), not only as documentation.
3. Every existing `agent/` directory under exclusion-list roots is deleted from the
   repository, including all current `platform-packs/*/agent/` trees
   (`history.md`, `decisions.md`, and the directories themselves).
4. Tests assert that a fixture with `platform-packs/<pack>/agent/history.md` (and
   optionally `decisions.md`) contributes nothing to planning discovery output.
5. Writers/docs are updated so platform packs are described as review-phase only
   for this concern; planning discovery text no longer lists pack `platform.yaml`
   or pack `agent/history.md` / `agent/decisions.md` as inputs.
6. `bill-boundary-history` / `bill-boundary-decisions` guidance (or a shared
   pointer) forbids creating `agent/` under exclusion-list roots.
7. `./gradlew build -x sourcesJar` and `detekt` pass; discovery unit tests covering
   the old pack-history fixture are retargeted or replaced.

## Non-Goals

- No intelligent heading index or bodies-on-demand protocol (subtask 2).
- No change to review/quality-check pack composition or install of packs.
- No rename/removal of unrelated packet fields beyond what is required to stop
  pack paths appearing in planning memory.

## Dependency Notes

Standalone relative to subtask 2's indexer, but should land first so subtask 2
indexes only non-excluded trees.

## Validation Strategy

1. Unit test: repo fixture with pack `agent/history.md` + module `agent/history.md`
   → planning discovery includes the module file (or its catalog in later shape)
   and excludes every pack path.
2. Repository check: no `platform-packs/**/agent/**` remains after cleanup.
3. Grep/docs assertion or targeted content test that planning discovery docs no
   longer advertise pack history/decisions as inputs.
4. `(cd runtime-kotlin && ./gradlew :runtime-infra-fs:test :runtime-application:test detekt)`.
5. `(cd runtime-kotlin && ./gradlew build -x sourcesJar)`.

## Next Path

Subtask 2 — replace dumb prefix dumping with programmatic heading walk and
selected-body delivery.
