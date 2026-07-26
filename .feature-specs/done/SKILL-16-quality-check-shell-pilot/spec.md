---
issue_key: SKILL-16
feature_name: quality-check-shell-pilot
feature_size: LARGE
status: Complete
created: 2026-04-17
depends_on: SKILL-14 (shell+content contract — shipped), SKILL-15 (new-skill scaffolder — shipped)
---

# SKILL-16 — Pilot `bill-quality-check` onto the shell+content contract

## Problem

After SKILL-14 only `bill-code-review` runs on the shell+content contract. `bill-quality-check`, `bill-feature-implement`, and `bill-feature-verify` still live on the pre-shell layout (`skills/<platform>/bill-<platform>-<capability>/`). This split leaves the repo carrying two separate skill layouts and blocks a clean extraction of per-platform skills into a sibling repo (follow-up work): any external repo would inherit the same straddle.

The natural next pilot is `bill-quality-check` because:

- Its shell already exists at `skills/base/bill-quality-check/SKILL.md` and is effectively a routing surface (detect stack → delegate). That's exactly the shape SKILL-14 formalized.
- The family is single-file per platform (no areas), which is a simpler contract shape than code-review. Lands the pattern for single-file shelled families without reshaping the code-review canon.
- Four platform overrides exist today (`go`, `kotlin`, `php`, `agent-config`); migration is mechanical.
- SKILL-15 gave us a scaffolder that can author new shelled skills into `platform-packs/<slug>/quality-check/<name>/` as soon as the family registry flips. The pilot is both the first consumer of that path and the justification for flipping it.

## Why now

Prerequisite for cleanly extracting per-platform skills out of this repo into a governed "reference skills" sibling repo: that extraction needs every family to live on one consistent layout. Extracting pre-shell overrides now would bake the straddle into the new repo and force a second migration later.

## Context (what a new-session implementer needs to know)

### What `bill-quality-check` looks like today

- **Shell at `skills/base/bill-quality-check/SKILL.md`** — already thin: detects stack, routes to `bill-<stack>-quality-check`, passes context through. Not yet wired to the shell+content contract (no sibling-sidecar symlinks, no loud-fail on missing platform, no manifest-driven discovery).
- **Four platform overrides**, all at the pre-shell layout:
  - `skills/agent-config/bill-agent-config-quality-check/SKILL.md` (77 lines)
  - `skills/go/bill-go-quality-check/SKILL.md`
  - `skills/kotlin/bill-kotlin-quality-check/SKILL.md` (97 lines — the most developed)
  - `skills/php/bill-php-quality-check/SKILL.md`
- **No `kmp` or `backend-kotlin` quality-check overrides exist today.** The pilot should not invent them; follow-up tickets can scaffold them after the contract flip.

### Single-file family, not area-structured

Unlike code-review, quality-check has **no specialist areas**. A platform gets exactly one quality-check skill (run the canonical validation commands, fix issues at root). The contract extension must represent that shape without forcing a fake `areas` block.

### Contract extension (design decision — author proposes, planner locks in)

Two shapes are on the table. **Proposed: additive.** Structural is listed for completeness so the planner can make the explicit call.

**Additive (proposed).** Keep the existing v1.0 manifest shape. Add a sibling top-level key:

```yaml
declared_quality_check_file: quality-check/bill-<slug>-quality-check/SKILL.md
```

Packs that already ship code-review content leave `declared_code_review_areas` and `declared_files` untouched; packs that add quality-check add the new key. No `SHELL_CONTRACT_VERSION` bump (the field is optional and forward-compatible). Loader gains a `load_quality_check_content(pack)` helper that parallels `load_code_review_content(pack)` and raises the same named exceptions on missing file / missing required section.

**Structural (alternative).** Reshape `declared_files` into a nested map keyed by family:

```yaml
declared_files:
  code-review:
    baseline: code-review/bill-<slug>-code-review/SKILL.md
    areas:
      architecture: ...
  quality-check:
    baseline: quality-check/bill-<slug>-quality-check/SKILL.md
```

This is cleaner for future single-file families but requires a `SHELL_CONTRACT_VERSION` 1.0 → 2.0 bump, a synchronized update across every pack + the shell constant + the loader, and a migration path for forks already on 1.0. SKILL-14 spec explicitly said "bump the shell constant and every pack together when the contract evolves" — doable, but heavy.

The spec recommends additive. Planning may override if it finds a reason to pay the bump cost now.

### Destination layout

Each platform's quality-check content relocates via `git mv` from:

- `skills/<platform>/bill-<platform>-quality-check/SKILL.md`

to:

- `platform-packs/<platform>/quality-check/bill-<platform>-quality-check/SKILL.md`

`git mv` preserves rename history (≥50% similarity required for GitHub's rename detection; current files are nearly identical post-move, so this is a given).

Sidecar symlinks for each relocated skill: `stack-routing.md`, `telemetry-contract.md` (mirroring `RUNTIME_SUPPORTING_FILES["bill-quality-check"]` today). No `review-orchestrator.md` or `review-delegation.md` — those are code-review-specific.

### Scaffolder registry flip

`skill_bill/scaffold.py::FAMILY_REGISTRY`:

```python
"quality-check": {
  "layout_kind": "shelled",              # was "pre-shell"
  "base_path_template": "platform-packs/{platform}/quality-check/{name}",  # was "skills/{platform}/{name}"
  "is_shelled": True,                    # was False
}
```

`skill_bill/constants.py::PRE_SHELL_FAMILIES` drops `quality-check`, leaving `("feature-implement", "feature-verify")`.

### Migration rules (install.sh)

Every user who has `bill-<platform>-quality-check` symlinked under an agent path will have a stale symlink pointing at the old `skills/<platform>/bill-<platform>-quality-check/` directory. `install.sh::RENAMED_SKILL_PAIRS` already handles this pattern for SKILL-14 relocations. Add pairs for each relocated quality-check skill so existing installs auto-heal on next `./install.sh`.

### Validator updates

- `scripts/validate_agent_configs.py` extends its platform-pack walk to also validate `declared_quality_check_file` (or the structural equivalent) using the same loader + named exceptions.
- `scripts/skill_repo_contracts.py::RUNTIME_SUPPORTING_FILES`: the entry for `bill-quality-check` is unchanged (still `stack-routing.md`, `telemetry-contract.md`) because the router name doesn't move; per-platform entries for `bill-<platform>-quality-check` keep the same sidecar set but now resolve against the pack location. The validator must enforce that every relocated pack has those two sidecars present as symlinks to the canonical playbooks.
- Add accept/reject fixtures under `tests/fixtures/shell_content_contract/` covering: valid pack with quality-check only; valid pack with code-review + quality-check; pack declaring `declared_quality_check_file` that doesn't exist; pack declaring quality-check content missing a required H2 section.

## Acceptance criteria

1. **Contract extension documented.** `orchestration/shell-content-contract/PLAYBOOK.md` documents how quality-check content is declared in `platform.yaml`. The final shape (additive vs structural) is locked in during planning and applied consistently across this PR.

2. **Contract version handling.** If the planner chooses additive, `SHELL_CONTRACT_VERSION` stays at `"1.0"` and the new manifest key is marked optional. If the planner chooses structural, `SHELL_CONTRACT_VERSION` is bumped, every shipped pack is migrated in the same PR, and the loader's `ContractVersionMismatchError` message tells forks how to upgrade.

3. **Loader extension.** `skill_bill/shell_content_contract.py` exposes a way to load a pack's quality-check content (e.g. `load_quality_check_content(pack)`) that enforces the same rules as code-review content — missing file raises `MissingContentFileError`, missing required H2 section raises `MissingRequiredSectionError`, etc. No silent fallback.

4. **Family registry flip.** `skill_bill/scaffold.py::FAMILY_REGISTRY["quality-check"]` is updated to the shelled layout (`platform-packs/<platform>/quality-check/<name>`). `skill_bill/constants.py::PRE_SHELL_FAMILIES` drops `quality-check`. Adding a new pre-shell family in the future still requires updating that tuple.

5. **Four relocations via `git mv`.** Every existing `skills/<platform>/bill-<platform>-quality-check/SKILL.md` moves to `platform-packs/<platform>/quality-check/bill-<platform>-quality-check/SKILL.md`. GitHub recognizes each relocation as a rename (≥50% similarity). The old `skills/<platform>/bill-<platform>-quality-check/` directories are removed cleanly; no orphan sibling files remain.

6. **Sidecar symlinks wired per relocated skill.** Each relocated quality-check skill ships with symlinks for `stack-routing.md` and `telemetry-contract.md` pointing at the canonical orchestration playbooks. Matches `RUNTIME_SUPPORTING_FILES["bill-quality-check"]` today. Uses sibling-symlink paths — no repo-relative references at runtime.

7. **Platform manifests updated.** The four platform-packs (`agent-config`, `go`, `kotlin`, `php`) declare their quality-check content in `platform.yaml` under the chosen shape. `kmp` and `backend-kotlin` manifests are left alone (no quality-check content exists for them yet). Manifest edits preserve key order and comments.

8. **Shell wired to contract.** `skills/base/bill-quality-check/SKILL.md` is updated to (a) use manifest-driven discovery for routing (like `bill-code-review` does post-SKILL-14), (b) loud-fail with a specific named exception when a routed pack is missing or invalid, (c) reference the contract loader explicitly. The six required H2 sections from SKILL-14 are not required for the shell itself — the shell remains a horizontal skill — but sibling-sidecar refs (`stack-routing.md`, `telemetry-contract.md`, `shell-content-contract.md`) must be wired if the shell is treated as a contract-validated skill.

9. **Routing playbook alignment.** `orchestration/stack-routing/PLAYBOOK.md` is already manifest-driven after SKILL-14; no enumerated platform names should be added for quality-check routing. Confirm by inspection.

10. **Validator coverage.** `scripts/validate_agent_configs.py` validates quality-check declarations on every platform pack, raising the shell+content contract's named exceptions on failure. Validator discovers packs dynamically via the existing `_discover_allowed_packages` / `discover_platform_packs` walk.

11. **Install.sh migration rules.** `RENAMED_SKILL_PAIRS` entries are added for each of the four relocations so existing installs automatically clean up stale symlinks on next run. Matches the pattern SKILL-14 used.

12. **Uninstall.sh parity.** Any change to `install.sh` that affects how quality-check skills are installed is mirrored in `uninstall.sh` so teardown stays symmetric.

13. **Test coverage.** Accept/reject fixtures under `tests/fixtures/shell_content_contract/` cover:
    - valid pack with quality-check only (no code-review),
    - valid pack with code-review + quality-check both declared,
    - pack declaring `declared_quality_check_file` (or the structural equivalent) that doesn't exist → `MissingContentFileError`,
    - pack declaring quality-check content missing a required H2 section → `MissingRequiredSectionError`.
    `tests/test_shell_content_contract.py` gains the matching cases. `tests/test_platform_packs.py` (or equivalent) continues to enumerate live packs and asserts the manifest reads cleanly under the loader.

14. **Scaffolder happy path.** `tests/test_scaffold.py` adds a `kind: platform-override-piloted` case for `family: quality-check` that lands at `platform-packs/<platform>/quality-check/<name>/` (the newly shelled layout). The existing pre-shell test case for `quality-check` is updated to use `feature-implement` instead, preserving coverage for pre-shell behavior on the remaining pre-shell families.

15. **README catalog accuracy.** README skill counts, section tables, and platform-coverage tables are updated: per-platform quality-check skills move from the pre-shell "skills/<platform>/" column to the relocated platform-pack column. The total skill count (today 48) stays the same — this is a relocation, not an add.

16. **Docs updated.** `docs/getting-started-for-teams.md` adds a short note about quality-check being shelled in the same way as code-review. `AGENTS.md` updates the "Governed platform packs" section to reflect that `quality-check` now joins `code-review` on the shell+content contract; the sentence about "`bill-quality-check`, `bill-feature-implement`, `bill-feature-verify` stay on the pre-shell model" is narrowed to only the remaining pre-shell families.

17. **Boundary history + decisions.** `agent/history.md` records the pilot in the established format. If the planner picks the structural option and bumps the contract, a `agent/decisions.md` entry captures the version-bump rationale.

18. **No behavior regressions.** Running `bill-quality-check` end-to-end against any of the four platforms produces the same result it did pre-migration (same command invocations, same fix heuristics, same output format). This is a relocation + contract wiring, not a content rewrite.

19. **Existing validation suite still passes.** `.venv/bin/python3 -m unittest discover -s tests` (currently 236 tests) passes. `npx --yes agnix --strict .` passes. `.venv/bin/python3 scripts/validate_agent_configs.py` passes. Expect new tests from AC 13 to push the count up.

## Non-goals

- Piloting `bill-feature-implement` or `bill-feature-verify` onto the shell+content contract. Those remain pre-shell; they are their own tickets.
- Adding new per-platform quality-check skills (e.g. for `kmp` or `backend-kotlin`). Only migrate what exists today.
- Changing the behavior or command surface of any individual quality-check skill. Content is moved, not rewritten.
- Adding a quality-check specialist area system (e.g. "lint", "tests", "format" as separate specialists). Quality-check is intentionally single-file per platform.
- Extracting per-platform skills into a sibling `skill-bill-skills` repo. That is the follow-up work this pilot unblocks.
- Rewriting the generic `bill-quality-check` router's fix heuristics. Shell stays thin.
- Telemetry contract changes. Quality-check telemetry already lives behind the shared sidecar.

## Open questions to resolve in planning

1. **Contract shape — additive vs structural.** Spec recommends additive (no version bump). Planning confirms or flips. If flipping, plan must cover the synchronized bump across shell + every pack + loader + error message + fork-migration note.
2. **Single-file family discovery path.** Should the loader expose `load_quality_check_content(pack)` as a dedicated function, or a generic `load_family_content(pack, family)` that code-review also adopts? Pick one; the second reshapes code-review call sites too, so scope must be bounded.
3. **Should `skills/base/bill-quality-check/SKILL.md` be re-validated against the shell+content contract's six H2 section list?** It's a horizontal shell, not a platform-pack content file — probably no. Confirm.
4. **Sidecar set for quality-check.** Spec assumes `stack-routing.md` + `telemetry-contract.md` matching today's `RUNTIME_SUPPORTING_FILES["bill-quality-check"]`. Confirm no additional sidecars are needed for the relocated per-platform skills.
5. **Rename-pair format in install.sh.** Spec assumes reusing the SKILL-14 pattern verbatim. Confirm and enumerate the four pairs in planning.
6. **Required H2 sections for quality-check content files.** Code-review packs require six sections (Description, Specialist Scope, Inputs, Outputs Contract, Execution Mode Reporting, Telemetry Ceremony Hooks). Quality-check content files are run-and-fix scripts today and carry different sections (Execution Steps, Fix Strategy, Code Style Guidelines, etc.). Two sub-questions:
   - Do we enforce the same six sections on quality-check content (requiring additions in each pack)?
   - Or do we declare a quality-check-specific required-section set in the loader?
   Spec recommends: declare a quality-check-specific set (`Description`, `Execution Steps`, `Fix Strategy`, plus the two scaffolder-owned sections `Execution Mode Reporting` and `Telemetry Ceremony Hooks`). Planning locks in the exact list.

## Scenario mock-ups (for context)

### Scenario A — Kotlin pack adds quality-check

```yaml
# platform-packs/kotlin/platform.yaml (additive extension)
platform: kotlin
contract_version: "1.0"
# ... existing code-review declarations unchanged ...
declared_code_review_areas:
  - architecture
  - performance
  - platform-correctness
  - security
  - testing
declared_files:
  baseline: code-review/bill-kotlin-code-review/SKILL.md
  areas:
    architecture: code-review/bill-kotlin-code-review-architecture/SKILL.md
    # ...
declared_quality_check_file: quality-check/bill-kotlin-quality-check/SKILL.md
```

### Scenario B — `agent-config` pack (quality-check with no code-review areas)

```yaml
platform: agent-config
contract_version: "1.0"
# ... routing signals, declared_code_review_areas omitted for brevity ...
declared_quality_check_file: quality-check/bill-agent-config-quality-check/SKILL.md
```

### Scenario C — Pack declaring quality-check file that doesn't exist

```yaml
declared_quality_check_file: quality-check/bill-kotlin-quality-check/SKILL.md
# …but the file was never created
```

Loader raises `MissingContentFileError("platform-packs/kotlin/quality-check/bill-kotlin-quality-check/SKILL.md")`. Validator fails loudly. No silent fallback to the old `skills/kotlin/bill-kotlin-quality-check/SKILL.md`.

## Files expected to change

Created (under `platform-packs/<platform>/quality-check/bill-<platform>-quality-check/` via `git mv` from the old location):
- `platform-packs/agent-config/quality-check/bill-agent-config-quality-check/SKILL.md` (moved)
- `platform-packs/go/quality-check/bill-go-quality-check/SKILL.md` (moved)
- `platform-packs/kotlin/quality-check/bill-kotlin-quality-check/SKILL.md` (moved)
- `platform-packs/php/quality-check/bill-php-quality-check/SKILL.md` (moved)
- sibling-sidecar symlinks (`stack-routing.md`, `telemetry-contract.md`) for each relocated skill
- `tests/fixtures/shell_content_contract/quality_check_only/` (and sibling rejection fixtures)
- possibly `tests/fixtures/shell_content_contract/quality_check_missing_file/`, `quality_check_missing_section/`

Modified:
- `skill_bill/shell_content_contract.py` — loader extension + (if chosen) contract version constant
- `skill_bill/scaffold.py` — family registry flip for `quality-check`
- `skill_bill/constants.py` — drop `quality-check` from `PRE_SHELL_FAMILIES`
- `orchestration/shell-content-contract/PLAYBOOK.md` — document the declaration
- `skills/base/bill-quality-check/SKILL.md` — wire contract loader + loud-fail; reference sibling sidecars
- `platform-packs/agent-config/platform.yaml`
- `platform-packs/go/platform.yaml`
- `platform-packs/kotlin/platform.yaml`
- `platform-packs/php/platform.yaml`
- `scripts/validate_agent_configs.py` — validate quality-check declarations on every pack
- `scripts/skill_repo_contracts.py` — only if sidecar rules need to reflect the relocation (probably no change; `RUNTIME_SUPPORTING_FILES["bill-quality-check"]` already lists the right files)
- `install.sh` — `RENAMED_SKILL_PAIRS` entries for the four relocations
- `uninstall.sh` — mirror the relocation so teardown works
- `tests/test_shell_content_contract.py` — accept/reject coverage for quality-check
- `tests/test_platform_pack_kotlin.py` and peers — live-pack assertions for quality-check
- `tests/test_scaffold.py` — update the pre-shell family case, add a shelled-family-quality-check case
- `README.md` — reflect the relocated per-platform quality-check skills in the catalog
- `docs/getting-started-for-teams.md` — note the shelling
- `AGENTS.md` — narrow the "pre-shell" sentence to the remaining families
- `agent/history.md` — pilot entry
- `agent/decisions.md` — only if the structural option is chosen and the version bump warrants a decision entry

Not modified:
- Any `skills/<platform>/` directory besides the four being relocated (add-ons and other content stay in place)
- Any code-review content under `platform-packs/<slug>/code-review/`
- `bill-feature-implement` or `bill-feature-verify` skills

## Feature flag

N/A. Contract migration, not user-facing runtime behavior.

## Backup / destructive operations

`git mv` operations per AC 5 are the only destructive moves. No `rm -rf`. The SKILL-14 pilot established that ≥69% similarity on renames is readily achieved for these content moves; expect the same here.

## Validation strategy

`bill-quality-check` (auto-routes to `bill-agent-config-quality-check` for this repo, which is itself being shelled in this PR — use the current pre-shell version until the relocation lands, then the shelled version afterward). The canonical triad must pass:

```
.venv/bin/python3 -m unittest discover -s tests
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```

## References

- SKILL-14 spec: `.feature-specs/SKILL-14-code-review-shell-pilot/spec.md` — architectural parent. This ticket applies the same pattern to quality-check.
- SKILL-15 spec: `.feature-specs/SKILL-15-new-skill-scaffolder/spec.md` — the scaffolder's `FAMILY_REGISTRY` and pre-shell note are the moving parts this pilot flips.
- Shell+content contract: `orchestration/shell-content-contract/PLAYBOOK.md`.
- Contract loader: `skill_bill/shell_content_contract.py`.
- Scaffolder family registry: `skill_bill/scaffold.py::FAMILY_REGISTRY`.
- Existing shell: `skills/base/bill-quality-check/SKILL.md`.
- Pre-shell overrides being migrated: `skills/{agent-config,go,kotlin,php}/bill-<platform>-quality-check/SKILL.md`.
- Roadmap context: `docs/ROADMAP.md` — this ticket is the prerequisite for extracting per-platform skills into a sibling reference repo.
