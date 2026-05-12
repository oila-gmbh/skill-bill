# SKILL-44 — State, Repo Controls, and Tree Polish

Status: In Progress

## Sources

- Subtask spec: `docs/desktop-skill-bill-app/ui-feature-subtasks/01-state-repo-tree.md`
- Parent overview: `docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`
- UI entry point referenced by the spec:
  `runtime-kotlin/runtime-desktop/feature/skillbill/src/commonMain/kotlin/skillbill/desktop/feature/skillbill/ui/SkillBillFrame.kt`

## Acceptance Criteria

1. Opening a valid local repo loads the existing runtime-backed tree.
2. Opening an invalid path shows the runtime validation message and no stale tree.
3. Refresh reflects file additions, file deletion, and validation status changes.
4. Refresh preserves selection only when the selected item still exists in the
   same repo.
5. A directory chooser can populate the path field and then run the same open
   flow as typed input.
6. Expand/collapse state does not reload runtime data or alter selection.
7. Keyboard navigation can move selection through visible nodes.
8. Generated artifacts are never editable and always display `RO` or
   equivalent.
9. Status bar target counts, repo path, branch label, read-only mode, and
   policy label are not hardcoded placeholder values.
10. Busy operations disable conflicting actions and show progress.
11. No repository files are modified by open, failed open, refresh, or tree
    navigation.

## Non-Goals

- Editing and saving.
- Running repo validation.
- Git staging, commit, or push.
- Scaffold creation.
- File watching.

## Parent overview — Global Rules (apply to this subtask)

- Use existing Skill Bill runtime services or CLI-equivalent adapters for
  governed behavior.
- Keep generated `SKILL.md` wrappers, generated support pointers,
  provider-specific native-agent output, and install cache output read-only.
- Do not duplicate manifest discovery, scaffold payload validation, routing,
  validation, rendering, or native-agent generation rules in UI code.
- Keep desktop code responsible for presentation, local state, process
  orchestration, and Git UX only.
- Preserve the existing app shell and evolve it in place.

## Consolidated Subtask Spec

### Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`. It preserves
the implemented read-only repo browser and prepares the state model for the
write, validation, Git, render, and scaffold subtasks that follow.

### UI Entry Points

- Repository path text field in the left panel.
- `Open` action inside the repository selector.
- Top-toolbar `Refresh` action.
- Left-panel tree groups and node rows.
- `RO` badges and read-only mode indicators.
- Left-panel contract policy footer.
- Bottom status bar and dock badges.
- Inspector metadata for selected tree items.

### Goal

Make the app-level state model coherent enough that every visible badge, count,
selection, policy label, and disabled action reflects the same underlying repo
session and tree state.

### Scope

- Keep the existing path-based repo open flow.
- Add a desktop directory chooser as a second path entry method.
- Preserve explicit refresh behavior.
- Preserve the selected tree item across refresh when the same repo still has
  that item.
- Clear selection when refresh removes the selected item.
- Add expand/collapse state for tree groups.
- Add keyboard focus and keyboard selection for visible tree rows.
- Replace static status-bar, dock badge, and policy placeholders with
  model-backed values or documented constants.
- Add explicit busy states for open, refresh, selection, and tree rebuild.
- Keep generated artifacts selectable only for inspection and always read-only.

### Runtime and Service Requirements

- `RepoSessionService.open(path)` remains the repo identity entry point.
- `SkillTreeService.treeFor(session)` rebuilds the tree on refresh.
- `AuthoringGateway.describeSelection(id)` must reject stale repo-scoped ids.
- `RecentRepoRepository` stores only successful repo selections.
- Tree nodes must come from runtime discovery and authoring surfaces.
- Generated artifacts must come from shared generated-artifact discovery, not UI
  filename guessing.

### Validation

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:core:data:jvmTest :runtime-desktop:feature:skillbill:jvmTest
```

Manual smoke:

1. Open this repository.
2. Add a temporary authored skill source in a throwaway copy.
3. Click Refresh and confirm the tree changes.
4. Delete the selected source and click Refresh.
5. Confirm the UI clears selection without crashing.
