# SKILL-181 · Subtask 1 — Split recoverability into validity and freshness

## Scope

Replace the single equality gate in
`GoalPlanningSweep.recoverableProvenance`
(`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/goalrunner/GoalPlanningSweep.kt`)
with an explicit **valid** vs **fresh** classification.

**Valid** requires all of:

- saved `decompositionManifestHash` matches current
- saved `phaseOutputContractId` matches current
- saved planning-packet `parent_spec` is present and `sha256HexUtf8(savedParentSpec) == saved.parentSpecHash`
- saved `preplan_payload` matches `payload_sha256`
- every id in the saved payload's `selected_boundary_headings` still resolves in the freshly parsed
  heading catalog (model-free catalog parse already used elsewhere in the sweep)

**Fresh** requires `GoalPlanningSpecCanonicalization.canonical(saved) == canonical(current)`.

Outcomes for this subtask only:

- Valid and fresh → reuse the saved provenance (today's happy path).
- Valid but not fresh → do **not** call `incompatibleProvenance`; surface a typed stale-valid signal
  the next subtask will consume (or a local sealed result the sweep can branch on). Do not yet
  re-run preplan.
- Not valid → keep today's loud stop at `PHASE_PREPLAN` / subtask `0`.

Do not change cascade behaviour, CLI replan, exit codes, or status `planning_reason` here.

## Acceptance Criteria

1. When manifest hash, schema id, parent-spec self-hash, payload sha, heading resolution, and
   canonical parent-spec equality all hold, the sweep reuses the saved shared preplan exactly as
   today.
2. When everything in criterion 1 holds except canonical parent-spec equality, the sweep does not
   stop with the incompatible-provenance message and does not discard the saved preplan.
3. When any validity clause fails (manifest, schema, self-hash, payload sha, or an unresolved
   selected heading id), the sweep still stops loudly at preplan / subtask 0.
4. Heading-id resolution uses the freshly parsed catalog and does not invoke a model.
5. Existing unit coverage around recoverable provenance is updated so the stale-valid path is
   distinguishable from both reuse and hard stop.

## Non-Goals

- In-run preplan refresh (subtask 2).
- Cascade filtering for terminal subtasks (subtask 3).
- Exit-code or stop-message remapping (subtask 4).
- Broadening body canonicalization.

## Dependencies

None.

## Validation Strategy

- Unit-test the three outcomes (reuse / stale-valid / invalid) against fixtures that mutate one clause
  at a time.
- Fixture: body-only parent-spec edit with otherwise identical hashes → stale-valid, not stop.
- Fixture: selected heading removed from the catalog → invalid stop.
- Fixture: payload bytes that do not match `payload_sha256` → invalid stop.
- Build and test the affected modules.

## Next Path

Subtask 2 consumes the stale-valid signal and refreshes the preplan in-run.
