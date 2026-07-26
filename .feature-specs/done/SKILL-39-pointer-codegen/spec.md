# SKILL-39: pointer-file codegen for platform-packs

Status: Complete

Sources: feature briefing inline (no external doc); originating Linear/issue key SKILL-39. Builds on SKILL-37/38 native-agent codegen patterns in `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/`.

## Context

Today, `platform-packs/<platform>/<category>/<skill>/` contains 60 hand-authored "pointer" .md files — each holds a single relative-path line like `../../../../orchestration/shell-content-contract/shell-ceremony.md`. Three patterns:

1. **Orchestration pointers** — `review-orchestrator.md`, `shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`, `review-scope.md`, `review-delegation.md`, `specialist-contract.md` — point at `orchestration/<thing>/{PLAYBOOK,shell-ceremony,specialist-contract}.md`. Repeated across every code-review and quality-check specialist.
2. **Addon pointers** — e.g. `android-compose-adaptive-layouts.md` — point at `platform-packs/<platform>/addons/<topic>.md`. Repeated across kmp specialists that share an addon.

These are read by the agent following markdown links from `SKILL.md` (e.g. `[shell-ceremony.md](shell-ceremony.md)`). The skill itself is symlinked into `~/.claude/commands/<skill>` at install, so the agent reads pointer-file contents directly through the symlink.

Pain points:
- Brittle relative paths (`../../../../`); typos exist today (e.g. `../../../..//orchestration/...` with a double slash, present in 3 files).
- Adding a new specialist means hand-typing 3 pointer files with the right `..` depth.
- Moving `orchestration/` would touch all 60 files.
- No single source of truth for "which pointers does a specialist need".

This feature is repo-time codegen + validation, mirroring the SKILL-37/38 native-agent regenerate/validate model. No install-time behavior change.

## Acceptance criteria

1. **Pointer source-of-truth lives in `platform.yaml`.** Each platform pack's `platform.yaml` gains an optional `pointers:` mapping declaring which pointer files belong in each specialist directory. Schema:

   ```yaml
   pointers:
     # Path is relative to the pack root. Each entry says "this directory
     # gets these pointer files, each pointing at this target."
     code-review/bill-kotlin-code-review:
       - name: shell-ceremony.md
         target: orchestration/shell-content-contract/shell-ceremony.md
       - name: telemetry-contract.md
         target: orchestration/telemetry-contract/PLAYBOOK.md
       # ... etc
   ```

   The `target` is repo-rooted (e.g. `orchestration/...` or `platform-packs/kmp/addons/...`). The renderer computes the correct `../`-prefixed relative path from the pointer file's location to the target. **No hand-typed `../../../../` paths in `platform.yaml`.**

2. **Renderer.** New file `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/PointerRendering.kt` with:
   - `data class PointerSpec(val skillRelativeDir: String, val name: String, val target: String)` — one pointer entry as parsed from manifest.
   - `fun renderPointer(repoRoot: Path, packRoot: Path, spec: PointerSpec): String` — returns the file content (a single relative path, **no trailing newline**, computed via `Path.relativize` from the pointer file's location to `repoRoot.resolve(spec.target)`).
   - The renderer normalizes paths (no double slashes, no leading `./`) and asserts the target file exists (fail loud at render time, not validate time).

3. **Manifest parsing.** Extend `loadPlatformManifest` in `ShellContentLoader.kt` to parse the optional `pointers:` block into `PlatformManifest`. Add `val pointers: List<PointerSpec>` to `PlatformManifest` (default empty list when absent). Per-entry validation: name ends in `.md`, target is non-empty, no `..` in name, each (skillRelativeDir, name) pair is unique.

4. **Repo regenerate.** Add `PointerOperations.regenerate(repoRoot)` mirroring `NativeAgentOperations.regenerate` semantics: walk all packs, render all declared pointers, write only when content differs, return a `PointerRegenerationResult(regeneratedFiles: List<Path>)`. Wire it into the existing `skill-bill regenerate` CLI command (find via `grep regenerate runtime-kotlin/runtime-cli/src/main/kotlin/`) so a single command regenerates both native agents and pointers.

5. **Repo validate.** Add `validatePlatformPackPointers(repoRoot)` returning issues:
   - **Drift**: an existing on-disk pointer file's content does not match what the renderer would produce (after normalizing the file's bytes, e.g. trimming trailing whitespace).
   - **Orphan**: an on-disk pointer file (single-line file starting with `..`) inside a specialist directory is not declared in any `platform.yaml` `pointers:` block.
   - **Missing**: a declared pointer is not present on disk.
   - Wire into `validateRepoNativeAgents` callsite (or wherever `skill-bill repo validate` lives — find via grep).

6. **Migration: convert existing 60 files.**
   - Author `pointers:` blocks in both `platform-packs/kotlin/platform.yaml` and `platform-packs/kmp/platform.yaml` covering every existing pointer file.
   - Run the new regenerate; assert the diff against current files is **only** whitespace/double-slash normalization (specifically: the three `../../../..//orchestration/` typos collapse to `../../../../orchestration/`, and any trailing-newline differences are reconciled by choosing one normalized form).
   - Commit the regenerated files in this PR.

7. **Tests.**
   - **Round-trip / parse / render**: like `NativeAgentRoundTripTest`, walk all platform packs, parse pointers from `platform.yaml`, render them, assert byte-equality with on-disk content (after normalization). Asserts the migration in (6) is consistent.
   - **Renderer unit**: 4+ inline cases — same-pack target, addon-in-same-pack target, deep-nested target requiring 4 `..`, target-resolves-to-self error case (rejected).
   - **Manifest parse**: 3 cases — valid pointers block, missing pointers block (no-op), invalid pointer entry (rejected with helpful error).
   - **Validation**: pass + fail (drift), pass + fail (orphan), pass + fail (missing).

8. **Documentation.** Extend `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/README.md` (added in SKILL-38) — or add a sibling `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/README.md` if more appropriate — covering: (a) what pointer files are and why they exist (markdown link indirection), (b) the `pointers:` schema, (c) the regenerate/validate workflow, (d) when to add a new pointer (only via manifest, never hand-edit). Keep it under ~60 lines.

## Non-goals

- Do not change install behavior. Skills are still symlinked; pointer files are still read through the symlink.
- Do not change `orchestration/` layout or any pointer target paths.
- Do not introduce a new file format. Pointer files remain single-line plain text.
- Do not touch native-agent code from SKILL-37/38; only mirror its patterns.
- Do not add a templating language for SKILL.md or content.md (that's a separate larger refactor).
- Do not change the symlink-vs-copy fallback behavior on Windows.

## Validation

`(cd runtime-kotlin && ./gradlew check)` plus the existing `npx --yes agnix --strict .` and `scripts/validate_agent_configs` checks.
