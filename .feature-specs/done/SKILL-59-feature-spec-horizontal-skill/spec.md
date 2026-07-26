# SKILL-59 - bill-feature-spec horizontal skill

Created: 2026-05-31
Status: In Progress
Issue key: SKILL-59
Parent: follow-up to SKILL-51 decomposition workflow state and SKILL-56/SKILL-58 goal runner work

## Sources

- User request on 2026-05-31:
  - create a separate skill for decomposition so users can prepare a spec independently;
  - keep it reusable by `bill-feature-implement`;
  - make it a new horizontal skill for skill-bill.
- Existing `bill-feature-implement` decomposition behavior:
  - saves parent specs under `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md`;
  - writes `spec_subtask_*.md` files for oversized work;
  - writes and validates `decomposition-manifest.yaml`;
  - stops intentionally at planning when work is decomposed.
- Existing `bill-goal` behavior:
  - can interactively classify a goal and request one confirmation;
  - expects a decomposed parent workflow/runtime manifest before invoking `skill-bill goal <issue_key>`.

## Problem

Decomposition is currently embedded inside `bill-feature-implement` and partially
fronted by `bill-goal`. That makes the happy path workable, but it leaves no
clean user-facing way to prepare, review, edit, and validate a decomposition
without starting implementation.

The raw runtime command `skill-bill goal <issue_key>` is intentionally only a
runner. It should not grow into a spec generator. At the same time, requiring
users to run a full feature-implementation workflow just to produce subtask
specs is too indirect and makes `bill-goal` responsible for too much ceremony.

## Goals

1. Add a new horizontal governed skill named `bill-feature-spec`.
2. Let users provide an issue key plus issue description, design note, or rough
   task brief and receive prepared feature-spec artifacts without running
   implementation.
3. Reuse one feature-spec/decomposition implementation path across `bill-feature-spec`,
   `bill-feature-implement`, and `bill-goal`.
4. Preserve `skill-bill goal <issue_key>` as a runtime runner that consumes
   existing decomposition state rather than generating it.
5. Make prepared decompositions directly usable by `skill-bill goal <issue_key>`
   after validation and the required confirmation gate.
6. Keep single-spec preparation lightweight: `spec.md` is the complete artifact
   when no decomposition is needed.

## Non-Goals

- Do not create platform-specific decomposition skills or platform-pack entries.
- Do not fork or duplicate the `bill-feature-implement` planning/decomposition
  prompt logic.
- Do not change the decomposition manifest schema unless required to expose a
  missing runtime contract.
- Do not make every feature go through decomposition.
- Do not allow `skill-bill goal <issue_key>` to synthesize specs from prose.
- Do not add generated `SKILL.md` files or generated support pointers to source.

## Target User Experience

Users can prepare work without starting implementation:

```text
/bill-feature-spec SKILL-59
Create a horizontal skill that prepares decomposed feature specs and manifest
without running implementation. bill-feature-implement and bill-goal should use
the same decomposition path.
```

For single-spec work, the skill produces:

```text
.feature-specs/SKILL-59-feature-spec-horizontal-skill/spec.md
```

For decomposed work, the skill produces:

```text
.feature-specs/SKILL-59-feature-spec-horizontal-skill/spec.md
.feature-specs/SKILL-59-feature-spec-horizontal-skill/spec_subtask_1_*.md
.feature-specs/SKILL-59-feature-spec-horizontal-skill/spec_subtask_2_*.md
.feature-specs/SKILL-59-feature-spec-horizontal-skill/decomposition-manifest.yaml
```

It then reports the next command:

```bash
skill-bill goal SKILL-59
```

For small goals, the skill should not force decomposition. It should write a
normal parent `spec.md` and report that the work is small enough for
`bill-feature-implement` directly. It must not create a
`decomposition-manifest.yaml` for single-spec work.

## Acceptance Criteria

1. A new horizontal skill source exists at
   `skills/bill-feature-spec/content.md`.
2. The new skill is governed like other horizontal skills:
   - authored source is `content.md`;
   - no generated `SKILL.md` is committed;
   - install/render output remains generated.
3. The skill intake requires:
   - issue key;
   - intended outcome;
   - acceptance criteria;
   - known constraints and non-goals when available.
   If the issue key is missing, the skill must ask for it and must not invent
   one.
4. The skill classifies the work as:
   - `single_spec` when one normal implementation pass is appropriate;
   - `decomposed` when multiple independently resumable implementation
     subtasks are needed.
5. For `single_spec`, the skill writes or updates the parent spec at
   `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md`, validates the spec
   shape expected by the existing workflow, and reports that implementation can
   proceed with `bill-feature-implement`.
6. For `single_spec`, the skill does not create `decomposition-manifest.yaml`
   and does not create fake one-subtask decomposition state for consistency.
   If a decomposition manifest already exists for the same issue directory, the
   skill must fail loudly or ask before replacing the directory's mode.
7. For `decomposed`, the skill writes or updates:
   - parent `spec.md`;
   - two or more ordered `spec_subtask_*.md` files;
   - `decomposition-manifest.yaml`.
8. Each generated subtask spec contains:
   - its own purpose and scope;
   - acceptance criteria;
   - non-goals;
   - dependency notes;
   - validation strategy;
   - recommended next prompt or continuation path.
9. The decomposition manifest is validated against
   `orchestration/contracts/decomposition-manifest-schema.yaml`.
10. A prepared decomposed goal is importable or persisted such that
   `skill-bill goal <issue_key>` can start without requiring a separate
   `bill-feature-implement` planning run.
11. `bill-feature-implement` uses the same reusable feature-spec/decomposition service/path
    when planning returns `mode: "decompose"`.
12. `bill-goal` uses the same reusable feature-spec/decomposition service/path when a user
    gives an issue description and no decomposition exists yet.
13. `bill-goal` preserves its one-confirmation gate before starting the
    foreground `skill-bill goal <issue_key>` loop.
14. `skill-bill goal <issue_key>` remains a consumer of existing decomposition
    state only. If no parent workflow or checked-in decomposition manifest
    exists, it continues to fail loudly with a clear not-found message.
15. The implementation includes regression coverage proving there is one shared
    feature-spec/decomposition path rather than three divergent copies in
    `bill-feature-spec`, `bill-feature-implement`, and `bill-goal`.
16. Documentation and catalog surfaces mention `bill-feature-spec` as the
    standalone preparation step for feature specs and large goals.
17. Maintainer validation passes:
    - `skill-bill validate`
    - `(cd runtime-kotlin && ./gradlew check)`
    - `npx --yes agnix --strict .`
    - `scripts/validate_agent_configs`

## Design Notes

- Treat `bill-feature-spec` as a front door over shared orchestration, not
  as a standalone planning engine.
- Prefer extracting a reusable decomposition application service from the
  existing `bill-feature-implement` decomposition writer/manifest path.
- The durable decomposition manifest remains the executable contract for
  `goal`; markdown specs are the human-readable plan and handoff surface.
- A missing `decomposition-manifest.yaml` is intentional for `single_spec` mode.
  It tells the runtime and future maintainers that the work should be handled by
  `bill-feature-implement`, not by the goal runner.
- If users edit generated specs after preparation, validation should either
  detect manifest/spec drift or clearly report that the manifest is still the
  authoritative runtime contract.
- The CLI/runtime layer may expose a dedicated command if needed, but the skill
  must remain the user-facing governed entry point.

## Validation Strategy

- Add skill validation coverage for the new horizontal skill source and rendered
  install output.
- Add unit tests for the extracted decomposition service:
  - single-spec output;
  - single-spec output does not create a decomposition manifest;
  - decomposed output with subtask specs;
  - manifest validation success;
  - invalid/missing issue key loud failure;
  - import/persistence path that makes `skill-bill goal <issue_key>` runnable.
- Add integration tests covering:
  - `bill-feature-implement` decomposition still writes the same manifest shape;
  - `bill-goal` can prepare decomposition through the shared path before
    invoking the runner;
  - `skill-bill goal` still refuses to generate decomposition from prose.
- Run the full maintainer validation command set listed in the acceptance
  criteria.

## Open Questions

- Should `bill-feature-spec` expose a CLI command in addition to the skill,
  or should the skill drive existing workflow commands until a CLI need is
  proven?
- Should prepared decompositions be persisted to workflow state immediately, or
  should `skill-bill goal` continue importing the checked-in
  `decomposition-manifest.yaml` projection on first run?
- What is the strictest practical spec/manifest drift check after users manually
  edit prepared markdown specs?
