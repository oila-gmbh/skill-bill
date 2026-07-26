# SKILL-112 Subtask 8 - Conformance Gate Retirement and Final Verification

## Scope

Close the program: retire the exemption mechanism, run the full validation
matrix, and refresh installs.

### 1. Retire the exemption list

All six packs are now conformant. Remove the exemption-list mechanism from
the structure-conformance test so the standard is unconditionally enforced
for every current and future pack (including packs added later by teams —
the test reads `platform-packs/*` dynamically).

### 2. Full verification

Run the complete validation matrix, confirm no generated artifacts are
committed, and re-render installs so local agents pick up the new staging
hash.

### 3. Documentation touch-up

Update the README pack catalog and `docs/review-telemetry.md`-adjacent docs
only where this program changed observable behavior (the new kmp
platform-correctness specialist is the one catalog-visible addition).

## Acceptance Criteria

1. The structure-conformance test has no exemption list and passes for all
   six packs.
2. `skill-bill validate` passes from the repo root.
3. `(cd runtime-kotlin && ./gradlew check)` passes, including all six pack
   tests and `PlatformPackSchemaValidatesExistingPacksTest`.
4. `npx --yes agnix --strict .` and `scripts/validate_agent_configs` pass.
5. `grep -rn "Critical or Major\|Major or Critical\|Major or Blocker" platform-packs/`
   and `grep -rn "UI Delegation" platform-packs/` return nothing.
6. `./install.sh` completes; no generated `SKILL.md` wrappers, support
   pointers, or provider-specific agent outputs are committed.
7. The README pack catalog reflects the kmp platform-correctness addition.

## Non-Goals

- No content changes to any pack beyond documentation.
- No schema or contract-version changes.

## Dependency Notes

Depends on all of subtasks 1-7.

## Validation Strategy

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
./install.sh
grep -rn "Critical or Major\|Major or Critical\|Major or Blocker" platform-packs/ && exit 1 || true
```

## Next Path

Program complete; hand the feature branch to review via `bill-pr-description`.
