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
  orchestration, and runtime-backed UI flows.
- Preserve the existing app shell and evolve it in place.

## Subtask Specs

1. [State, Repo Controls, and Tree Polish](ui-feature-subtasks/01-state-repo-tree.md)
2. [Repository Validation Workbench](ui-feature-subtasks/02-validation-workbench.md)
3. [Authored Content Editor](ui-feature-subtasks/03-authored-content-editor.md)
4. [Render Check and Install Console](ui-feature-subtasks/04-render-console.md)
5. [Command Search and Quick Open](ui-feature-subtasks/07-command-search.md)
6. [Scaffold Entry Points and Wizards](ui-feature-subtasks/08-scaffold-wizards.md)
7. [Dead and Misleading Affordances](ui-feature-subtasks/09-dead-affordances.md)
8. [Inspector Generated-Artifact Reveal](ui-feature-subtasks/10-inspector-artifact-reveal.md)
9. [Keyboard Accelerators](ui-feature-subtasks/12-keyboard-accelerators.md)
10. [Material 3 Theme Adoption](ui-feature-subtasks/13-material3-theme-adoption.md)

## UI Feature Map

| UI surface | Current state | Subtask |
| --- | --- | --- |
| Repository path field and Open action | Implemented for local paths | 01 |
| Refresh toolbar action | Implemented for explicit reload | 01 |
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
| Bottom Install console dock tab | Placeholder | 04 |
| Bottom status bar | Partial | 01, 02, 03 |
| App theme, local palettes, and reusable UI tokens | Partial | 13 |

## Suggested Implementation Order

1. Subtask 01, because it creates the shared state model used by later features.
2. Subtask 02, because validation state drives badges, inspector rows, and save
   safety.
3. Subtask 03, because editing introduces the first repo write path.
4. Subtask 04, because render/check output can reuse the same console and
   generated-artifact read models.
5. Subtask 07, because command search should call already-real ViewModel
   commands.
6. Subtask 08, because scaffold creation is the broadest write path and should
   land after validation and dirty-state handling are reliable.
7. Subtask 09, because misleading affordances make every later polish pass
   harder to evaluate. Land before any further UI work on the same surfaces.
8. Subtask 10, because Inspector artifact reveal builds on the same
    dirty-editor selection seam subtasks 02 and 03 already established.
9. Subtask 12, because the accelerators wrap callbacks that subtasks 02-04
    already expose and should land after 09 to avoid binding shortcuts to
    dead buttons.
10. Subtask 13, because the Material 3 theme migration is broad visual
    infrastructure. Land it after the major UI behavior is real so the work can
    stay token-for-token instead of guessing at unfinished surfaces.

Each subtask file is intended to be small enough to hand directly to
`bill-feature-task`.
