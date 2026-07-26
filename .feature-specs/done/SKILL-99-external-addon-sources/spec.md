# SKILL-99 — External Addon Sources

## Outcome

skill-bill can load code-review addons from a **user-configured external source** that lives outside the skill-bill tree (a local directory). External addons are re-applied on every install and survive updates and `--clean`, so private or org-specific review knowledge (first consumer: a private iOS review addon shipped under SKILL-98) is never wiped by the reconcile. No private content ever enters skill-bill's public repo.

## Background / Motivation

Addons today are **pack-owned**: markdown under `platform-packs/<slug>/addons/*.md`, wired via that pack's `platform.yaml` (`addon_usage` + `pointers`), installed by the reconcile. The reconcile (`install reconcile --apply`) is the sole writer of `skills/` and `platform-packs/`. It is a per-skill three-way hash compare; non-skill pack files (`platform.yaml`, `addons/*.md`) are adopted from upstream with **copy-over-local semantics on every apply** (`adoptPlatformPackNonSkillFiles`), and `--clean` wipes `~/.skill-bill/platform-packs` entirely. Reconcile never deletes local-only files, but it unconditionally overwrites the installed `platform.yaml` from upstream.

This makes any out-of-repo addon non-durable. SKILL-98 shipped a private iOS addon as gitignored `platform-packs/ios/addons/acme-*.md` files plus skip-worktree'd `platform.yaml` wiring. The private `.md` files survive (local-only files are never deleted), but every install re-adopts the upstream `platform.yaml` and clobbers the local wiring — and `--clean` removes everything. The stopgap manual-reapply script is fragile and breaks whenever upstream `platform.yaml` changes.

The fix: a first-class **external addon source** — a self-describing addon directory at a durable path outside `~/.skill-bill`, applied by a dedicated install-flow overlay step that runs **after reconcile-apply and before skill staging**, and re-applied unconditionally on every install.

---

## Implementation Map (where the code lives)

All paths repo-relative to the repo root. Read these before coding.

**Reconcile (the thing that clobbers local wiring):**
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/reconcile/InstallReconcileApply.kt` — `applyReconciliation()` (L37); `adoptPlatformPackNonSkillFiles()` (L84) is the copy-over-local of `platform.yaml` + `addons/*.md`, called last (L72).
- Application entry: `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/install/InstallService.kt` — `applyReconcile()` (L95).
- CLI: `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/install/InstallCliCommands.kt` — `InstallReconcileCommand` (L104, name `"reconcile"`), `--apply` (L129).

**`~/.skill-bill/config.json` store (where the new key lives):**
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/FileTelemetryConfigStore.kt` — reads/writes the file; preserves unknown top-level keys via `payload.toMutableMap()` (L85, L116). **At the detekt `TooManyFunctions` limit (10)** — fold new logic into an existing function or add a new class, do not add a standalone function here.

**Config-reader command pattern to copy:**
- `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/config/ConfigCommand.kt` — `ConfigResolveSpecTypeCommand` (L39) and `ConfigResolveParallelAgentCommand` (L104) are the templates. Subcommands registered at `ConfigCommand.subcommands()` (L28). (These read the repo-local `.skill-bill/config.yaml`; the new one reads machine-global `~/.skill-bill/config.json` — different file, same command shape.)
- Service layer: `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/config/ConfigResolutionService.kt`.

**Platform-pack model + parser + coherence validators:**
- Data classes: `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/scaffold/model/ScaffoldModels.kt` — `PointerSpec(skillRelativeDir, name, target)` (L24), `GovernedAddonUsage` (L60), `GovernedAddonSelection(slug, entrypoint, companionPointers)` (L65), `PlatformManifest` (L71).
- Parser + the named coherence checks: `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/platformpack/ShellContentLoader.kt` — `loadPlatformManifest()` (L31), `parsePointers()` (L621, `pointers-unique-name-per-dir` L653), `parseAddonUsage()` (L662), `parseAddonUsageEntry()` (L720, `addon-usage-unique-slug-per-dir` L727), `requirePackOwnedAddonPointer()` (L751, `addon-usage-pointer-target-must-be-addon` L758).
- Schema doc: `orchestration/contracts/platform-pack-schema.yaml` — `addon_usage` (L275), coherence-checks block (L343–L364).
- JSON-schema validator: `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/platformpack/PlatformPackSchemaValidator.kt` — `validate(parsedYaml, slug)` (L61).

**Staging (`~/.skill-bill/installed-skills/` content-hash cache):**
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/apply/InstallApply.kt` — `applyInstallPlan()` (L25).
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/staging/InstallStagingIdentity.kt` — `installedSkillsCacheRoot`, content-hash identity.

**The ordering seam (CRITICAL — this is in bash, not Kotlin):**
- `install.sh` — `reconcile_and_commit_authored_source()` (L1018) runs `install reconcile --apply` (L1107), writing `skills/` + `platform-packs/`. The staging phase (`install apply`) runs later (main sequence ~L2602, summary L2644). **The new overlay step is invoked from `install.sh` BETWEEN these two.**

**CLI registration / DI:**
- `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/core/CliCommandGroups.kt` — `UtilityCliCommandGroup` (L46) injects `ConfigCommand` (L61); `TopLevelCliCommands` (L80).
- DI: `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/core/CliComponent.kt`.

**Existing SKILL-98 iOS artifacts (item to migrate):**
- `platform-packs/ios/addons/acme-*.md` — 12 files, **local-only, git-ignored** via a `.git/info/exclude` entry `platform-packs/ios/addons/acme-*.md`.
- `platform-packs/ios/platform.yaml` — **skip-worktree** (`git ls-files -v` → `S`); holds the local `acme` wiring. `addon_usage` block at L66.
- Note: `platform-packs/ios/addons/offline-*.md` (4 files) are **tracked/public** — they are NOT private and STAY in the repo. Only the `acme-*` family + its wiring migrates out.

**Test layout (match these):**
- Reconcile/install: `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/install/` — `InstallReconcileApplyTest.kt`, `InstallStagingTest.kt`, `InstallApplyTest.kt` (+ `InstallApplyTestSupport.kt`).
- Schema/coherence: `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/scaffold/` — `PlatformPackSchemaViolationsTest.kt`.
- CLI runtime: `runtime-kotlin/runtime-cli/src/test/kotlin/skillbill/cli/` — `CliConfigResolveSpecTypeRuntimeTest.kt` (template), driven via `CliRuntime.run(listOf(...), CliRuntimeContext())`.
- Config store: `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/infrastructure/fs/FileTelemetryConfigStoreTest.kt`.

---

## Recommended Architecture

Two Kotlin CLI commands + one `install.sh` insertion. (Names are recommendations; keep the acceptance criteria as the contract.)

1. **`config resolve-external-addons`** (new subcommand under `ConfigCommand`) — reads `~/.skill-bill/config.json`, parses/validates `external_addon_sources`, prints the resolved sources. Loud-fails on malformed JSON or a missing `path`. Satisfies AC2 and the `validate` surface (AC6). Copy `ConfigResolveSpecTypeCommand`.

2. **`install apply-external-addons`** (new subcommand under the install group) — the overlay step. Resolves the same config, then for each source performs the merge (below). Loud-fails atomically (no partial state). Invoked by `install.sh`.

3. **`install.sh` wiring** — call `install apply-external-addons` immediately after `install reconcile --apply` returns (inside/after `reconcile_and_commit_authored_source`, L1018) and before the staging `install apply` phase (~L2602). Guard empty-config as a no-op. Keep bash 3.2 compatible; guard empty-array expansions.

Putting the merge logic in Kotlin (not bash) is deliberate: it must be unit-testable and must reuse the existing addon/pointer parser and coherence rules.

---

## Scope

### 1. Config surface

`~/.skill-bill/config.json` gains an optional array:

```json
{
  "external_addon_sources": [
    { "path": "/home/me/private-review-addons/ios", "platform": "ios" }
  ]
}
```

- `path` — absolute path to a durable directory outside `~/.skill-bill` (v1). Git URL/ref is out of scope (Non-Goals) but the field shape must not preclude adding a `git`/`ref` variant later.
- `platform` — the platform-pack slug the source's addons target (e.g. `ios`).
- Absent/empty array ⇒ **current behavior exactly** (no overlay, no new output, zero cost).
- Array order is meaningful: sources apply in config order → deterministic merge.
- `config.json` is today the telemetry document (`FileTelemetryConfigStore`), which preserves unknown top-level keys, so the new key is safe for older runtimes. External sources are machine-global (like installed packs), which is why this file — not repo-local `.skill-bill/config.yaml` — is the right home.

### 2. External source layout (self-describing)

An external source directory contains:

- addon markdown files (any names; e.g. `acme-persistence.md`)
- an **`addon-manifest.yaml`** fragment carrying **only** the `addon_usage` and `pointers` entries to merge into the target pack, in the exact schema `platform.yaml` uses for those blocks. Pointer `target`s are written **relative to the external source dir**; on merge the overlay rewrites them to the canonical `platform-packs/<slug>/addons/<file>.md` form (the coherence check `addon-usage-pointer-target-must-be-addon` accepts only that form).

Example `addon-manifest.yaml`:

```yaml
pointers:
  code-review/bill-ios-code-review-persistence:
    - name: acme-persistence
      target: acme-persistence.md          # source-relative; rewritten on merge
addon_usage:
  code-review/bill-ios-code-review-persistence:
    - slug: acme-persistence
      entrypoint: acme-persistence
      companion_pointers: []
```

The fragment validates against the same rules pack-owned addons obey: bare-filename pointer names, `addon_usage` slug/entrypoint/companion_pointers shape, and every referenced addon file exists in the source dir. Because the coherence validator and pointer rendering require targets to physically exist under the installed pack, external addon markdown **must be copied into the installed pack's `addons/` dir** — it cannot be referenced in place.

### 3. Install-flow external-overlay step (the merge)

The overlay is **not** a reconcile layer (reconcile has no layer pipeline and re-adopts upstream `platform.yaml` every apply — that is what erases the SKILL-98 wiring). It is a distinct step run from `install.sh` after reconcile-apply, before staging, unconditionally on every install.

For each configured source, in config-array order, whose `platform` matches an installed pack:

1. **Copy** the source's addon `.md` files into `~/.skill-bill/platform-packs/<platform>/addons/`.
2. **Merge** the fragment's `addon_usage` and `pointers` into the installed `~/.skill-bill/platform-packs/<platform>/platform.yaml`:
   - rewrite each pointer `target` to canonical `platform-packs/<platform>/addons/<file>.md` form;
   - **additive** — append external entries after pack-owned entries in the matching skill dir's lists;
   - **any collision is a loud failure, never a silent overwrite** — a slug or pointer-name that collides with a pack-owned addon, or with another external source in the same skill dir.
3. A source whose `platform` matches **no** installed pack is **skipped with a visible warning** (not silent, not fatal).

No pointer-file regeneration is needed: specialists never read `platform.yaml` or pointer paths at runtime. Addon content is **inlined at staging time** into the content-hash-keyed `~/.skill-bill/installed-skills/` cache. Because the overlay mutates `platform.yaml` + `addons/` before staging runs, the affected skills re-render and re-stage automatically, and external addons resolve exactly like pack-owned ones.

**Durability comes from unconditional re-application, not exemption bookkeeping:** reconcile clobbers `platform.yaml` from upstream each apply; the overlay re-merges on top afterward; so a normal update AND `--clean` both reconstitute the external addons from the source. The copied `addons/*.md` are local-only non-skill files — reconcile never deletes them and never reports them as upstream conflicts (true today, no new machinery).

### 4. Validation & failure behavior

- The config-reader command (and, transitively, the install path) validate each configured source's manifest against the addon schema and verify referenced files exist.
- **Loud-fail with an actionable message** on: malformed `config.json`; a `path` that does not exist; a malformed `addon-manifest.yaml`; an `addon_usage` entrypoint/companion with no matching file; a slug/pointer-name collision in the same skill dir (external vs pack-owned AND external vs external).
- Existing per-dir coherence rules (`pointers-unique-name-per-dir`, `addon-usage-unique-slug-per-dir`) still apply to the merged `platform.yaml`. The overlay must detect cross-source collisions **itself**, since schema uniqueness is per-dir and knows nothing about provenance.
- **A loud failure aborts the install without partially applying an overlay** (no partial state — validate everything before writing anything, or write to a temp and swap).

### 5. First consumer — migrate the SKILL-98 private iOS addon

Move the **`acme-*`** private iOS review addon out of the skill-bill tree into an external source directory at a durable path outside `~/.skill-bill`:

- its per-area `acme-*.md` files, plus an `addon-manifest.yaml` carrying their `addon_usage`/`pointers` wiring for the `ios` code-review skills;
- register the directory in `~/.skill-bill/config.json` `external_addon_sources`;
- **remove the in-tree workaround**: the `.git/info/exclude` entry `platform-packs/ios/addons/acme-*.md`, the skip-worktree state on `platform-packs/ios/platform.yaml` (`git update-index --no-skip-worktree`), and the local `acme` wiring inside that `platform.yaml` — so the working tree matches upstream and is clean;
- leave the tracked public `offline-*.md` addons untouched;
- verify a fresh `./install.sh` AND a `./install.sh --clean` reconstitute the private addon from the external source with per-specialist selective loading intact.

## Acceptance Criteria

1. `~/.skill-bill/config.json` supports an optional `external_addon_sources: [{ path, platform }]` array; when absent or empty, install/reconcile/validate behavior is observably identical to today (no overlay, no new output, no new failure modes).
2. A new runtime command resolves and validates external-addon config from `~/.skill-bill/config.json`, loud-failing on malformed JSON or a non-existent `path`, and printing the resolved sources on success. Telemetry ownership of `config.json` is untouched; `FileTelemetryConfigStore` unknown-key preservation keeps both readers compatible.
3. An external source is self-describing: a directory of addon `.md` files plus an `addon-manifest.yaml` carrying `addon_usage` + `pointers` fragments in the existing schema; the fragment validates against the same addon/pointer schema rules as pack-owned addons, and source-relative pointer targets are rewritten to `platform-packs/<slug>/addons/<file>.md` form on merge.
4. The install flow applies an external-overlay step **after `install reconcile --apply` and before staging** that copies the source's `.md` into the installed pack's `addons/`, merges the fragment's `addon_usage`/`pointers` into the installed `platform.yaml` (sources applied in config order), and the subsequent staging re-renders/re-stages the affected skills so the external addon content is inlined into the installed-skills cache.
5. The overlay is re-applied unconditionally on every install, so a normal update AND a `--clean` install both reconstitute the external addons even though reconcile re-adopts the upstream `platform.yaml` each apply; overlay-copied `addons/*.md` files are never reported as upstream conflicts or deleted by reconcile.
6. Install/validate loud-fails (aborting without partial application) on: malformed config, missing source `path`, malformed manifest, an `addon_usage` entrypoint/companion referencing a missing file, or a same-skill-dir slug/pointer-name collision (external vs pack-owned or external vs external). A source targeting a platform that is not installed is skipped with a visible warning.
7. The SKILL-98 private iOS addon (`acme-*`) is migrated to an external source directory + `addon-manifest.yaml`, registered in `~/.skill-bill/config.json`; the in-tree workaround (`.git/info/exclude` `acme-*` entry, skip-worktree on `platform-packs/ios/platform.yaml`, local `acme` wiring in that file) is removed; the tracked public `offline-*.md` addons are untouched; and `git status` is clean with the working tree matching upstream.
8. After migration, both a normal `./install.sh` and a `./install.sh --clean` reconstitute the private addon into `~/.skill-bill` with per-specialist selective loading intact (each iOS specialist resolves only its own per-area file), and no private/external addon content exists anywhere under skill-bill's git-tracked tree.
9. New/changed runtime behavior (config resolution, manifest validation, the install-flow overlay step and its ordering, pointer-target rewriting, collision failures) is covered by tests at the level the surrounding reconcile/install code is tested.

## Non-Goals

- Publishing private/external addon content into skill-bill's public repo.
- A remote addon registry or discovery service; v1 resolves a local directory path only (git URL/ref deferred, though the config shape must not preclude it).
- Changing how pack-owned (in-repo) addons work; external addons are strictly additive.
- Per-user auth/secrets management beyond filesystem permissions.
- Authoring new skill content beyond migrating the existing SKILL-98 private iOS addon.
- Desktop-app awareness of the overlay: the app's baseline/drift view may show overlaid entries as drift; teaching it about the overlay layer is deferred. The overlay must merely not corrupt the workspace the app reads.
- Preserving overlay content through a user-invoked full `uninstall.sh` (the external source dir is durable; reinstall reconstitutes).

## Constraints

- **Public repo cleanliness:** no external/private content in skill-bill's git-tracked tree; the feature must make the current gitignore + skip-worktree workaround unnecessary.
- **Single owned write path:** the install flow remains the sole writer of `skills/` and `platform-packs/`. The overlay runs inside it (after reconcile-apply, before staging), not as an independent writer. The overlay must **not mutate reconcile-owned skill dirs** — doing so changes their content hash and turns the next reconcile into a spurious `KeepLocal`/`Conflict`. It touches only `addons/` and `platform.yaml`, then lets staging re-render the skills.
- **Ordering is correctness, not optimization:** overlay after reconcile (or the merge is clobbered by upstream adoption) and before staging (or inlined skill content misses the external addons).
- **Zero-config parity:** users with no `external_addon_sources` see no behavior change and no new failure modes.
- **bash 3.2:** `install.sh` must stay bash 3.2 compatible (macOS ships 3.2); guard empty-array expansions and the empty-config no-op.

## Validation Strategy

- **Kotlin unit/integration tests** for: config resolution (valid/malformed/missing path); manifest schema validation (valid/malformed/missing-file; slug and pointer-name collisions incl. external-vs-external); pointer-target rewriting; the overlay step (merge survives upstream `platform.yaml` adoption; deterministic multi-source ordering; skipped-with-warning on non-installed platform; re-applied after `--clean`; no partial state on failure).
- **Staging integration:** after the overlay, affected skills re-render and external addon content is inlined into the installed-skills cache (content-hash changes; specialist pointer files carry the external text).
- **End-to-end:** register an external source, run `./install.sh` and `./install.sh --clean`, assert the addon files + merged `platform.yaml` entries + re-staged skills appear in `~/.skill-bill` both times.
- **Migration check:** `git status` clean, working tree matches upstream, no private/external addon content in the tracked tree; a fresh clone + install with the external source configured reproduces the full per-area private addon.
- **Regression:** with no `external_addon_sources` configured, install/reconcile output is unchanged from the current baseline.

## Next Path

```bash
Run bill-feature-task on .feature-specs/SKILL-99-external-addon-sources/spec.md
```

## Status

- Status: Complete
- Agent: opencode
