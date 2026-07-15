# SKILL-123.5 - Desktop Tools Catalog, Install Wizard, And Manager

## Scope

Expose machine-skill services through the desktop app. This subtask adds a machine-scoped Tools entry point, registry-driven tool catalog, command-palette actions, install wizard, manager UI, mutation serialization, stale-request handling, post-mortem presentation, file/directory chooser adapters, and reveal-source adapter.

## Acceptance Criteria

1. The desktop toolbar exposes an accessible **Tools** button next to existing install/setup and creation controls.
2. The Tools button opens a Material 3 dialog rendered from stable tool descriptors containing id, title, short description, icon or marker, mutation risk, availability, and activation action.
3. The initial catalog includes **Install skill to agents** and **Manage installed skills**.
4. The catalog supports keyboard traversal, Enter/Space activation, Escape dismissal, focus restoration to the Tools button, and descriptive accessibility semantics.
5. **Open Tools**, **Install skill to agents**, and **Manage installed skills** are available through the command palette and route to the same controller actions as the toolbar.
6. Tools and machine-skill mutations are usable without an open repository and are not disabled by repository read-only state.
7. The install wizard implements source selection, agent target selection with conflict-aware defaults, exact mutation preview, apply, per-target results, and inventory refresh.
8. Source selection accepts a `SKILL.md` file or directory through JVM adapters and shows skill name, source path, included-file count, total bytes, and validation result before continuing.
9. Target selection shows every supported target with provider, path, detection status, conflict status, and checkbox; all detected conflict-free targets are preselected and at least one selected target is required.
10. The manager presents one logical row per non-product skill name, with search and filters for ownership, health, and agent.
11. Manager detail shows name, description, ownership, provenance, canonical managed-source path, active snapshot hash, every detected target state, content identity, validation issues, and last mutation result.
12. Managed actions expose Edit, Manage agents, Reveal source, Repair when applicable, and Delete through previews and confirmations backed by application services.
13. Unmanaged rows are read-only until adoption, with divergent copies requiring an authoritative source and replacement-target choice.
14. Product skills are excluded from the normal list, with a read-only diagnostic toggle if product diagnostics are exposed.
15. A running machine-skill mutation prevents starting another machine-skill mutation but does not block unrelated repository browsing.
16. Compose and ViewModel code consume presentation-safe state and dispatch controller actions only; filesystem, parser, ownership, hashing, mutation, and product-classification logic remain in shared services.

## Non-Goals

- Left navigator Third-Party Skills projection and managed editor path.
- Headless CLI commands.
- Marketplace, export, health-check catalog tools, or source watching.

## Dependency Notes

Depends on subtasks 1 through 4. Navigator/editor work in subtask 6 should reuse the same gateway, state, inventory refresh, mutation serialization, and post-mortem acknowledgement paths.

## Validation Strategy

Add desktop gateway/ViewModel tests for no-repository behavior, read-only repository behavior, stale request tokens, modal and busy transitions, shared refresh after mutation, result mapping, post-mortem acknowledgement, and reveal/source chooser adapters. Add Compose tests for toolbar button semantics, catalog rendering/dispatch, keyboard/focus behavior, wizard steps, manager grouping and filters, destructive confirmations, and command-palette dispatch.

## Next Path

Continue with `spec_subtask_6_navigator_validation.md`.
