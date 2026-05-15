# Subtask 10: Inspector Generated-Artifact Reveal

Status: Complete

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`. It closes a
gap in the Inspector pane: the `Generated artifacts` section lists artifact
paths as plain `KeyValueRow` entries with no way to navigate to them, even
though those paths correspond to real tree items.

## UI Entry Points

- Inspector pane `Generated artifacts` section rows for the currently
  selected authored source.
- The same section after a `Render check` run, when the render summary's
  `generatedArtifacts` list is populated.

## Goal

Make every artifact path in the Inspector `Generated artifacts` section a
single-click navigation to the corresponding read-only tree item, so users can
go from "this authored source produced these files" to "open the generated
file in the read-only editor" without manually walking the tree.

## Scope

- Add a route callback that maps a generated artifact path back to a tree
  item id and selects it through the existing tree-selection seam (the same
  path used by `runTreeItemSelection`, including the dirty-editor prompt
  guard).
- Wire the `Generated artifacts` rows to that callback. Rows must remain
  visually consistent with `KeyValueRow` but gain `Role.Button`,
  `iconButtonSemantics(description = "Open artifact: $path")`, and a real
  hover affordance.
- Resolve the artifact path → tree-item-id mapping through an existing
  service (e.g. extend `SkillTreeService` or `AuthoringGateway`); do not
  inline filename guessing in the UI layer.
- Honor the existing dirty-editor prompt: clicking an artifact while the
  editor is dirty must route through `DirtyEditorPromptReason.SELECTION_CHANGE`
  the same way tree clicks do.
- If a path has no corresponding tree item (stale render summary, deleted
  file), the click is a no-op and the row visually demotes to non-interactive
  for that frame.

## Runtime and Service Requirements

- Extend an existing service to expose `resolveTreeItemIdForArtifact(path)`
  (or reuse an existing equivalent such as
  `validationGateway.resolveTreeItemIdForSource`).
- No filesystem I/O on the UI dispatcher. Resolution must happen against the
  in-memory tree snapshot.
- Selection must continue to flow through the existing
  `beginSelectTreeItem` / `finishSelectTreeItem` seam so history filtering
  and onSourceRouteSelected fan-out keep working.

## Acceptance Criteria

- Each row in the Inspector `Generated artifacts` section is keyboard- and
  pointer-clickable when the artifact resolves to a tree item.
- Clicking a resolvable artifact row selects that tree item, expands its
  ancestors, opens the read-only editor for it, and updates the history path
  filter the same way a manual tree click does.
- Clicking an unresolvable artifact row produces no visible change and does
  not throw.
- Clicking an artifact row while the editor is dirty triggers the existing
  dirty-editor prompt (`SELECTION_CHANGE`), not an immediate selection switch.
- Row semantics announce the artifact path through `contentDescription`.
- No UI code performs filesystem I/O to resolve the artifact path.

## Validation

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:feature:skillbill:jvmTest
```

Manual smoke:

1. Open a repo with at least one authored skill that has generated wrappers.
2. Select the authored source.
3. Run `Render check`.
4. Click each path under `Generated artifacts` in the Inspector and confirm
   the corresponding tree item becomes selected and the read-only editor
   updates.
5. Make a small edit in an authored source, leave it dirty, click an
   artifact row, and confirm the dirty-editor prompt appears.

## Non-Goals

- Editing generated artifacts.
- Revealing artifacts in the OS file manager.
- Auto-running render on selection.
- Adding new dock tabs or inspector sections.
