---
issue_key: SKILL-136
subtask_id: 3
name: Declare the areas and route to them
parent_spec: .feature-specs/SKILL-136-android-native-review-specialists/spec.md
---

# Subtask 3 — Declare the areas and route to them

## Intended Outcome

The `kmp` pack declares `persistence` and `reliability` as its own areas, so
`ReviewLaunchPlanPolicy` resolves them at composition depth 0 and shadows the
Kotlin baseline's backend-framework lanes. The five baseline-sourced areas are
unaffected, and no other pack's composed plan changes.

## Scope

- `platform-packs/kmp/platform.yaml`: add `persistence` and `reliability` to
  `declared_code_review_areas`, `declared_files`, `area_metadata` (including
  `focus`), and `lane_conditions`.
- `platform-packs/kmp/code-review/bill-kmp-code-review/content.md`: add
  matching rows to the Diff-Signal Routing Table.
- Launch-plan resolution coverage in the runtime tests.

## Acceptance Criteria

1. `platform-packs/kmp/platform.yaml` declares `persistence` and `reliability`
   with `declared_files`, `area_metadata.focus`, and `lane_conditions`
   entries, and validates against
   `orchestration/contracts/platform-pack-schema.yaml`.
2. The `area_metadata.focus` text for both areas names Android-native
   frameworks (Room, SQLDelight, DataStore, WorkManager) rather than
   backend-JVM frameworks.
3. `bill-kmp-code-review`'s Diff-Signal Routing Table includes rows for both
   new areas, matching the existing rows' format and specificity.
4. The composed KMP launch plan resolves `persistence` to
   `bill-kmp-code-review-persistence` and `reliability` to
   `bill-kmp-code-review-reliability`.
5. The composed KMP launch plan resolves `architecture`, `performance`,
   `security`, `testing`, and `api-contracts` to their
   `bill-kotlin-code-review-*` equivalents, unchanged.
6. `platform-correctness`, `ui`, and `ux-accessibility` continue to resolve to
   the KMP declared specialists.
7. No pack other than `kmp` has a changed composed launch plan, asserted for at
   least one other pack.
8. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, and
   `scripts/validate_agent_configs` pass.

## Non-Goals

- Declaring KMP-specific `architecture`, `performance`, `security`, `testing`,
  or `api-contracts` specialists.
- Introducing per-area baseline sourcing or additive baseline layering.
- Changing the approved `declared_code_review_areas` taxonomy.
- Changing the generic or Kotlin packs.

## Dependencies

Subtask 1 (routing must select the pack) and Subtask 2 (the specialists must
exist before they can be declared and resolved).

## Validation Strategy

Assert composed area-to-skill resolution for the full KMP plan and an unchanged
plan for at least one other pack. Then:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
scripts/validate_agent_configs
```

## Next Path

Continue the goal; the platform-pack half of the feature is complete after this
subtask.
