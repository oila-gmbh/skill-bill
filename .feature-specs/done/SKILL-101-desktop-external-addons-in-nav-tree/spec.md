---
status: Complete
issue_key: SKILL-101
source: inline user request in Claude Code session (desktop external-addons feature)
---

# SKILL-101: Surface external add-ons in the desktop navigation tree

## Problem

The Skill Bill desktop app never reads `external_addon_sources` from
`~/.config/skill-bill/config.json`. That config is consumed only by the
install/CLI overlay path (`ExternalAddonOverlayService`,
`FileExternalAddonSourceConfigStore`). The nav tree's "Add-ons" group is built
solely by `SkillTreeBuilder.loadAddons` →
`RepoSourceDiscoveryService.discoverGovernedAddonFiles(root)`, which scans
`<root>/platform-packs/*/addons/` and hard-labels every hit "Pack-owned add-on
source."

As a result, a user who registers a private external add-on source (for
example a Capmo add-on directory declared for platform `ios`) has no way to see
or edit those add-ons from the desktop app:

- Browsing the **source repo**, external add-ons never appear — they live in a
  separate configured directory outside `platform-packs/`.
- Browsing the **installed workspace**, the overlaid copies appear but are
  indistinguishable from pack-owned add-ons, and editing them there is futile
  because `./install.sh` regenerates that directory on the next install.

## Goal

Make external add-on sources first-class in the desktop nav tree: read them
from config, show them inside the existing "Add-ons" group under their target
platform, mark them clearly as external with an "EXT" badge, and make them
editable at their source location so edits persist across installs.

## Decisions (confirmed with the user)

1. **Approach:** browse the external source directories declared in config;
   list each source directory's top-level `.md` files; edit them at source.
2. **Placement:** merge into the existing "Add-ons" group, grouped by target
   platform, each external item carrying an "EXT" badge. No new top-level tree
   group.
3. **Visibility:** show whenever config declares sources, independent of which
   repo/workspace is currently open.

## Proposed solution

### 1. Expose an external-source resolver to the desktop data layer

Reuse the existing resolver rather than re-parsing config:
`ExternalAddonOverlayService.resolveSources(home, environment): List<ExternalAddonSource(path, platform)>`,
reachable via `RuntimeComponent.externalAddonOverlayService`.

In `DesktopRuntimeApplicationServices`
(`runtime-desktop/core/data/.../di/DesktopRuntimeApplicationServices.kt`):
add `externalAddonOverlayService` to the service bundle and expose
`resolveExternalAddonSources(): List<ExternalAddonSource>` that delegates to it
with `currentUserHome()` + `System.getenv()`.

### 2. Add a testable seam

Add an overridable `externalAddonSourcesResolver: () -> List<ExternalAddonSource>`
on `RepoBrowserStore`, defaulting to `runtimeServices.resolveExternalAddonSources()`
— mirroring the existing `baselineModifiedResolver` / `authoringSaver` /
`sourceFileSaver` seams. Expose it on `RuntimeRepoBrowserService` the same way
those seams are exposed. `RuntimeRepoSessionService.open()` passes the resolver
into `SkillTreeBuilder.buildTree`. This lets tests inject a temp source
directory instead of reading the real machine config.

### 3. Merge external add-ons into the tree

In `SkillTreeBuilder.loadAddons`, after pack-owned add-ons are gathered:

- Resolve external sources via the seam, wrapped in `runCatching { … }.getOrDefault(emptyList())`
  so a malformed/unreadable external config fails soft (external add-ons are
  skipped; the pack-owned tree still loads).
- For each source directory that exists, list its top-level `*.md` files and
  build a `SkillBillTreeItem` per file: `kind = ADD_ON`, `editable = true`,
  `external = true`, `contentFile` = the source `.md`, selection `detail` =
  `"External add-on source (from <dir>)."`, placed under the `source.platform`
  sub-group inside "Add-ons".
- **Dedup:** when an external `(platform, slug)` matches a discovered pack-owned
  add-on (the overlaid copy present in the installed workspace), keep the
  external entry (the editable source of truth) and drop the pack-owned
  duplicate, so each logical add-on shows exactly once.

### 4. Domain model

Add `external: Boolean = false` to `SkillBillTreeItem` in
`runtime-desktop/core/domain/.../model/SkillBillModels.kt`.

### 5. UI badge

In `SkillBillNavTree`, render an "EXT" caption badge on the row when
`node.external` is true, mirroring the existing `readOnlyLabel` caption slot and
using `SkillBillTheme.frameTokens.primary` so it reads as a distinct provenance
marker rather than a status.

## Acceptance Criteria

1. The desktop app resolves `external_addon_sources` through the existing
   `ExternalAddonOverlayService.resolveSources` resolver; no config-parsing
   logic is duplicated in the desktop layer.
2. For each declared external source directory, its top-level `*.md` files
   appear under their target-platform sub-group inside the existing "Add-ons"
   group, each with `external = true` on the tree item.
3. An external add-on item is editable and its editor targets the external
   source `.md` file (its `contentFile`/`authoredPath` is the source path, not
   an overlaid installed copy).
4. When an external `(platform, slug)` matches a discovered pack-owned add-on,
   the external entry is kept and the pack-owned duplicate is dropped, so the
   add-on appears exactly once under that platform group.
5. A malformed or unreadable external add-on config does not blank or fail the
   tree: external add-ons are skipped and the pack-owned tree still loads.
6. The nav tree row renders an "EXT" badge when, and only when, the item is
   external.
7. `RuntimeRepoBrowserServiceTest` covers, via the resolver seam and a temp
   source directory: an external add-on appearing under its platform group with
   `external = true` and editable, and the dedup dropping an overlaid pack-owned
   duplicate. Existing add-on tests continue to pass.

## Non-goals / constraints

- Do not modify the install/CLI overlay path or the `external_addon_sources`
  config schema.
- Do not introduce a new top-level tree group; external add-ons live inside the
  existing "Add-ons" group.
- Do not make the overlaid installed copies editable-as-external — the feature
  browses and edits the external **source**, not the overlaid copy.

## Validation strategy

- Unit tests in
  `runtime-desktop/core/data/src/jvmTest/.../RuntimeRepoBrowserServiceTest.kt`
  using the new resolver seam (external add-on visibility, `external` flag,
  editability, source-path targeting, and dedup).
- Module build + test for the touched Gradle modules
  (`runtime-desktop/core/domain`, `runtime-desktop/core/data`,
  `runtime-desktop/feature/skillbill`).
- Manual smoke: with the real `~/.config/skill-bill/config.json` external source
  registered, open the app and confirm the external add-ons show under
  "Add-ons" with the EXT badge and open the source file for editing.

## Affected files (expected)

- `runtime-desktop/core/domain/src/commonMain/kotlin/skillbill/desktop/core/domain/model/SkillBillModels.kt`
- `runtime-desktop/core/data/src/jvmMain/kotlin/skillbill/desktop/core/data/di/DesktopRuntimeApplicationServices.kt`
- `runtime-desktop/core/data/src/jvmMain/kotlin/skillbill/desktop/core/data/service/RepoBrowserStore.kt`
- `runtime-desktop/core/data/src/jvmMain/kotlin/skillbill/desktop/core/data/service/RuntimeRepoSessionService.kt`
- `runtime-desktop/core/data/src/jvmMain/kotlin/skillbill/desktop/core/data/service/SkillTreeBuilder.kt`
- `runtime-desktop/core/data/src/jvmMain/kotlin/skillbill/desktop/core/data/service/RuntimeRepoBrowserService.kt`
- `runtime-desktop/feature/skillbill/src/commonMain/kotlin/skillbill/desktop/feature/skillbill/ui/SkillBillNavTree.kt`
- `runtime-desktop/core/data/src/jvmTest/kotlin/skillbill/desktop/core/data/service/RuntimeRepoBrowserServiceTest.kt`

## Next path

```bash
Run bill-feature-task on .feature-specs/SKILL-101-desktop-external-addons-in-nav-tree/spec.md
```
