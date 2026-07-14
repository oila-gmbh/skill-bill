---
status: Ready for implementation
issue_key: SKILL-122
subtask_id: 2
---

# SKILL-122 Subtask 2: Agent-addon discovery, staging, and scaffold delivery

## Scope

Use the validated declarations from subtask 1 to add dynamic agent-addon
delivery. Update renderer/install staging so a declared consumer receives a
generated pointer to the add-on content only in staged output; source files
never receive generated output. Fold manifest declarations and target content
into staging identity/hash calculations. Extend dynamic listing/show/explain
and add an atomic `agent-addon` scaffolder kind.

## Acceptance Criteria

1. Rendering/install dynamically finds every valid agent addon and materializes
   consumer-specific generated pointers for `bill-feature` and its documented
   internal sidecars without a hard-coded list of slugs.
2. Pointer filenames are deterministic, collision-safe, normalized relative
   paths whose targets are regular repository files. Missing target, pointer
   collision, self-reference, or malformed declaration fails before staging
   promotion.
3. Generated pointers, agent-addon manifests, and content bodies contribute to
   the affected installed skills' content hashes. Editing an add-on refreshes
   consumer staging while unrelated skills remain unaffected.
4. No `SKILL.md`, pointer, or provider-native generated file is committed under
   `agent-addons/` or `skills/`; install rendering remains atomic and leaves no
   partial staged directory on failure.
5. `skill-bill show`/`explain` and dynamic repository catalogue output identify
   agent add-ons as a distinct extension category and display slug,
   description, supported agents, and consumers. The same typed discovery model
   supplies the desktop tree; the UI does not introduce a filesystem scan.
6. `skill-bill new` accepts an `agent-addon` payload/wizard kind and atomically
   creates `agent-addon.yaml` plus `content.md` only after validating its slug,
   agents, and consumers. Dry run reports both planned paths; any renderer,
   validator, or install failure restores the repository byte-for-byte.
7. Existing platform-pack add-on loading, external platform add-on overlays,
   installer selection, and source-generation behaviour remain unchanged.

## Non-Goals

- Parsing feature arguments or injecting add-ons into workflows.
- A generic external source format for agent add-ons or the desktop feature-run
  selection control.

## Validation Strategy

Add render/install hash, pointer collision, staged-output/source-cleanliness,
catalogue, and scaffold atomic-rollback tests. Use `skill-bill render` and a
temporary install to inspect resulting staged consumer pointers.

## Dependency Notes

Depends on subtask 1. Subtask 3 consumes the staged pointer and typed lookup
surface created here.

## Next Path

Proceed to `spec_subtask_3_agent-addon-feature-selection.md`.
