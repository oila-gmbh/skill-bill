---
issue_key: SKILL-17
feature_name: addon-shell-pilot
feature_size: MEDIUM
status: Not Started
created: 2026-04-17
depends_on: SKILL-14 (shell+content contract — shipped), SKILL-16 (quality-check shell pilot — shipped)
---

# SKILL-17 — Relocate governed add-ons into `platform-packs/<slug>/addons/`

## Problem

After SKILL-16, the only per-platform content that still lives under `skills/<platform>/` is the governed add-on layer at `skills/<platform>/addons/`. The end-state goal for this repo is to become a pure governance/framework suite with zero per-platform content checked in, extracted into one or more sibling "reference pack" repos. Leaving add-ons straddled across `skills/<platform>/addons/` (stack-owned) and `platform-packs/<slug>/` (user-owned) blocks that extraction: add-ons are *platform*-specific, so they need to travel with their platform pack, not with the framework.

This ticket finishes the straddle SKILL-14 scoped out. The work is signposted by an existing in-tree TODO at `scripts/skill_repo_contracts.py:18–25` ("TODO(SKILL-14 follow-up): migrate `GOVERNED_STACK_ADDONS` to discovery from `platform-packs/<slug>/platform.yaml`"). SKILL-16 left that TODO in place; SKILL-17 retires it.

## Why now

1. **Prerequisite for sibling-repo extraction.** Extracting a platform pack into a sibling repo must include the add-ons it owns. If add-ons remain stack-owned under `skills/<platform>/addons/`, the extraction either leaves add-ons behind (breaks kmp reviews that reference `android-compose-*`) or copies them out-of-band (silent drift). Relocating first makes extraction a clean directory move.
2. **Manifest already half-wired.** `platform-packs/kmp/platform.yaml` already declares `governs_addons: true` and `addon_signals`. The runtime just isn't consulting the manifest yet — it consults the hardcoded `GOVERNED_STACK_ADDONS` dict in `scripts/skill_repo_contracts.py`. Flipping to manifest discovery is mechanical.
3. **Ownership model needs to match the directory.** AGENTS.md currently says "Add-ons are stack-owned supporting assets." That language is a historical artifact of the pre-split layout. The end-state is user-owned: teams fork a platform pack (including its add-ons) and extend/replace as needed. The ownership sentence moves in lockstep with the directory move — not as a separate rewrite.

## Context (what a new-session implementer needs to know)

### What the add-on system looks like today

- **Inventory (kmp is the only platform with add-ons):**
  - `skills/kmp/addons/android-compose-implementation.md`
  - `skills/kmp/addons/android-compose-review.md`
  - `skills/kmp/addons/android-compose-edge-to-edge.md`
  - `skills/kmp/addons/android-compose-adaptive-layouts.md`
  - `skills/kmp/addons/android-navigation-implementation.md`
  - `skills/kmp/addons/android-navigation-review.md`
  - `skills/kmp/addons/android-interop-implementation.md`
  - `skills/kmp/addons/android-interop-review.md`
  - `skills/kmp/addons/android-design-system-implementation.md`
  - `skills/kmp/addons/android-design-system-review.md`
  - `skills/kmp/addons/android-r8-implementation.md`
  - `skills/kmp/addons/android-r8-review.md`

  Twelve files total, flat layout (no nested directories), kebab-case names with `-implementation.md` / `-review.md` / `-<topic>.md` suffixes per AGENTS.md.

- **Source of truth for slug list today:** `scripts/skill_repo_contracts.py::GOVERNED_STACK_ADDONS` (a `dict[str, tuple[str, ...]]`) plus `GOVERNED_ADDON_SUPPORT_FILES` (for the topic files like `-edge-to-edge` / `-adaptive-layouts`). `ADDON_SUPPORTING_FILE_TARGETS` is computed from those two dicts and resolves every add-on symlink target to `skills/<stack>/addons/<slug>-<impl|review>.md`.
- **Manifest plumbing already present:** `platform-packs/kmp/platform.yaml` declares `governs_addons: true` and a `routing_signals.addon_signals` tuple. The `PlatformPack` dataclass in `skill_bill/shell_content_contract.py` has `governs_addons: bool = False` and `RoutingSignals.addon_signals: tuple[str, ...]`. The manifest does NOT yet declare the add-on slug list or file list — that's the missing piece.
- **How add-ons are consumed at runtime:** skills reference add-on content via sibling supporting-file symlinks. For example, `skills/kmp/bill-kmp-code-review/android-compose-review.md` is a symlink to `../../../skills/kmp/addons/android-compose-review.md`. Relocating the target shifts every such symlink.

### Contract extension (design decision — author proposes, planner locks in)

**Proposed: additive.** Keep the existing v1.0 manifest shape. Add one optional top-level key on packs that declare `governs_addons: true`:

```yaml
declared_addons:
  - slug: android-compose
    implementation: addons/android-compose-implementation.md
    review: addons/android-compose-review.md
    topic_files:
      - addons/android-compose-edge-to-edge.md
      - addons/android-compose-adaptive-layouts.md
  - slug: android-navigation
    implementation: addons/android-navigation-implementation.md
    review: addons/android-navigation-review.md
  # …
```

Rationale:
- Explicit list matches the shell+content philosophy (the loader validates what's declared, not what's discovered).
- Per-slug block lets a pack declare only `implementation` or only `review` when an add-on has just one side (not the case today, but cheap to support).
- `topic_files` is an optional list — most add-ons won't have any.
- No `SHELL_CONTRACT_VERSION` bump (the field is optional and forward-compatible, matching SKILL-16's additive pattern).

**Alternative: pure discovery (rejected by spec).** Walk `platform-packs/<slug>/addons/*.md` and infer slugs from filename suffixes. Simpler to author, but silently tolerates typos and orphan files; conflicts with the shell+content philosophy ("the loader validates what's declared"). Spec rejects this — planning may revisit.

**Alternative: reuse existing routing-signal list.** Rely on `routing_signals.addon_signals` as the slug source. Rejected because `addon_signals` is for *routing* (what file markers boost the pack's confidence), not a declaration of content. Keeping the two orthogonal avoids coupling.

### Destination layout

Each file relocates via `git mv` from:

- `skills/<platform>/addons/<name>.md`

to:

- `platform-packs/<platform>/addons/<name>.md`

Flat layout preserved. `git mv` retains rename history (kmp add-ons are already kebab-case files; post-move similarity will be ≈100% since content doesn't change).

### Consumer symlinks

Every runtime skill sidecar that today points at `skills/<stack>/addons/<file>.md` needs repointing to `platform-packs/<stack>/addons/<file>.md`. The authoritative list is `ADDON_SUPPORTING_FILE_TARGETS` (derived dict in `scripts/skill_repo_contracts.py`). Under the new shape:

- Delete the hardcoded `GOVERNED_STACK_ADDONS` + `GOVERNED_ADDON_SUPPORT_FILES` + `ADDON_SUPPORTING_FILE_TARGETS` dicts.
- Replace them with a function `compute_addon_supporting_file_targets() -> dict[str, str]` that walks discovered platform packs, reads each pack's `declared_addons`, and constructs the target map at module load (or lazily).
- `SUPPORTING_FILE_TARGETS` consumes the computed map instead of the static dict.

The TODO at `scripts/skill_repo_contracts.py:18–25` gets removed in the same change.

### Loader extension

`skill_bill/shell_content_contract.py`:

- New `REQUIRED_ADDON_FILE_KINDS = ("implementation", "review")` — every declared slug must carry both unless a future ticket softens this.
- New `AddonDeclaration` dataclass: `slug: str`, `implementation: Path`, `review: Path`, `topic_files: tuple[Path, ...]`.
- Extend `PlatformPack` with `declared_addons: tuple[AddonDeclaration, ...] = ()`.
- Parse `declared_addons` in `_build_pack`; absent is fine (empty tuple); present requires every entry to have `slug`, `implementation`, `review` (others optional). Malformed entries raise `InvalidManifestSchemaError`.
- New `load_addon_content(pack: PlatformPack, slug: str) -> AddonDeclaration` returns the declared entry and asserts each file exists. Missing file → `MissingContentFileError`. No H2 section enforcement: add-on files are freeform reviewer/implementer content, not shell-contracted specialists.
- Cross-check: if a pack sets `governs_addons: true` but has empty `declared_addons`, raise `InvalidManifestSchemaError("'governs_addons: true' requires non-empty 'declared_addons'")`. A pack with `governs_addons: false` (or absent) and a non-empty `declared_addons` is also invalid.

### Ownership language

AGENTS.md and CLAUDE.md both say add-ons are "stack-owned." That's the SKILL-12 framing. Post-SKILL-17 they become user-owned alongside the rest of the platform pack. Update:

- "Governed add-ons" section header unchanged.
- "Store only under `skills/<platform>/addons/`, flat" → "Store only under `platform-packs/<slug>/addons/`, flat."
- "stack-owned supporting assets" → "platform-owned supporting assets that travel with the pack."
- "Resolve add-ons only after dominant-stack routing selects the owning platform" — unchanged; routing semantics don't change.

### Scaffolder implications

`skill_bill/scaffold.py` today has an `add-on` skill kind destination of `skills/<platform>/addons/<name>.md` (per AGENTS.md and `skill_bill/scaffold.py::FAMILY_REGISTRY`). Flip to `platform-packs/<slug>/addons/<name>.md`. Also extend the scaffolder to append the new `declared_addons` entry to the owning pack's `platform.yaml` when kind is `add-on` — idempotent, preserves key order (same helper pattern as `set_declared_quality_check_file` added in SKILL-16, possibly a shared primitive).

### Validator updates

- `scripts/validate_agent_configs.py` already iterates platform packs; extend it to call `load_addon_content(pack, slug)` for every declared slug in packs where `governs_addons` is `true`. Any `InvalidManifestSchemaError` / `MissingContentFileError` surfaces verbatim.
- Remove any add-on-specific enumeration elsewhere in the validator. Routing checks that reference `addon_signals` stay as-is (those are already manifest-driven).
- Existing accept/reject fixture matrix gains four cases (see AC 11 below).

### Install / uninstall

Add-ons are supporting files for skills, not skills themselves — they are not enumerated by `build_skill_names` in `install.sh` or `uninstall.sh`. The installer's interaction with add-ons is through the sidecar symlink graph that `compute_addon_supporting_file_targets()` emits. As long as the new computed map points at the new `platform-packs/<slug>/addons/` paths, skill installation already does the right thing.

Follow the SKILL-16 documentary-comment pattern: no new `RENAMED_SKILL_PAIRS` entries are needed (add-ons aren't skills). Add a one-line comment at the relevant spot in both `install.sh` and `uninstall.sh` noting that SKILL-17 relocated add-ons but the installer is a no-op for them (symlink recomputation handles the change on next run).

### Boundary-history scoping

SKILL-17 touches `skills/kmp/addons/`, `platform-packs/kmp/`, `skill_bill/`, `scripts/`, `tests/`, AGENTS.md / CLAUDE.md, README, `docs/`, and `agent/history.md`. No `agent/decisions.md` entry is needed because the additive contract shape is analogous to SKILL-16's — see that precedent.

## Acceptance criteria

1. **Contract extension documented.** `orchestration/shell-content-contract/PLAYBOOK.md` documents how `declared_addons` is declared in `platform.yaml` when `governs_addons: true`. The declaration shape (list of blocks with `slug`, `implementation`, `review`, optional `topic_files`) is locked in during planning.

2. **Contract version handling.** `SHELL_CONTRACT_VERSION` stays at `"1.0"`. The new key is optional and forward-compatible. No version bump. Packs without `declared_addons` remain contract-compliant; packs that set `governs_addons: true` MUST also provide a non-empty `declared_addons` (cross-check raises `InvalidManifestSchemaError`).

3. **Loader extension.** `skill_bill/shell_content_contract.py` gains `AddonDeclaration`, parses `declared_addons`, exposes `load_addon_content(pack, slug) -> AddonDeclaration`. `MissingContentFileError` fires on missing declared files. `InvalidManifestSchemaError` fires on malformed entries or on `governs_addons` / `declared_addons` disagreement. No silent fallback.

4. **Twelve relocations via `git mv`.** Every existing `skills/kmp/addons/*.md` moves to `platform-packs/kmp/addons/*.md`. GitHub recognizes each move as a rename (≥50% similarity; expected ≈100% since content doesn't change). The old `skills/kmp/addons/` directory is removed cleanly. `skills/kmp/` retains only the skill directories it had, now with no `addons/` subtree.

5. **Manifest updated.** `platform-packs/kmp/platform.yaml` declares the five add-on slugs under `declared_addons`, with `implementation` and `review` paths for each, and the two topic files on `android-compose`. `governs_addons: true` stays. No other pack manifests change.

6. **Shell symlink graph repointed.** `scripts/skill_repo_contracts.py` no longer hardcodes `GOVERNED_STACK_ADDONS` / `GOVERNED_ADDON_SUPPORT_FILES` / `ADDON_SUPPORTING_FILE_TARGETS`. A new `compute_addon_supporting_file_targets()` function derives the map from discovered platform packs' `declared_addons`. `SUPPORTING_FILE_TARGETS` consumes the computed map. The SKILL-14 TODO at lines 18–25 is removed.

7. **Runtime symlinks updated.** Every skill sidecar that points at the old `skills/kmp/addons/<file>.md` path now points at `platform-packs/kmp/addons/<file>.md`. Exact list is whatever `validate_runtime_supporting_files` enforces (driven by the computed map). After this PR, `find skills -type l -lname '*skills/kmp/addons*'` returns zero results.

8. **Scaffolder add-on destination flipped.** `skill_bill/scaffold.py`'s `add-on` skill-kind destination changes from `skills/<platform>/addons/<name>.md` to `platform-packs/<slug>/addons/<name>.md`. The scaffolder appends a new entry to the owning pack's `declared_addons` list in `platform.yaml` idempotently, matching the `set_declared_quality_check_file` pattern from SKILL-16. Rollback on failure is atomic.

9. **Validator coverage.** `scripts/validate_agent_configs.py` validates `declared_addons` on every pack with `governs_addons: true`, calling `load_addon_content` per slug and surfacing named exceptions on failure. No platform enumeration.

10. **Ownership language updated in docs.** AGENTS.md and the project CLAUDE.md both switch the "Governed add-ons" section wording from "stack-owned" to "platform-owned" and update the storage path from `skills/<platform>/addons/` to `platform-packs/<slug>/addons/`. The "Non-negotiable rules" bullet about keeping add-ons stack-owned is rewritten to keep add-ons pack-owned.

11. **Test coverage.** Accept/reject fixtures under `tests/fixtures/shell_content_contract/` cover:
    - valid pack with `governs_addons: true` and a well-formed `declared_addons` list,
    - pack with `governs_addons: true` but empty/missing `declared_addons` → `InvalidManifestSchemaError`,
    - pack with `governs_addons: false` but non-empty `declared_addons` → `InvalidManifestSchemaError`,
    - pack declaring an `implementation` path that doesn't exist on disk → `MissingContentFileError`.
    `tests/test_shell_content_contract.py` gains matching cases. Existing live-pack tests continue to pass and newly assert `kmp`'s `declared_addons` resolves cleanly.

12. **Scaffolder test coverage.** `tests/test_scaffold.py`:
    - Update the existing `add-on` scaffolder test to expect the new `platform-packs/<slug>/addons/` destination and the manifest edit.
    - Add a manifest-write-failure rollback test for the add-on scaffold flow (matches SKILL-16's pattern for quality-check).

13. **Install.sh / uninstall.sh.** No `RENAMED_SKILL_PAIRS` changes. Documentary comment added above the array in both scripts noting that SKILL-17 relocated add-ons and that installer behavior is driven by the computed symlink map, not by rename pairs. Matches the SKILL-16 precedent.

14. **README catalog accuracy.** Every README row or table cell that references the old `skills/kmp/addons/` path updates to `platform-packs/kmp/addons/`. Add-on count and skill count are unchanged (add-ons are not skills and don't contribute to the 48 count).

15. **Docs updated.** `docs/getting-started-for-teams.md` add-on section updates paths and ownership language. `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md` updates the `add-on` worked example to the new destination and manifest-edit shape.

16. **Boundary history.** `agent/history.md` records SKILL-17 in the established format. No `agent/decisions.md` entry — the additive contract choice is analogous to SKILL-16 and doesn't warrant a standalone decision.

17. **No behavior regressions.** Every add-on currently surfaced by `bill-kmp-code-review` / `bill-feature-implement` on an Android or KMP workload surfaces the same content from the new location. The add-on selection flow (`Selected add-ons: <slugs>` report line) is unchanged in wording and behavior. `ADDON_REPORTING_LINE` and `ADDON_IMPLEMENTATION_SUFFIX` / `ADDON_REVIEW_SUFFIX` constants stay (they are format-level invariants, not location-level).

18. **Validation suite passes.** `.venv/bin/python3 -m unittest discover -s tests` passes (expect the count to rise from the new AC 11 / AC 12 tests). `npx --yes agnix --strict .` passes. `.venv/bin/python3 scripts/validate_agent_configs.py` passes.

## Non-goals

- Adding new add-ons (e.g. for `php`, `go`, `backend-kotlin`). This is a relocation ticket, not a content-expansion ticket.
- Promoting add-ons to standalone slash commands or top-level skills. The ownership model stays: add-ons are supporting content consumed by skills via sibling symlinks.
- Changing the add-on selection / routing semantics. Dominant-stack routing still fires first; add-on selection still happens after.
- Extracting `platform-packs/kmp/` (or any other pack) into a sibling repo. That's the follow-up this ticket unblocks.
- Rewriting add-on content. Files move, content does not change.
- Piloting `bill-feature-implement` or `bill-feature-verify` onto the shell+content contract. Those remain pre-shell.
- Softening the "every slug needs both `implementation` and `review`" rule. A future ticket may revisit.
- Introducing an `add-on-area` specialization system (analogous to code-review areas). Add-ons stay flat.

## Open questions to resolve in planning

1. **Single-side add-ons.** The proposed shape allows an add-on to declare only `implementation` or only `review` (the schema marks them "optional after slug"). AC 3 currently says every entry MUST have both. Which is the actual rule? Spec recommends: keep both required in SKILL-17 (matches today's every-add-on-has-both reality) and relax later if a use case arises. Planning confirms.

2. **Topic-file naming convention.** Today topic files like `android-compose-edge-to-edge.md` are identified by suffix heuristic (`<slug>-<topic>.md`). The proposed `topic_files: [...]` list makes them explicit. Should the loader *also* enforce the heuristic naming (topic file basename starts with the slug), or trust the manifest and allow arbitrary filenames under `addons/`? Spec recommends: trust the manifest (the shell+content philosophy). Planning confirms.

3. **Discovery reversal.** Should the loader ALSO flag orphan files in `platform-packs/<slug>/addons/` that are not declared (e.g. a stray `.md` that nobody references)? Spec recommends: yes, raise `OrphanAddonFileError` — consistent with "no silent drift." Planning confirms or picks softer wording (warning instead of loud-fail).

4. **Shared helper for scaffolder manifest edits.** SKILL-16 added `set_declared_quality_check_file`. SKILL-17 needs `append_declared_addon` (or similar). Should these be reified into a shared `scaffold_manifest` primitive for all future declared-content extensions, or kept as per-family helpers? Spec recommends: keep per-family helpers for now; refactor into a shared primitive when the third extension lands.

5. **Rejection-fixture coverage depth.** AC 11 lists four rejection modes. Are there others worth covering (e.g. `declared_addons` entry with only a slug and no paths, duplicate slugs)? Planning confirms the final fixture list.

6. **What to do with `GOVERNED_ADDON_SUPPORT_FILES`.** Today it's a dict that maps `android-compose` → `(edge-to-edge.md, adaptive-layouts.md)`. Under the new shape these become the `topic_files` on the `android-compose` entry. No standalone constant survives. Planning confirms the constant is deleted outright.

## Scenario mock-ups (for context)

### Scenario A — kmp's post-migration manifest

```yaml
# platform-packs/kmp/platform.yaml
platform: kmp
contract_version: "1.0"
governs_addons: true
routing_signals:
  strong:
    - build.gradle.kts
    - settings.gradle.kts
  tie_breakers: [...]
  addon_signals:
    - androidx.compose
    - compose.material3
# ... existing code-review declarations unchanged ...
declared_addons:
  - slug: android-compose
    implementation: addons/android-compose-implementation.md
    review: addons/android-compose-review.md
    topic_files:
      - addons/android-compose-edge-to-edge.md
      - addons/android-compose-adaptive-layouts.md
  - slug: android-navigation
    implementation: addons/android-navigation-implementation.md
    review: addons/android-navigation-review.md
  - slug: android-interop
    implementation: addons/android-interop-implementation.md
    review: addons/android-interop-review.md
  - slug: android-design-system
    implementation: addons/android-design-system-implementation.md
    review: addons/android-design-system-review.md
  - slug: android-r8
    implementation: addons/android-r8-implementation.md
    review: addons/android-r8-review.md
```

### Scenario B — malformed manifest (rejection)

```yaml
governs_addons: true
# declared_addons omitted
```

Loader raises `InvalidManifestSchemaError("'governs_addons: true' requires non-empty 'declared_addons'")`. Validator fails loudly.

### Scenario C — missing file

```yaml
declared_addons:
  - slug: android-compose
    implementation: addons/android-compose-implementation.md
    review: addons/android-compose-review.md
    topic_files:
      - addons/android-compose-edge-to-edge.md
# …but android-compose-edge-to-edge.md was never moved
```

`load_addon_content(pack, "android-compose")` raises `MissingContentFileError("platform-packs/kmp/addons/android-compose-edge-to-edge.md")`.

## Files expected to change

Created / relocated (under `platform-packs/kmp/addons/` via `git mv` from `skills/kmp/addons/`):
- 12 files, one per existing add-on content file.

Modified:
- `skill_bill/shell_content_contract.py` — `AddonDeclaration`, `declared_addons` parsing, `load_addon_content`, cross-check, `__all__`
- `skill_bill/scaffold.py` — `add-on` destination flip, manifest-edit wiring
- `skill_bill/scaffold_manifest.py` — new `append_declared_addon` helper (or equivalent)
- `platform-packs/kmp/platform.yaml` — add `declared_addons` block
- `orchestration/shell-content-contract/PLAYBOOK.md` — document the new key and required shape
- `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md` — update the `add-on` worked example
- `scripts/skill_repo_contracts.py` — delete hardcoded dicts, add `compute_addon_supporting_file_targets()`, delete SKILL-14 TODO
- `scripts/validate_agent_configs.py` — validate `declared_addons` dynamically
- `install.sh`, `uninstall.sh` — documentary comments only
- `tests/test_shell_content_contract.py` — accept/reject coverage
- `tests/test_scaffold.py` — updated add-on case + rollback test
- `AGENTS.md`, `CLAUDE.md` — ownership language + paths
- `README.md` — catalog path updates
- `docs/getting-started-for-teams.md` — add-on section path/ownership update
- `agent/history.md` — SKILL-17 entry

Not modified:
- Any `platform-packs/<other>/` manifest (only kmp governs add-ons today)
- Base skills (`skills/base/*`) — their content is stack-agnostic
- `bill-feature-implement` / `bill-feature-verify` skills
- Telemetry contract / telemetry ceremony — unchanged

## Feature flag

N/A. Contract migration, not user-facing runtime behavior.

## Backup / destructive operations

`git mv` operations per AC 4. No `rm -rf`. Content unchanged, so rename similarity is ≈100%.

## Validation strategy

`bill-quality-check` auto-routes to `bill-agent-config-quality-check` for this repo. The canonical triad must pass:

```
.venv/bin/python3 -m unittest discover -s tests
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```

## References

- SKILL-12 spec: `.feature-specs/SKILL-12-addon-system/spec.md` — established the add-on system (stack-owned, `skills/<platform>/addons/`). SKILL-17 flips its ownership model.
- SKILL-14 spec: `.feature-specs/SKILL-14-code-review-shell-pilot/spec.md` — contract baseline; scoped add-on discovery out; left the TODO SKILL-17 retires.
- SKILL-16 spec: `.feature-specs/SKILL-16-quality-check-shell-pilot/spec.md` — additive-contract-extension pattern SKILL-17 mirrors.
- In-tree TODO: `scripts/skill_repo_contracts.py:18–25` ("TODO(SKILL-14 follow-up): migrate `GOVERNED_STACK_ADDONS` to discovery…").
- Runtime add-on consumers: `skills/kmp/bill-kmp-code-review/`, `skills/kmp/bill-kmp-feature-implement/` (symlinks into `addons/`).
- Roadmap context: `docs/ROADMAP.md` — SKILL-17 is the final precondition for extracting platform packs into a sibling reference repo.
