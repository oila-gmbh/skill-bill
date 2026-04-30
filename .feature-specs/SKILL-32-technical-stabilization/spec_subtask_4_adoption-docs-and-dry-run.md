---
Status: Not Started
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
---

# Subtask 4: Adoption-Facing Hardening (Docs + External-Author Dry Run)

## Goal

Make the technical improvements visible and useful to teams: rewrite the
getting-started docs around the packaged runtime, document strict-vs-model-
mediated guarantees, and prove the external-author flow works end-to-end with
a green dry run.

This subtask delivers Phase 7 of the parent spec.

## Scope

This subtask owns:

1. Updating `docs/getting-started.md` and `docs/getting-started-for-teams.md`
   to cover the packaged runtime, supported fallback boundary, validation
   commands, and which guarantees are strict vs. model-mediated.
2. Adding a "governance system vs reference packs" section if the distinction
   is still easy to miss after the doc rewrite.
3. Implementing an external-author dry run that:
   - scaffolds a temporary platform pack in a fixture or temp repo;
   - validates it;
   - installs/links it into a temp agent path;
   - removes it cleanly.

## Acceptance Criteria

1. `docs/getting-started.md` covers:
   - packaged runtime behavior (Kotlin only, no Gradle invocation in normal
     use);
   - supported fallback boundary (which behaviors fail closed vs. degrade);
   - validation commands (`./gradlew check`, `unittest`, `agnix --strict`,
     `validate_agent_configs.py`);
   - what guarantees are strict (MCP schemas, contract fixtures) vs.
     model-mediated.
2. `docs/getting-started-for-teams.md` covers the same axes from a team-
   onboarding angle.
3. If the governance-system-vs-reference-packs distinction is still easy to
   miss after the rewrite, a short section is added to one of those docs to
   clarify it.
4. An external-author dry run script (or test) executes the four-step flow
   and is green: scaffold a temporary pack, validate it, install/link to a
   temp agent path, remove cleanly.
5. A non-maintainer reading these docs can understand the runtime model
   without consulting migration history.

## Non-Goals

- Any runtime, schema, contract-test, or Python-retirement work (Subtasks 1,
  2, 3 own all of those).
- Adding new platform-pack content; packs remain reference examples per the
  parent spec non-goals.

## Dependencies

- Subtask 1 (ownership table tells the docs which items remain Python-tooling
  vs. retired).
- Subtask 2 (packaged Kotlin behavior must exist to be documented).
- Subtask 3 (Python retirement state must be final so the docs don't promise
  paths that no longer exist).

## Validation Strategy

`bill-quality-check` plus the four-command validation gate. Plus the
external-author dry run must be green. Doc accuracy is verified by running
each documented command from a clean checkout.

## Implementation Notes

- Doc targets: `docs/getting-started.md`, `docs/getting-started-for-teams.md`.
- The dry run can live as a Kotlin integration test under
  `runtime-kotlin/runtime-cli/src/test/kotlin/...` or as a shell test invoked
  from `tests/` — pick whichever boundary already owns scaffold + install
  flow tests so we do not split this concern.
- Cross-link the strict-vs-model-mediated section to the Subtask 2 strict
  schemas and the Subtask 3 contract fixtures so readers can verify claims.
- Constraints: AGENTS.md four-command gate, 2-space indent, no Markdown
  pretty-print drift in existing docs that we are not editing.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_4_adoption-docs-and-dry-run.md`.
