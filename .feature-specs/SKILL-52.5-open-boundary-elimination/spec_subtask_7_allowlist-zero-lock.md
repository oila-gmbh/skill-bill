# SKILL-52.5 · Subtask 7 — Allow-list zero lock

Parent spec: [.feature-specs/SKILL-52.5-open-boundary-elimination/spec.md](./spec.md)
Issue key: SKILL-52.5
Subtask order: 7 of 7
Depends on: subtask 6
Branch model: same-branch, commit per subtask

## Purpose

Delete the allow-list machinery and lock the runtime to zero public raw-map
surfaces in inner layers. No grandfather list, no `@OpenBoundaryMap` escape hatch.

## Scope

In scope:

- Delete `RuntimeArchitectureScanConstants.RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` and
  all tests that parse or sync it (`parseArchitectureAllowList`,
  `parseSkill522Inventory`, allow-list parity tests, count ratchet).
- Delete `runtime-kotlin/scripts/sync_raw_map_allowlist.py`.
- Update `RuntimeRawMapArchitectureTest` so any public raw-map violation in
  application/domain/ports fails with no exemption path.
- Remove `@OpenBoundaryMap` from production sources; delete or retain the
  annotation class only if tests need it — zero production references required.
- Update `runtime-kotlin/ARCHITECTURE.md` Raw Map Boundary Rule to state
  zero-tolerance (no curated exceptions list).
- Record in `runtime-kotlin/agent/decisions.md` the supersession of the
  2026-05-29 lifecycle permanent open-boundary decision and the elimination of
  the SKILL-52.1 allow-list governance model.
- Update `OpenBoundaryMap.kt` KDoc or remove the file if unused.

Out of scope:

- New typing work — subtask 6 must have cleared the list.
- Infra-private serializers that were never on the allow-list.

## Acceptance Criteria

1. `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` and `sync_raw_map_allowlist.py` are absent.
2. `./gradlew :runtime-core:test --tests 'skillbill.architecture.*'` passes with
   zero public raw-map violations and no allow-list parsing.
3. Production code has zero `@OpenBoundaryMap` references.
4. `ARCHITECTURE.md` documents zero-tolerance with no FQN inventory blocks.
5. `agent/decisions.md` records allow-list elimination and lifecycle decision
   supersession.

## Non-Goals

- Further API changes beyond deleting escape hatches.
- Desktop hexagon work (SKILL-52.4).

## Dependency Notes

- Subtask 6 must leave zero (or fixable straggler) allow-list entries.

## Validation Strategy

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
! rg -n 'RAW_MAP_OPEN_BOUNDARY_ALLOWLIST' runtime-kotlin && exit 1 || true
! rg -n '@OpenBoundaryMap' runtime-kotlin --glob '!**/test/**' && exit 1 || true
test ! -e runtime-kotlin/scripts/sync_raw_map_allowlist.py
```

## Next Path

Feature complete — open PR for `feat/SKILL-52.5-open-boundary-elimination`.
