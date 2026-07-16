---
status: Draft
issue_key: SKILL-127
subtask_id: 4
source: parent spec
---

# SKILL-127.4: CLI, Docs, Telemetry, and Full Validation

## Scope

Expose operator-facing commands and documentation for the add-on catalogue and
typed activation model, then complete repository validation.

## Acceptance Criteria

1. CLI commands can list the catalogue, inspect an add-on's metadata, verify
   startup/bootstrap installation status, and show which initializer add-ons
   would load for a receiving agent.
2. Existing `agent-addon resolve-selection` and verification commands preserve
   explicit-selection behavior while reporting activation type and activation
   source where relevant.
3. Documentation explains `initializer` versus `contextual`, catalogue contents,
   startup/bootstrap behavior, explicit selection, contextual loading, and
   precedence limits.
4. Telemetry records slug, activation type, compatible agent ids, consumer, and
   digest without emitting add-on body content or absolute user paths.
5. Desktop or scaffold surfaces that show add-ons display activation type and
   `use_when` without treating add-ons as top-level skills.
6. The full validation gate passes: `skill-bill validate`,
   `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and
   `scripts/validate_agent_configs`.

## Non-Goals

- A remote marketplace.
- Automatic contextual inference beyond the governed loader contract.
- Provider-specific startup support beyond what has a tested, documented
  surface.

## Dependency Notes

Depends on subtasks 1, 2, and 3.

## Validation Strategy

Run targeted CLI and docs tests first, then the full repository validation gate.

## Next Path

Finish the decomposed goal, update history/decision records if required by the
implementation, and prepare the parent PR description.
