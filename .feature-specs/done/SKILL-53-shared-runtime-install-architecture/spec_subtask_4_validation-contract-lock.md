# SKILL-53 Shared Runtime Install Architecture - Subtask 4: Validation And Contract Lock

Parent overview: [spec.md](spec.md)  
Issue key: SKILL-53  
Branch model: same-branch (`feat/SKILL-53-shared-runtime-install-architecture`); commit on completion after final validation.

## Scope

Close the feature with cross-boundary coverage and validation after the shared foundation, CLI/shell persistence, and desktop adapter work are implemented.

This subtask owns final verification and any narrow fixes required by the tests:

- Add or tighten tests that prove CLI install persistence, desktop preference adapter behavior, detected/manual agent cases, MCP opt-out, failed-attempt non-persistence, and migration/backward compatibility for existing desktop preference files.
- Verify runtime modules do not depend on `runtime-desktop:*` and desktop install-choice state has not been duplicated into a new desktop-owned format.
- Verify `install.sh` delegates persistence through runtime CLI/shared runtime behavior and does not write desktop preference internals.
- Run the repo validation strategy and fix root causes without suppressions.
- Update the parent spec status only if the implementation workflow for this subtask reaches the normal completion/audit step.

## Acceptance Criteria

1. Tests cover CLI install persistence.
2. Tests cover desktop preference adapter behavior.
3. Tests cover detected-agent and manual-agent persistence cases.
4. Tests cover MCP opt-out persistence.
5. Tests cover migration/backward compatibility for existing desktop preference files.
6. Full validation passes through `bill-quality-check` or the documented repo-native fallback.
7. Dependency direction remains valid: runtime modules do not depend on desktop modules.

## Non-Goals

- Do not introduce new install selection semantics.
- Do not perform broad install runtime refactors unrelated to closing coverage or validation gaps.
- Do not change generated skill wrappers, support pointers, native-agent outputs, or install staging artifacts.
- Do not remove legacy desktop preference compatibility unless tests prove migration is complete and behavior remains backward compatible.

## Dependencies

Depends on subtasks 1, 2, and 3 because this subtask validates the completed shared persistence, non-desktop write path, and desktop adapter/migration behavior together.

## Validation Strategy

Primary: `bill-quality-check`.

Required final commands if not already covered by the routed checker:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-53-shared-runtime-install-architecture/spec_subtask_4_validation-contract-lock.md`.

