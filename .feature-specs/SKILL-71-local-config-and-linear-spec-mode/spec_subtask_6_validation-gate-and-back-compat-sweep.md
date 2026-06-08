---
status: Complete
---

# SKILL-71 Subtask 6 - Validation Gate and Back-Compatibility Sweep

Parent spec: [.feature-specs/SKILL-71-local-config-and-linear-spec-mode/spec.md](./spec.md)
Issue key: SKILL-71

## Scope

Closing gate. Run the full maintainer command set and prove the contract bump and
the new modes did not regress existing behavior.

- Run the full maintainer validation suite and confirm every command passes.
- Back-compatibility sweep:
  - load a representative existing `0.2`-era manifest (no `spec_source`) under the
    bumped contract and confirm the runtime default applies `local` and
    validation passes;
  - run a local-mode (`spec_type: local` / no config) prep + feature-task
    end-to-end and confirm specs are still committed and nothing is deleted —
    byte-for-byte today's behavior.
- Confirm no review/audit/validation gate was weakened to land the feature.
- Confirm `additionalProperties: false` still holds and the two new fields are the
  only schema additions.

## Acceptance Criteria

1. All maintainer commands pass:
   - `skill-bill validate`
   - `(cd runtime-kotlin && ./gradlew check)`
   - `npx --yes agnix --strict .`
   - `scripts/validate_agent_configs`
2. A `0.2`-era manifest loads under the bumped contract with `spec_source`
   defaulted to `local` and validates without error.
3. A local-mode prep + feature-task run is unchanged from today (specs committed,
   nothing deleted, no Linear/MCP calls).
4. No existing review, audit, or validation gate is weakened or skipped.
5. The schema contains exactly the two documented additions (`spec_source`,
   per-subtask `linear_issue_id`) and preserves `additionalProperties: false`.

## Non-Goals

- No new behavior introduced in this subtask; it only validates and gates.
- No changes to surfaces already delivered in subtasks 1-5 beyond fixes the gate
  surfaces.

## Dependency Notes

Depends on subtasks 1-5; this is the terminal gate.

## Validation Strategy

The maintainer command set above plus the back-compat and local-mode regression
checks. Any failure blocks completion and is fixed in the owning subtask's area
before re-running the gate.

## Next Path

Terminal subtask. On success the feature is complete; in a Linear-backed repo the
local `.feature-specs/SKILL-71-*` scratch would be deleted per subtask 4 — but
SKILL-71 itself is prepared in local mode, so its specs are committed normally.
