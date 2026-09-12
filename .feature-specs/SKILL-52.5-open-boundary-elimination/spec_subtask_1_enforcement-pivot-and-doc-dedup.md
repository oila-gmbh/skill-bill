# SKILL-52.5 · Subtask 1 — Enforcement pivot and ARCHITECTURE.md dedup

Parent spec: [.feature-specs/SKILL-52.5-open-boundary-elimination/spec.md](./spec.md)
Issue key: SKILL-52.5
Subtask order: 1 of 7
Depends on: none
Branch model: same-branch, commit per subtask

## Purpose

Remove the token-killing FQN inventories from `ARCHITECTURE.md` and collapse
allow-list maintenance to a single machine-readable source, without changing
runtime API shapes yet. Establish a count ratchet so later subtasks must shrink
the list monotonically until subtask 7 deletes it.

## Scope

In scope:

- Delete the inline bullet list between
  `<!-- open-boundary-allowlist:start/end -->` and the duplicate
  `<!-- skill-52-2-inventory:start/end -->` section from
  `runtime-kotlin/ARCHITECTURE.md`. Replace with concise prose pointing at the
  canonical Kotlin constant (until subtask 7 deletes it).
- Update `parseArchitectureAllowList`, `parseSkill522Inventory`, and related
  tests in `RuntimeRawMapArchitectureTest` / `RuntimeArchitectureTestSupport` so
  they read the Kotlin constant (or a dedicated `open-boundary-allowlist.txt`
  under `runtime-core/src/test/resources/` if that simplifies parity) instead of
  parsing `ARCHITECTURE.md`.
- Update `scripts/sync_raw_map_allowlist.py` to stop writing ARCHITECTURE.md
  sections; sync only the canonical source.
- Add a ratchet test asserting `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST.size` equals the
  measured baseline (~412 at spec authoring time) so subtasks 2–6 must decrease
  the count; subtask 7 removes the ratchet with the constant.
- Fix any ARCHITECTURE.md prose that still says the doc block is the canonical
  enumeration.

Out of scope:

- Typing any allow-list entry.
- Deleting `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST`.
- Changing CLI/MCP/desktop wire output.

## Acceptance Criteria

1. `ARCHITECTURE.md` contains no FQN bullet lists for the open-boundary allow-list
   or SKILL-52.2 inventory (markers removed or reduced to a one-line pointer).
2. Architecture parity tests no longer parse FQN lists from `ARCHITECTURE.md`.
3. `sync_raw_map_allowlist.py` no longer mutates `ARCHITECTURE.md`.
4. A ratchet test records the current allow-list size and fails if it increases.
5. `./gradlew :runtime-core:test --tests 'skillbill.architecture.*'` passes.

## Non-Goals

- Reducing allow-list entry count (starts in subtask 2).
- Rewriting boundary rule doctrine beyond deduping the inventory location.

## Dependency Notes

- None. First executable unit; base branch is `main`.

## Validation Strategy

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
! rg -n 'open-boundary-allowlist:start' runtime-kotlin/ARCHITECTURE.md | rg '`skillbill\.' && exit 1 || true
! rg -n 'skill-52-2-inventory:start' runtime-kotlin/ARCHITECTURE.md | rg '`skillbill\.' && exit 1 || true
```

## Next Path

`.feature-specs/SKILL-52.5-open-boundary-elimination/spec_subtask_2_learnings-and-decomposition-ingress.md`
