# SKILL-44 state-repo-controls-tree-polish

Status: Complete

## Sources

- `docs/desktop-skill-bill-app/ui-feature-subtasks/01-state-repo-tree.md`
- `docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`

## Acceptance Criteria

1. Opening a valid local repo loads the existing runtime-backed tree.
2. Opening an invalid path shows the runtime validation message and no stale tree.
3. Refresh reflects file additions, file deletion, and validation status changes.
4. Refresh preserves selection only when the selected item still exists in the same repo.
5. A directory chooser can populate the path field and then run the same open flow as typed input.
6. Expand/collapse state does not reload runtime data or alter selection.
7. Keyboard navigation can move selection through visible nodes.
8. Generated artifacts are never editable and always display `RO` or equivalent.
9. Status bar target counts, repo path, branch label, read-only mode, and policy label are not hardcoded placeholder values.
10. Busy operations disable conflicting actions and show progress.
11. No repository files are modified by open, failed open, refresh, or tree navigation.

## Consolidated Spec

# Subtask 01: State, Repo Controls, and Tree Polish

Status: Draft

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`. It preserves
the implemented read-only repo browser and prepares the state model for the
write, validation, Git, render, and scaffold subtasks that follow.

## UI Entry Points

- Repository path text field in the left panel.
- `Open` action inside the repository selector.
- Top-toolbar `Refresh` action.
- Left-panel tree groups and node rows.
- `RO` badges and read-only mode indicators.
- Left-panel contract policy footer.
- Bottom status bar and dock badges.
- Inspector metadata for selected tree items.

## Goal

Make the app-level state model coherent enough that every visible badge, count,
selection, policy label, and disabled action reflects the same underlying repo
session and tree state.

## Scope

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

## Runtime and Service Requirements

- `RepoSessionService.open(path)` remains the repo identity entry point.
- `SkillTreeService.treeFor(session)` rebuilds the tree on refresh.
- `AuthoringGateway.describeSelection(id)` must reject stale repo-scoped ids.
- `RecentRepoRepository` stores only successful repo selections.
- Tree nodes must come from runtime discovery and authoring surfaces.
- Generated artifacts must come from shared generated-artifact discovery, not UI
  filename guessing.

## Acceptance Criteria

- Opening a valid local repo loads the existing runtime-backed tree.
- Opening an invalid path shows the runtime validation message and no stale tree.
- Refresh reflects file additions, file deletion, and validation status changes.
- Refresh preserves selection only when the selected item still exists in the
  same repo.
- A directory chooser can populate the path field and then run the same open
  flow as typed input.
- Expand/collapse state does not reload runtime data or alter selection.
- Keyboard navigation can move selection through visible nodes.
- Generated artifacts are never editable and always display `RO` or equivalent.
- Status bar target counts, repo path, branch label, read-only mode, and policy
  label are not hardcoded placeholder values.
- Busy operations disable conflicting actions and show progress.
- No repository files are modified by open, failed open, refresh, or tree
  navigation.

## Validation

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

## Non-Goals

- Editing and saving.
- Running repo validation.
- Git staging, commit, or push.
- Scaffold creation.
- File watching.

## Parent Context Excerpt

# Desktop UI Feature Implementation Specs

Status: Draft

## Purpose

This document inventories the features currently exposed by the desktop Skill
Bill UI and links each visible affordance to an implementation-sized subtask.
It is derived from
`runtime-kotlin/runtime-desktop/feature/skillbill/src/commonMain/kotlin/skillbill/desktop/feature/skillbill/ui/SkillBillFrame.kt`
and the current domain/view-model state contracts.

The UI already has a real repo-browser path. These subtasks preserve that work
and describe the remaining behavior behind each visible control, tab, panel,
and status surface.

## Global Rules

- Use existing Skill Bill runtime services or CLI-equivalent adapters for
  governed behavior.
- Keep generated `SKILL.md` wrappers, generated support pointers,
  provider-specific native-agent output, and install cache output read-only.
- Do not duplicate manifest discovery, scaffold payload validation, routing,
  validation, rendering, or native-agent generation rules in UI code.
- Keep desktop code responsible for presentation, local state, process
  orchestration, and Git UX only.
- Preserve the existing app shell and evolve it in place.

## Subtask Specs

1. [State, Repo Controls, and Tree Polish](ui-feature-subtasks/01-state-repo-tree.md)
2. [Repository Validation Workbench](ui-feature-subtasks/02-validation-workbench.md)
3. [Authored Content Editor](ui-feature-subtasks/03-authored-content-editor.md)
4. [Render Check and Install Console](ui-feature-subtasks/04-render-console.md)
5. [Source-Control Changes and History](ui-feature-subtasks/05-changes-history.md)
6. [Commit, Push, and Fork Publishing](ui-feature-subtasks/06-publishing.md)
7. [Command Search and Quick Open](ui-feature-subtasks/07-command-search.md)
8. [Scaffold Entry Points and Wizards](ui-feature-subtasks/08-scaffold-wizards.md)

## UI Feature Map

| UI surface | Current state | Subtask |
| --- | --- | --- |
| Repository path field and Open action | Implemented for local paths | 01 |
| Refresh toolbar action | Implemented for explicit reload | 01 |
| Branch/source-control toolbar label | Partial | 05, 06 |
| Validate toolbar action | Placeholder | 02 |
| Render check toolbar action | Placeholder | 04 |
| Read-only toolbar badge | Implemented as mode indicator | 01, 03 |
| Command search box | Placeholder | 07 |
| Left tree navigator | Implemented read-only tree | 01 |
| Left Validation action | Placeholder | 02 |
| Left Read-only browsing action | Indicator | 01 |
| Contract policy footer | Static indicator | 01 |
| Editor tab strip | Partial single-tab display | 03 |
| Center editor/source viewer | Read-only display | 03 |
| Inspector metadata section | Partial | 01, 03, 04 |
| Inspector repository validation section | Partial | 02 |
| Inspector validation issues section | Partial | 02 |
| Inspector generated artifacts section | Partial | 04 |
| Bottom Validation dock tab | Partial | 02 |
| Bottom Changes dock tab | Placeholder | 05 |
| Bottom History dock tab | Placeholder | 05 |
| Bottom Install console dock tab | Placeholder | 04 |
| Bottom status bar | Partial | 01, 02, 03, 05 |

## Suggested Implementation Order

1. Subtask 01, because it creates the shared state model used by later features.
2. Subtask 02, because validation state drives badges, inspector rows, and save
   safety.
3. Subtask 03, because editing introduces the first repo write path.
4. Subtask 05, because users need diff review once writes exist.
5. Subtask 06, because publishing depends on real Git status and validation.
6. Subtask 04, because render/check output can reuse the same console and
   generated-artifact read models.
7. Subtask 07, because command search should call already-real ViewModel
   commands.
8. Subtask 08, because scaffold creation is the broadest write path and should
   land after validation, dirty-state, and Git review are reliable.

Each subtask file is intended to be small enough to hand directly to
`bill-feature-implement`.
