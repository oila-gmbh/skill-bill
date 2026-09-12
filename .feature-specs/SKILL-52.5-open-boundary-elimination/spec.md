# SKILL-52.5 — Open-boundary elimination

Created: 2026-09-12
Status: Draft
Issue key: SKILL-52.5
Parent: SKILL-52 — Full hexagonal runtime architecture
Predecessor: SKILL-52.4 — Hexagon leak closure follow-ups

## Intended Outcome

Eliminate the curated raw-map open-boundary allow-list and every grandfathered
public `Map<String, Any?>` (and alias) surface in `runtime-application`,
`runtime-domain`, and `runtime-ports`. Inner runtime layers expose typed DTOs
backed by runtime contracts; wire-shaped maps exist only at adapter boundaries
(CLI, MCP, desktop, infra persistence codecs) behind private mappers.

The ~412 FQNs currently listed in
`RuntimeArchitectureScanConstants.RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` and duplicated
in `runtime-kotlin/ARCHITECTURE.md` are active workflow/feature-task/goal-runner
seams, not dead code. This feature types them away in dependency order, then
deletes the allow-list machinery entirely.

Immediate doc relief: stop embedding the FQN inventory in `ARCHITECTURE.md`
(subtask 1) so agents and humans are not forced to ingest ~1,000 lines of
machine data when reading architecture guidance.

## Problem

SKILL-52.1 introduced the Raw Map Boundary Rule and an allow-list so new leaks
fail loudly while legacy surfaces were retired incrementally. SKILL-52.2–52.4
closed scaffold, review, telemetry application, and several adapter seams, but
the allow-list still carries ~412 entries — mostly durable workflow artifact
codecs and feature-task engine helpers. The list is now:

- A maintenance tax (triple-sync across Kotlin constant, ARCHITECTURE.md markers,
  and SKILL-52.2 inventory via `scripts/sync_raw_map_allowlist.py`).
- A token killer whenever AI or humans read `ARCHITECTURE.md`.
- A false signal that remaining entries are acceptable long-term; most were
  reclassified as `open_extension` rather than finished.

Prior decision (2026-05-29) labeled lifecycle telemetry payloads and
`LifecycleTelemetryService` emit methods as permanent `@OpenBoundaryMap` event
bags. This feature supersedes that decision: MCP/CLI lifecycle events get typed
contract models with adapter-owned `.toPayload()` at the wire edge, matching
the `SystemService.doctor` / `version` pattern from SKILL-52.3 subtask 4.

## Acceptance Criteria

1. `runtime-kotlin/ARCHITECTURE.md` no longer contains inline FQN bullet lists
   between `open-boundary-allowlist` or `skill-52-2-inventory` markers; boundary
   rule prose describes zero-tolerance after subtask 7.
2. Every public raw-map declaration currently on the allow-list is replaced with
   a typed model, typed port contract, or moved to a private adapter/infra mapper
   so it is no longer scanned as a boundary violation.
3. `RuntimeArchitectureScanConstants.RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` and
   `scripts/sync_raw_map_allowlist.py` are deleted; architecture tests hard-fail
   any new public raw-map shape in application/domain/ports without an allow-list
   escape hatch.
4. `@OpenBoundaryMap` is removed from production use (annotation may remain only
   if needed for migration fixtures, with zero production references).
5. Persisted workflow, decomposition, goal, and feature-task records remain
   readable; schema bumps follow the existing quarantine/regenerate policy in
   `runtime-kotlin/ARCHITECTURE.md`.
6. CLI, MCP, and desktop wire payloads stay byte-compatible at documented adapter
   seams or gain explicit contract-version bumps with loud-fail migration.
7. `./gradlew :runtime-core:test --tests 'skillbill.architecture.*'` passes with
   the allow-list deleted and zero public raw-map violations reported.

## Constraints

- Do not weaken hexagonal dependency direction or reintroduce domain imports of
  schema validators beyond what SKILL-52.2 subtask 4 already permits.
- Wire and payload keys stay in `runtime-contracts` `*Keys` objects; no inline
  string literals (`WireVocabularyArchitectureTest`).
- Prefer typed DTO + adapter mapper over `@OpenBoundaryMap` grandfathering.
- Subtask commits must each leave the tree compilable and architecture-test green
  for the remaining allow-list entries until subtask 7 deletes the list.
- Area `agent/decisions.md` records the supersession of the 2026-05-29 permanent
  lifecycle open-boundary decision in subtask 7.

## Non-Goals

- Rewriting infra-fs/sqlite/http adapter internals that are already private and
  not on the allow-list.
- Changing workflow business semantics (phase order, review policy, goal routing).
- Desktop UI model redesign beyond mapper updates required for typed port returns.
- Collapsing hexagonal ports for YAGNI (see SKILL-238).
- Authoring a second inventory ledger in ARCHITECTURE.md after subtask 1 removes
  the current one.

## Validation Strategy

- Subtask 1: architecture doc parity tests updated; allow-list count ratchet
  baseline recorded in test constant.
- Subtasks 2–6: `./gradlew :runtime-core:test --tests 'skillbill.architecture.*'`
  plus module-scoped tests named in each subtask spec.
- Subtask 7: full architecture suite; grep confirms zero production
  `@OpenBoundaryMap` and zero `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` references.

## Delivery Plan

1. Enforcement pivot — dedup ARCHITECTURE.md, single canonical allow-list source,
   count ratchet.
2. Learnings + decomposition ingress — finish the nine entries previously tagged
   `must_type_now` / `postponed_with_reason`, plus decomposition parse helpers.
3. Workflow taskruntime artifact typing — `skillbill.workflow.taskruntime.*`
   codec layer (~98 FQNs).
4. Feature task engine typing — `skillbill.engine.featuretask.*` (~88 FQNs).
5. Goal runner + workflow goal typing — goalrunner and goal workflow models.
6. Application workflow, ports workflow, telemetry, and remainder.
7. Allow-list zero lock — delete list, `@OpenBoundaryMap`, sync script; hard-fail
   rule only.

## Sources

- `runtime-kotlin/ARCHITECTURE.md` — Raw Map Boundary Rule, Open-Boundary Allow-List
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeRawMapArchitectureTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTestSupport.kt`
- `runtime-kotlin/scripts/sync_raw_map_allowlist.py`
- `.feature-specs/done/SKILL-52.2-runtime-boundary-closure/spec.md`
- `.feature-specs/done/SKILL-52.3-runtime-hexagon-leak-closure/spec_subtask_4_application-wire-seam-and-open-boundary-reconciliation.md`
- `runtime-kotlin/agent/decisions.md` — 2026-05-29 lifecycle open-boundary decision
