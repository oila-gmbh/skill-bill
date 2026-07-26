# SKILL-53 Shared Runtime Install Architecture - Subtask 3: Desktop Adapter And Migration

Parent overview: [spec.md](spec.md)  
Issue key: SKILL-53  
Branch model: same-branch (`feat/SKILL-53-shared-runtime-install-architecture`); commit on completion before subtask 4.

## Scope

Move desktop install-choice replay onto the shared install-selection store while keeping desktop-only preferences desktop-owned.

This subtask owns the desktop read/write integration:

- Update desktop first-run setup so successful setup persists install selections through the shared runtime store instead of maintaining a separate desktop-only install preference format.
- Update post-publish reinstall replay in the desktop skillbill feature to read the latest shared install-selection record through an adapter/gateway rather than `DesktopFirstRunPreferences` install fields.
- Preserve desktop-only settings, including recent repo path and UI-only state, in the desktop preference store.
- Add migration/backward compatibility for existing desktop preference files containing `firstRun.*` keys. Existing users should still get a reusable latest install selection without losing desktop-only preferences.
- Keep the desktop gateway mapped to existing shared install plan/apply services; do not add shell-specific behavior to desktop.

## Acceptance Criteria

1. Desktop first-run setup uses shared install-selection persistence for reusable install choices.
2. Desktop post-publish reinstall uses the shared install-selection persistence instead of owning or reading a separate desktop install preference format.
3. Existing desktop-only preferences such as recent repo path and UI-only state remain desktop-owned.
4. Existing desktop preference files with legacy `firstRun.*` install keys migrate or adapt into the shared install-selection record.
5. Desktop JVM code depends only on shared runtime APIs for install selection, not CLI or shell behavior.

## Non-Goals

- Do not change the first-run wizard prompts or user-visible install selection semantics.
- Do not remove desktop-only preference storage for recent repo path or UI state.
- Do not modify CLI or `install.sh` behavior except to adapt to any shared API refinements needed for desktop.
- Do not persist failed desktop install attempts as reusable latest selections.

## Dependencies

Depends on subtask 1 for the shared store contract and implementation. Depends on subtask 2 for the successful apply persistence behavior that desktop post-publish reinstall will share with non-desktop installs.

## Validation Strategy

Primary: `bill-quality-check`.

Recommended local focus before the full check:

```bash
(cd runtime-kotlin && ./gradlew :runtime-desktop:core:datastore:test :runtime-desktop:core:data:test :runtime-desktop:feature:skillbill:test)
```

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-53-shared-runtime-install-architecture/spec_subtask_3_desktop-adapter-migration.md`.

