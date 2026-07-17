---
status: Draft
issue_key: SKILL-127
subtask_id: 3
source: parent spec
---

# SKILL-127.3: Runtime Activation and Durable Selection State

## Scope

Teach `bill-feature`, feature-task runtime, prose workflows, and decomposed goal
children to combine initializer, explicit, and contextual add-on selections with
deduplication and durable provenance.

## Acceptance Criteria

1. Compatible initializer add-ons are loaded once for every Skill
   Bill-launched child session without requiring an explicit
   `agent-addon:<slug>` token, including `codex-agent-policy` for Codex and
   `peak-hours-warning` for ZCode.
2. Explicit add-on selections remain supported and are deduplicated when they
   name an initializer that was already loaded.
3. Contextual add-ons can be loaded through a governed activation path that
   verifies compatible agent ids, consumers, source identity, and content
   digest before prompt injection.
4. Durable workflow state records slug, source identity, content digest, and
   activation source (`initializer`, `explicit`, or `contextual`) for every
   loaded add-on.
5. Resume rejects missing sources, digest drift, incompatible receiving agents,
   reordered durable entries, or activation-mode mismatches before launching a
   child.
6. Runtime prompt formatting labels loaded add-ons by activation source and
   preserves ordering: initializer, explicit, then contextual.
7. Goal child launches forward the same hydrated selection to review, repair,
   audit, validation, history, commit, and PR phases without reparsing raw
   tokens.
8. `execution-budget` remains contextual and is not loaded unless explicitly
   selected or chosen by the governed contextual activation path.

## Non-Goals

- Natural-language auto-selection without the governed contextual loader.
- Changing code-review mode, parallel-review mode, or phase-agent selection.
- Loading add-ons for agents outside their declared compatibility set.

## Dependency Notes

Depends on subtasks 1 and 2. Runtime selection uses the schema metadata from
subtask 1 and may use the catalogue/bootstrap outputs from subtask 2.

## Validation Strategy

Run feature-task runtime tests, prose workflow tests, goal runner integration
tests, and prompt formatter tests for initializer loading, contextual loading,
deduplication, and resume drift rejection.

## Next Path

Continue with subtask 4 for CLI, docs, telemetry, and full validation.
