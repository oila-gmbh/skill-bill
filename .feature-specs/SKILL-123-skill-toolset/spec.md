# SKILL-123 — Machine Skill Toolset

Created: 2026-07-13  
Status: Draft  
Spec source: local  
Sources: user request; existing desktop toolbar and first-run installer; current content-addressed install cache and symlink safety contracts

## Outcome

The Skill Bill desktop app exposes a machine-level **Tools** entry point for operations that apply to agent skills rather than the currently open Skill Bill repository. Its first tools let a user import a third-party skill, install it to all or selected detected agents through Skill Bill-owned symlinks, and inspect, edit, adopt, retarget, or remove non-product skills without duplicating copies across agent directories. The main left navigator also exposes the same inventory under its own collapsible **Third-Party Skills** group, so installed third-party skills remain visible during normal repository work without requiring the Tools dialog to be open.

Skill Bill remains the owner of every skill mutated by these tools. Imported source and metadata live under `~/.skill-bill/`; immutable installed snapshots live in the existing `~/.skill-bill/installed-skills/` cache; each agent receives only a symlink to the active snapshot. Editing a managed skill creates a new validated snapshot and atomically retargets its managed links. No success path copies a skill directly into an agent's skills directory.

## Problem

Agent skills are commonly installed independently into Claude, Codex, Copilot, OpenCode, Junie, or ZCode directories. This produces duplicated files, divergent versions, unclear ownership, and repetitive maintenance whenever the same skill should be available to more than one agent.

Skill Bill already solves the distribution half of this problem for its own governed skills: it detects agents, stages content under a central cache, and links agent skill directories to that cache. The desktop app does not yet expose that model for third-party skills, and it has no machine-wide inventory that separates Skill Bill product skills, Skill Bill-managed third-party skills, and unmanaged skills installed by other means.

## Product Decisions

1. **Tools are machine-scoped.** The Tools button remains available even when no repository is open or the current repository is read-only. Tool state is not stored in the open repo.
2. **“All agents” means all detected targets.** The default selection includes every supported agent target detected by the shared runtime. Users may deselect agents. Multiple target directories for one provider, such as multiple Claude profiles, remain distinct targets and are all shown.
3. **Agent installations are symlink-only.** Agent directories contain links to Skill Bill-managed snapshots. There is no file-copy fallback, including on Windows. When symlinks are unavailable, the operation fails before mutation with the existing actionable preflight guidance.
4. **Imported skills are opaque runtime skills, not governed repository sources.** A third-party `SKILL.md` is not converted into Skill Bill's authored `content.md` model and is never written under this repository's `skills/` tree. The governed source/generated boundary remains unchanged.
5. **Inventory and ownership are separate.** The manager discovers all non-product entries in detected agent skill directories, but mutations are enabled only for entries whose ownership can be proved by Skill Bill metadata and links. An unmanaged entry must be adopted before it can be edited or centrally deleted.
6. **No name-only ownership inference.** A `bill-*` prefix alone is not enough to classify ownership. Product skills are identified from the active Skill Bill installation/baseline plus protected names; managed third-party skills are identified by their managed record and cache target; everything else is unmanaged.
7. **Mutations are planned, previewed, and applied transactionally.** Import, adoption, edit, target changes, and deletion show their affected source, snapshot, and agent links before applying. Conflicts and partial failures are visible and never silently overwritten.
8. **The tool catalog is extensible.** The Tools dialog renders tool descriptors from one registry rather than hard-coding separate toolbar buttons for every future utility.
9. **Third-party skills are first-class navigation items.** The left panel projects the shared machine inventory into a dedicated group; it does not rescan agent directories or maintain a second classification model.

## User Experience

### Tools entry point

Add a **Tools** button to the main workspace toolbar, next to the existing install/setup and creation controls. It opens a Material 3 dialog containing the available tool descriptors. Each descriptor has a stable id, title, short description, icon/marker, mutation risk, availability state, and activation action.

Initial entries:

- **Install skill to agents** — import a skill from the filesystem and link it to all or selected detected agents.
- **Manage installed skills** — inventory non-product machine skills and manage Skill Bill-owned ones.

The Tools dialog supports keyboard traversal, Enter/Space activation, Escape dismissal, focus restoration to the Tools button, and descriptive accessibility semantics. Opening the catalog is non-mutating. A running mutation disables starting another machine-skill mutation but does not wedge unrelated repository browsing.

The command palette also exposes **Open Tools**, **Install skill to agents**, and **Manage installed skills**, all routed to the same actions as the toolbar.

### Left-panel Third-Party Skills group

Add a top-level collapsible **Third-Party Skills** entry to the existing left navigator. It is a machine-scoped sibling of the repository-backed groups, not a child of `Horizontal Skills`, `Platform Packs`, or `Repository`.

- The group is present whether or not a repository is open. It shows an empty-state child when no third-party skills are found.
- Its badge shows the number of logical non-product skills, deduplicated by skill name across agent targets.
- Child rows come from the same inventory snapshot used by **Manage installed skills** and show concise ownership/health badges such as `managed`, `unmanaged`, `conflict`, `broken`, or `divergent`.
- Expanding or refreshing the group triggers the shared machine-inventory load with stale-request protection. A successful install, adoption, edit, target change, repair, or deletion refreshes both this group and an open manager dialog from one result.
- Selecting a managed child opens its canonical managed `SKILL.md` in the center editor, clearly labeled **Third-party runtime skill** and distinct from governed `content.md`. It uses the same validate-restage-retarget save path as the manager's Edit action.
- Selecting an unmanaged child opens a read-only source/details view with an **Adopt into Skill Bill** action. Divergent same-name copies first require choosing which agent copy to inspect or adopt.
- The inspector shows ownership, canonical source/snapshot where applicable, installed agents, link health, and conflicts. Product Skill Bill skills never appear in this group.
- Collapse state is preserved for the desktop session. Keyboard tree navigation, focus, selection, and accessibility semantics match the existing navigator behavior.

### Install skill to agents

Use a four-step wizard:

1. **Choose source**
   - Accept either a `SKILL.md` file or a directory containing a root `SKILL.md`.
   - Choosing `SKILL.md` imports that file. Choosing a directory imports the root `SKILL.md` and its regular supporting files recursively.
   - Reject nested symbolic links, special files, paths escaping the selected directory, a missing or duplicate root `SKILL.md`, and an unreadable source.
   - Parse the root frontmatter and require a non-empty `name` and `description`. The name must be a safe single path segment and must not collide with `.bill-shared`, a protected product skill, or an existing unmanaged path on a selected target.
   - Show the resolved skill name, source path, included-file count, total bytes, and validation result before continuing.
2. **Choose agents**
   - Show every supported agent target with provider, detected path, detection status, current name conflict, and selection checkbox.
   - Preselect all detected, conflict-free targets.
   - Require at least one selected target. Unsupported or undetected targets are informational and cannot be selected in v1.
3. **Review plan**
   - Show the canonical managed-source path, future snapshot path/hash, every link to create or retarget, conflicts, and any obsolete managed links that will be removed.
   - For an already managed skill with the same content, offer an idempotent target-selection update rather than creating another source record.
   - For an already managed skill with changed content, label the action **Update managed skill** and preserve current links until the replacement snapshot is ready.
4. **Apply and result**
   - Copy the validated source into Skill Bill's managed source store, write/update its managed record, stage an immutable content-addressed snapshot, and create/retarget only the selected agent links.
   - Report per-target created, unchanged, retargeted, skipped, warning, or failed outcomes.
   - Refresh the machine-skill inventory after success or warning.

### Manage installed skills

The manager opens with a fresh scan and presents one logical row per skill name, not one row per agent copy. Search and filters cover ownership (`Managed`, `Unmanaged`, `Conflict`), health (`Healthy`, `Missing link`, `Broken link`, `Divergent`, `Name collision`), and agent.

Each skill detail shows:

- name and description;
- ownership/provenance;
- canonical managed-source path and active snapshot hash when managed;
- every detected agent target and its state;
- whether all discovered copies resolve to identical bytes;
- validation issues and last mutation result.

Product Skill Bill skills do not appear in the main list. A count and **Show product skills (read-only)** diagnostic toggle may expose them for troubleshooting, but edit, adopt, retarget, and delete actions remain disabled.

Managed-skill actions:

- **Edit** opens the root `SKILL.md` in a focused editor. Save validates first, writes the managed source atomically, stages a new snapshot, retargets all currently selected managed links, and removes the old snapshot only when it has no remaining managed references. A failed validation or retarget leaves the prior source/snapshot/links active.
- **Manage agents** changes the target set through a preview. Deselection unlinks only a symlink proven to belong to that managed skill; selection refuses an unmanaged collision.
- **Reveal source** opens the canonical managed source in the operating system file manager.
- **Delete** previews every owned agent link, managed-source path, record, and unreferenced snapshot to remove. It requires explicit confirmation. It never removes an unmanaged file or a link whose target changed after preview.

Unmanaged-skill behavior:

- Unmanaged skills are visible and read-only.
- **Adopt into Skill Bill** imports a selected discovered copy, validates it, and replaces only explicitly selected equivalent copies with managed symlinks after a preview.
- When same-name unmanaged copies have different content, the row is `Divergent`; adoption requires choosing the authoritative copy and explicitly selecting which agent copies may be replaced.
- Direct editing or central deletion is disabled until adoption. The UI explains that Skill Bill cannot safely mutate content owned by another installer.

### Empty, conflict, and recovery states

- With no detected agents, both tools explain how detection works and offer a refresh; install cannot advance past agent selection.
- A same-name unmanaged file/directory or external symlink is preserved and reported as a conflict.
- A broken Skill Bill-owned link is repairable from the manager when its source record is healthy.
- A managed record with missing source or a hash mismatch is reported as `Corrupt`; no destructive automatic repair occurs.
- If rollback cannot restore the pre-operation state, preserve a machine-skill post-mortem containing affected paths and recovery guidance until the user acknowledges it.

## Managed Storage and Identity

Use an explicit machine-managed source layer so editable source is never confused with immutable cache output:

```text
~/.skill-bill/
  managed-skills/
    <skill-name>/
      record.json
      source/
        SKILL.md
        ...supporting files...
  installed-skills/
    <skill-name>-<content-hash>/
      SKILL.md
      ...supporting files...
      .content-hash
```

Agent directories contain:

```text
<agent-skills-dir>/<skill-name> -> ~/.skill-bill/installed-skills/<skill-name>-<content-hash>/
```

`record.json` is a versioned runtime contract containing at least the logical name, source kind, normalized source path, active content hash, selected agent/provider target identities, import/update timestamps, and format version. Add a strict Draft 2020-12 schema under `orchestration/contracts/`, a matching Kotlin contract-version constant and parity test, a typed `InvalidManagedSkillRecordSchemaError`, and loud-fail validation at every read seam. Do not put this internal contract in the desktop repository tree.

The content hash covers normalized relative paths and bytes for the complete imported bundle. Cache reuse verifies every expected file and `.content-hash`; it must not trust the directory name alone.

## Runtime and Architecture

Machine-skill behavior belongs behind shared runtime services, not Compose code and not a desktop-only filesystem walker.

Recommended boundaries:

- domain models for managed skill source, inventory entry, ownership, health, agent target state, mutation plan, outcome, and typed conflicts;
- application services for discovery/inventory, import/adopt planning, mutation apply, edit/update, target reconciliation, repair, and removal;
- filesystem ports/adapters that reuse the existing agent detection, installed-cache identity, Windows symlink preflight, safe-link replacement, and transaction/rollback primitives;
- a desktop domain gateway that maps shared typed results into UI state;
- a feature controller/ViewModel slice for catalog, wizard, manager, the left-panel inventory projection, editor, stale-request tokens, and post-mortem state;
- JVM file/directory chooser and reveal adapters kept outside `commonMain`.

Agent providers and target paths must come from the existing runtime `InstallAgent`/agent-target discovery surface. Do not duplicate the provider list or hard-code home paths in desktop code. If multiple callers need richer discovery than the current service exposes, promote that behavior to a shared port/service and keep CLI, installer, and desktop aligned.

The mutation executor rechecks every preview precondition immediately before writing: source identity, selected target existence, existing link type/target, name availability, active record version/hash, and symlink capability. The apply order is source temp-write, validation, snapshot temp-write, atomic snapshot publication, atomic link replacement, record publication, then safe cleanup. Rollback restores prior managed links and record on a recoverable failure.

## Safety and Validation Rules

- Never follow an imported bundle's symbolic links and never import filesystem special files.
- Never overwrite a regular file, directory, or symlink outside Skill Bill's managed roots.
- Never unlink by name alone; verify link ownership and the expected target captured by the plan.
- Never edit an installed cache snapshot in place.
- Never delete a snapshot while any discovered or recorded managed link still targets it.
- Reserve protected product names and `.bill-shared`; reject collisions before staging.
- Treat path traversal, absolute bundle-relative paths, case-folding name collisions, and unsafe path segments as typed validation failures.
- Normalize and compare real paths carefully without resolving through a target that has not yet been proven safe.
- Preserve cancellation semantics and return typed per-target outcomes for warnings/failures.
- Keep absolute paths out of telemetry. No imported skill content is emitted to telemetry at any level.
- Use the existing preview/confirmation and partial-mutation post-mortem patterns rather than creating an unrelated destructive-action model.

## Acceptance Criteria

1. The desktop toolbar exposes an accessible **Tools** button that opens an extensible tool catalog, and the catalog initially contains **Install skill to agents** and **Manage installed skills**.
2. The Tools catalog and both initial tools are usable without an open repository and are also reachable through the command palette; repository read-only state does not disable machine-skill management.
3. Install accepts a `SKILL.md` file or a directory with one root `SKILL.md`, validates required `name`/`description` frontmatter and safe naming, includes regular supporting files for a directory import, and rejects symlinks, special files, traversal, unreadable content, missing/duplicate roots, and protected-name collisions before mutation.
4. Agent choices come from the shared runtime detector, preserve multiple target paths for the same provider, preselect all detected conflict-free targets, allow any non-empty subset, and clearly distinguish detected, undetected, selected, and conflicting targets.
5. “Install to all agents” creates or retargets a symlink in every selected detected agent skill directory to one Skill Bill-managed immutable snapshot; it never copies the skill into agent directories and never uses a copy fallback when symlinks are unavailable.
6. Imported editable source and a versioned managed record live under `~/.skill-bill/managed-skills/<name>/`; immutable snapshots live under the existing `~/.skill-bill/installed-skills/<name>-<hash>/`; imported third-party skills never enter governed repository `skills/` or platform-pack source trees.
7. The managed-skill record has a strict runtime schema, matching Kotlin contract version/parity coverage, a typed invalid-schema error, and loud-fail validation at every record read seam.
8. Reinstalling identical content for an already managed skill is idempotent and can update its selected agent set without duplicating source or snapshots; importing changed content is presented as an update and keeps the old installation active until the new snapshot is fully validated.
9. Install, update, adoption, agent-target change, repair, and deletion all produce a read-only preview of exact managed-source, snapshot, and agent-link mutations and revalidate those preconditions immediately before apply.
10. The manager inventories one logical row per non-product skill name across all detected agent target directories and reports ownership, provenance, validation, content identity, per-agent presence, broken links, name collisions, and divergent same-name copies.
11. Product Skill Bill skills are excluded from the normal management list; if shown through the diagnostic toggle, they are read-only and cannot be adopted, edited, retargeted, or deleted.
12. A managed skill can be edited through its canonical `SKILL.md`; save validates the candidate, atomically publishes a new content-addressed snapshot, retargets all selected owned links, updates the record, and leaves the prior source/snapshot/links active on validation or apply failure.
13. **Manage agents** adds/removes selected targets using only verified Skill Bill-owned symlinks, preserves unmanaged collisions, and reports a per-target result.
14. **Delete** removes every verified managed link selected by the preview, then removes the managed record/source and only unreferenced snapshots; it refuses a link whose target or type changed after preview and requires explicit confirmation.
15. Unmanaged non-product skills are visible but read-only until adoption; adoption validates a chosen source copy and converts only explicitly selected copies to Skill Bill-managed symlinks. Divergent same-name copies require the user to choose the authoritative source and replacement targets.
16. Existing unmanaged regular files, directories, and external symlinks are never overwritten, edited, or removed by import, update, reconciliation, repair, or delete paths.
17. Broken owned links can be repaired from a healthy managed record; corrupt records, missing managed source, hash mismatches, and orphan snapshots are reported without automatic destructive repair.
18. Every mutation is transactional to the extent supported by the filesystem, restores the prior record and links after a recoverable failure, and emits a persistent actionable post-mortem when rollback cannot be proven complete.
19. Windows symlink preflight uses the existing runtime contract and stops before mutation with elevation/Developer Mode guidance when symlinks are unavailable.
20. Tests cover tool registry rendering/dispatch, keyboard and accessibility behavior, file/directory import validation, multi-provider and multi-profile discovery, idempotent install/update, all-vs-subset target selection, ownership classification, product exclusion, unmanaged/divergent adoption, edit restaging, target reconciliation, conflict preservation, broken-link repair, delete preview/apply, stale-plan rejection, rollback/post-mortem, Windows preflight, and end-to-end verification that selected agent entries are symlinks to one managed snapshot.
21. The implementation reuses shared application/runtime services and filesystem primitives; Compose/UI code contains no independent agent-path table, skill ownership rules, content-hash algorithm, record parser, symlink mutation logic, or product-skill classifier.
22. The relevant desktop and runtime checks pass, followed by the repository validation gates: `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.
23. The left navigator contains a persistent collapsible **Third-Party Skills** group backed by the shared machine inventory; it deduplicates rows by logical name, displays ownership/health state, refreshes after every mutation, opens managed `SKILL.md` source through the governed managed-skill edit path, opens unmanaged items read-only with adoption guidance, and never lists product Skill Bill skills.

## Non-Goals

- A public marketplace, remote registry, Git URL installer, or automatic update feed for third-party skills.
- Installing native-agent definitions or MCP servers bundled beside a third-party skill.
- Converting arbitrary third-party `SKILL.md` content into governed `content.md` source.
- Direct mutation or deletion of skills owned by another installer; users adopt them first.
- Editing arbitrary supporting files in the first release; the managed bundle is preserved, while the built-in editor edits the root `SKILL.md`.
- Creating agent directories for software that is not detected as present.
- Copy-based compatibility for filesystems or Windows configurations without symlink support.
- Repository Git operations, publishing, sharing, or team synchronization.
- Automatic deduplication of differently named skills with identical bytes.

## Validation Strategy

- Domain policy tests use in-memory records/targets for ownership, protected names, divergent copies, mutation plans, and stale-plan decisions.
- Filesystem integration tests use temporary homes containing regular entries, relative and absolute links, broken links, multiple Claude targets, cache snapshots, corrupt records, and simulated failures at each mutation phase.
- Desktop ViewModel tests cover stale request tokens, modal/busy transitions, left-tree inventory projection and refresh, cross-surface selection persistence, result mapping, and post-mortem acknowledgement.
- Compose tests cover the Tools catalog, wizard steps, manager grouping/filters, the collapsible Third-Party Skills group and its managed/unmanaged selection states, confirmation affordances, keyboard traversal, focus restoration, and semantics.
- An end-to-end smoke imports a fixture bundle to at least two fake detected agent targets, asserts both entries are symlinks to the same hash snapshot, edits the skill and asserts both links retarget, then deletes it and asserts unrelated unmanaged entries remain byte-for-byte unchanged.

## Product Follow-ups and Suggestions

1. Add a headless `skill-bill managed-skill list|import|adopt|update|remove` surface after the shared service stabilizes. It would make the desktop behavior scriptable without turning the UI into the source of truth.
2. Add **Export managed skill** as the next low-risk tool. Exporting the canonical source bundle makes backups and sharing explicit without coupling v1 to a marketplace.
3. Add **Health check / repair links** as a separate catalog tool once inventory data exists. It can summarize broken, missing, divergent, or orphaned state without mixing diagnosis into normal installation.
4. Add optional “watch source and restage” only as an explicit developer mode. Automatic watching by default would undermine immutable snapshots and make agent behavior change unexpectedly mid-session.
5. Keep adoption explicit. It is tempting to automatically claim any identical same-name skill found across agents, but provenance is more important than convenience for destructive management.

## Next Path

Run the decomposed goal workflow:

```bash
skill-bill goal SKILL-123
```
