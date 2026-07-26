# SKILL-52.2 Subtask 1 - Boundary Inventory + Contract Targets

Parent spec: [.feature-specs/SKILL-52.2-runtime-boundary-closure/spec.md](./spec.md)
Issue key: SKILL-52.2
Subtask order: 1 of 5
Depends on: none
Branch model: same-branch, commit per subtask

## Purpose

Create the mechanical target map for SKILL-52.2 before changing behavior. This
subtask should make the remaining raw-map and broad-boundary debt explicit,
owned, and test-addressable.

## Scope

In scope:

- Inventory every public raw-map declaration in:
  - `runtime-application/src/main/kotlin`
  - `runtime-domain/src/main/kotlin`
  - `runtime-ports/src/main/kotlin`
- Classify each entry as one of:
  - must type now in SKILL-52.2;
  - allowed open extension/artifact seam;
  - private/internal serializer helper not part of public API;
  - postponed with explicit reason and owner.
- Update `runtime-kotlin/ARCHITECTURE.md` with a SKILL-52.2 target section that
  names the remaining open-boundary classes by category.
- Add or adjust architecture tests so the inventory is not just prose:
  - raw-map allow-list entries must be grouped by SKILL-52.2 target owner;
  - stale entries removed by later subtasks must fail if reintroduced;
  - new public raw maps without `@OpenBoundaryMap` and documentation must fail.
- Add fixture tests for any new scanner regex or source parser used by the
  architecture test. Do not trust an empty production scan alone.
- Fix obvious stale documentation that requires no behavioral change, including
  module ownership comments that still point to `runtime-core` for implementations
  now owned by `runtime-infra-fs`.

Out of scope:

- Large API migrations.
- CLI/MCP/Desktop behavior changes.
- Removing raw-map entries that require model design.

## Acceptance Criteria

1. A SKILL-52.2 inventory section exists in `runtime-kotlin/ARCHITECTURE.md`.
2. Architecture tests parse or otherwise enforce the inventory categories.
3. Every current public raw-map declaration is classified.
4. The known stale `SkillRemoveFileSystem` implementation ownership KDoc is
   corrected.
5. Focused architecture tests pass.

## Validation

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
```

