# SKILL-188 · Subtask 3 — Regression, conformance, and documentation correction

## Scope

Pin the defect closed and stop the documentation from teaching the workaround.

The parent spec's diagnostic evidence is the test oracle: a pack declaring
add-ons in `addon_usage`, no markdown link in any `content.md`, and rendered
agents containing zero add-on bytes. That exact shape must become a test that
fails against the pre-fix renderer.

- Rendering and composition regression coverage in
  `runtime-kotlin/runtime-infra-fs`.
- External-overlay end-to-end coverage exercising
  `FileSystemExternalAddonOverlay` into an installed pack, then render.
- Conformance coverage for the slug/content equivalence introduced in
  subtask 2.
- `docs/external-addons.md` and the native-agent composition documentation.

## Acceptance Criteria

1. A regression test reproduces the exact reported defect: a pack shaped like
   the iOS pack with `addon_usage` declaring an add-on, no markdown link in the
   owning `content.md`, asserting the add-on's distinctive content is present in
   the rendered agent. It fails against the current renderer.
2. A regression test covers the external-overlay path end to end: overlay an
   external source onto an installed pack, render, assert the content reaches
   the agent, and assert the upstream `content.md` was not modified.
3. Idempotence coverage asserts byte-identical rendered output across repeated
   renders from the same manifest and add-on files.
4. Ordering coverage pins the documented sequence of baseline content, area
   content, and composed add-ons, including entrypoint-before-companions.
5. Dedup coverage asserts that a file reachable both as a declared add-on and as
   a linked sidecar appears exactly once.
6. Loud-fail coverage asserts the error message for a missing, unreadable, or
   undeclared target names the slug, the slot, and the fully resolved absolute
   path, and that no agent is rendered.
7. Budget coverage asserts that exceeding the size budget fails loudly naming
   the pack, skill directory, and byte total, and never truncates.
8. Conformance coverage pins slug/content equivalence: no slug is reported as
   selected without its content being present in the worker.
9. Harness coverage asserts composition on every rendered target under
   `installed-skills/native-agents-*/`, not the Claude set alone.
10. `docs/external-addons.md` states that `addon_usage` alone composes content
    and drops any implication that a `content.md` link is required.
11. The native-agent composition documentation states the same, and documents
    the composition ordering, the dedup rule, the loud-fail vocabulary, and the
    budget behavior.
12. Any documentation that recommends or implies editing an upstream pack's
    `content.md` to activate an add-on is removed.
13. The runtime check suite passes.

## Non-Goals

- No behavioral change to composition; that is subtasks 1 and 2. This subtask
  only proves and documents it.
- No new diagnostic command for inspecting composed agents.
- No change to add-on authoring format or the external-source config shape.
- No retrofit of already-installed agent files.

## Dependency Notes

Depends on subtasks 1 and 2. The composition seam, harness coverage, and slug
reconciliation must all exist before they can be pinned and described.

Criterion 1's "fails against the current renderer" is a property of the test's
design, verifiable by inspection against the pre-fix behavior described in the
parent spec — it does not require reverting the implementation.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Then confirm the parent spec's real-install probe:

```bash
cd /home/sermilion/StudioProjects/skill-bill
./install.sh
```

and verify composed add-on content is present in each declared area agent.

## Next Path

Feature complete. Per the parent spec, re-run the delegated review that surfaced
this defect and compare against the archived inline result from the same delta;
the delegated findings should now carry the precedent citations the add-ons'
Grounding sections require.
