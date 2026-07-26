# SKILL-133 Unified Manifest Authority

Status: ready

## Intended Outcome

Feature preparation has one authoritative artifact shape. A
`decomposition-manifest.yaml` is the sole marker that feature-spec preparation
has completed, and every prepared feature has a manifest containing one or more
ordered executable subtasks.

An existing `spec.md` without a manifest is intake, not proof that scope was
assessed. On a new run, `bill-feature` sends that intake through
`bill-feature-spec`; it never dispatches implementation directly from a bare
spec. Small features use the same manifest-driven workflow as larger features,
with exactly one subtask when one implementation unit is sufficient.

## Motivation

SKILL-131 exposed an unsafe inference in `bill-feature`: a pre-existing
`spec.md` with no decomposition manifest was treated as an authoritative
single-spec preparation result. The current invocation therefore skipped
`bill-feature-spec`, accepted externally chosen scope, and launched a broad
25-criterion implementation as one runtime task.

The two artifact shapes also force routing, source-mode handling, cleanup,
status, resume, tests, and documentation to distinguish single-spec from
decomposed features. A mandatory manifest removes that ambiguity and makes
prepared state explicit.

## Scope

- Make the decomposition manifest the sole on-disk authority marker for a
  prepared feature.
- Require new-work preparation whenever an issue has no manifest, even when a
  parent `spec.md` already exists.
- Make feature-spec preparation emit a schema-valid manifest containing at
  least one executable subtask.
- Represent a small feature as a parent spec, one executable subtask spec, and
  a manifest whose `subtasks` array contains that single subtask.
- Route every newly prepared feature through the manifest-driven goal boundary;
  the one-subtask case executes one child feature-task workflow.
- Preserve DB-authoritative continuation handling before artifact discovery.
- Preserve local and Linear spec-source behavior, durable workflow identity,
  feature-task semantics, telemetry, cleanup, and loud-fail validation.
- Update authored skill guidance, runtime preparation code, tests,
  documentation, rendering, and installed outputs.

## Acceptance Criteria

1. `decomposition-manifest.yaml` is the only artifact whose presence marks feature-spec preparation as authoritative; `spec.md` alone never authorizes direct implementation dispatch.
2. `bill-feature` performs repository-scoped continuation lookup before artifact preparation. A valid resumable workflow still dispatches from its persisted identity and artifacts without re-preparing the feature.
3. For a `no_match` continuation result, exactly one matching valid manifest skips feature-spec preparation and dispatches the manifest-driven executor.
4. For a `no_match` continuation result with no matching manifest, `bill-feature` invokes `bill-feature-spec` even when one matching `spec.md` exists. The existing spec is treated as intake to be assessed and normalized.
5. Multiple issue-matching manifests, malformed manifests, selector conflicts, and invalid prepared artifacts fail loudly; routing never chooses a candidate by recency or falls back to a bare spec.
6. `bill-feature-spec` always produces or updates a parent `spec.md`, one or more ordered executable subtask specs, and `decomposition-manifest.yaml` through the shared preparation path.
7. A feature classified as one implementation unit produces exactly one manifest subtask. The one-subtask preparation is schema-valid and does not require a fabricated second subtask.
8. The single-subtask manifest retains the parent feature contract in `spec.md` and points its sole subtask to a distinct executable subtask spec containing scope, acceptance criteria, non-goals, dependency notes, validation strategy, and next path.
9. The shared preparation writer accepts one or more ordered subtasks, rejects zero subtasks, and preserves identifier uniqueness, ascending dependency order, acyclic earlier-only dependencies, required-field validation, and atomic writes.
10. Preparation no longer has a write path that returns a successful authoritative result without a manifest. Mode or sizing decisions may remain internal planning metadata but cannot select a different artifact shape.
11. `bill-feature` dispatches authoritative manifests through `bill-feature-goal` regardless of subtask count. A one-subtask goal launches exactly one child feature-task workflow and retains the existing single confirmation gate.
12. Goal status, resume, commit, PR, cleanup, and telemetry behavior work for a one-subtask manifest without special-case deletion, duplicate parent completion, or a second child launch.
13. Local preparation omits `spec_source` as the existing local default; Linear preparation stamps the manifest and subtask issue identity consistently for both one-subtask and multi-subtask features.
14. Existing schema-valid multi-subtask manifests remain authoritative and behaviorally unchanged.
15. Existing bare specs remain readable as intake and can be upgraded by feature-spec preparation without silently discarding intended outcome, acceptance criteria, constraints, or non-goals.
16. Tests cover bare-spec preparation, manifest direct dispatch, one-subtask writing and validation, zero-subtask rejection, ambiguous/malformed manifests, one-child goal execution, continuation precedence, local/Linear source behavior, and cleanup.
17. Authored `content.md` guidance and operator documentation describe the manifest authority invariant and the one-subtask representation; generated governed wrappers and support pointers are not committed.
18. After authored skill or renderer changes, `./install.sh` refreshes local staging, and the repository passes:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Constraints

- The workflow database remains authoritative for nonterminal continuation and
  immutable execution identity.
- Manifest validation remains schema-backed and loud-failing at every parse
  seam.
- The manifest contract continues to support one or more subtasks; do not
  encode different schema shapes for small and large features.
- Preserve unrelated working-tree changes and the nonterminal SKILL-131
  workflow until it is explicitly abandoned through the supported operator
  path.
- Update governed `content.md` sources rather than generated `SKILL.md`
  wrappers.
- Do not add agent-identity branching to the runtime process runner.

## Non-Goals

- Changing how feature size is estimated beyond removing its authority over
  artifact shape.
- Requiring multiple subtasks for work that has only one executable unit.
- Automatically migrating or rewriting nonterminal durable workflows.
- Changing review severity, phase-agent selection, add-on selection, or
  feature-task phase semantics.
- Supporting nested decomposition of an already executable subtask spec.

## Validation Strategy

- Add focused preparation-writer and schema tests for one-subtask acceptance
  and zero-subtask rejection.
- Add router contract tests proving a bare spec invokes preparation and a valid
  manifest dispatches directly.
- Add goal-runner integration coverage proving a one-subtask manifest completes
  exactly one child lifecycle and finalizes the parent correctly.
- Add continuation regression coverage proving persisted workflow identity wins
  before artifact inspection.
- Add local and Linear preparation/cleanup tests.
- Run the complete repository validation gates from acceptance criterion 18.

## Next Path

Run bill-feature on `.feature-specs/SKILL-133-unified-manifest-authority/spec.md`.
