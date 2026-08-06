---
issue_key: SKILL-136
subtask_id: 7
name: Documentation and full maintainer gate
parent_spec: .feature-specs/SKILL-136-android-native-review-specialists/spec.md
---

# Subtask 7 — Documentation and full maintainer gate

## Intended Outcome

Documentation reflects the pack's Android coverage and the review-telemetry
changes, and the full maintainer gate passes against the complete feature.

## Scope

- README and any platform-pack catalog section listing the KMP pack's declared
  areas and its Android coverage.
- `docs/review-telemetry.md`: canonical attribution fields, per-lane finding
  attribution, the shared finding key, and the snapshot retention policy.
- Full gate plus `./install.sh` so local staged installs refresh.

## Acceptance Criteria

1. README and the platform-pack catalog describe the `kmp` pack as covering
   Android and Kotlin Multiplatform, and list its declared areas including
   `persistence` and `reliability`.
2. `docs/review-telemetry.md` documents the canonical attribution fields
   (`routed_skill`, `detected_stack`, `detected_scope`) and the
   unresolved-value convention.
3. `docs/review-telemetry.md` documents per-lane `specialist_reviews` and
   per-lane finding attribution.
4. `docs/review-telemetry.md` documents the shared finding key joining the
   workflow review loop and review-run import stores.
5. `docs/review-telemetry.md` documents the snapshot retention policy and the
   opt-in prune command.
6. No company identifiers appear in any changed documentation; neutral
   placeholders are used.
7. `skill-bill validate` passes.
8. `(cd runtime-kotlin && ./gradlew check)` passes.
9. `npx --yes agnix --strict .` passes.
10. `scripts/validate_agent_configs` passes.
11. `./install.sh` runs after the source/pack changes so local staged installs
    refresh, and the generated staging hash is inspected.
12. No generated `SKILL.md` wrappers, support pointers, or provider-specific
    native-agent output are committed.

## Non-Goals

- Any behavioural change to packs or telemetry; this subtask is documentation
  and the gate only.
- Renaming the spec directory, which deliberately keeps its original name.

## Dependencies

Subtasks 1, 2, 3, 4, 5, and 6 — the gate runs against the complete feature.

## Validation Strategy

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
./install.sh
```

Render `bill-kmp-code-review` and its new specialists to confirm the installed
shell exposes both new areas.

## Next Path

Feature complete; reconcile the parent spec to its final state.
