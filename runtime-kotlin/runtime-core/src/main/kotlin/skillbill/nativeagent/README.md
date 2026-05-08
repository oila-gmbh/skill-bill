# skillbill.nativeagent

Provider-neutral source-of-truth for subagent prompts plus per-provider rendering and install.

## Source format

Each native agent lives at `<skill-or-pack>/native-agents/<name>.md` with YAML frontmatter and a markdown body. The frontmatter accepts exactly two keys: `name` (lowercase kebab-case, must match the filename stem) and `description` (non-blank). Anything else is rejected.

Example:

```
---
name: bill-example-worker
description: Example worker.
---

# Worker

Do the work.
```

`parseNativeAgentSource` reads these files; `renderNativeAgentSource` emits the canonical neutral form (used for round-trip stability and scaffolding stubs).

## Bodies are provider-agnostic

The body is shared across every provider (Claude, Codex, Opencode, Junie). Provider-specific shaping happens only in the renderer, never in the body. The validator (`validateRepoNativeAgents`) rejects bodies containing any of:

- handlebars-style switches: `{{#claude}}`, `{{#codex}}`, `{{#opencode}}`, `{{#junie}}`
- programmatic switches (case-insensitive): `if provider ==`, `if (provider`

If a provider truly needs different output, add the dispatch inside `NativeAgentProvider.render(source)` — each enum constant overrides `render`, so the wiring is one place.

## Install / symlink fallback

`NativeAgentOperations.renderInstallArtifacts` materializes per-provider files under `<home>/.skill-bill/native-agents/<repo-slug>-<8-byte-hash>/<provider-dir>/`. The slug is the platform-packs root's parent directory basename, sanitized to `[a-z0-9-]+` (≤32 chars); empty slugs collapse to hash-only.

`installNativeAgentFile` (in `skillbill.install`) then links those rendered files into the agent's home directory using one of four `InstallAction` decisions: `Create` (no existing target), `Replace` (replace a managed/legacy symlink), `AlreadyLinked` (skip — symlink already points at the right source), or `Skip` (preserve a non-managed file at the target path). Results surface as `InstallNativeAgentResult.Linked` or `InstallNativeAgentResult.Skipped` with a human-readable reason.

When the JVM cannot create a symlink, the install fails with a clear remediation hint: enable Windows Developer Mode (Settings -> Privacy & security -> For developers) or run the install from an elevated shell.

## Platform-pack pointer files (SKILL-39)

Each `platform-packs/<platform>/<category>/<skill>/` directory ships a handful of single-line markdown files (`shell-ceremony.md`, `telemetry-contract.md`, addon pointers, …). Their entire content is one relative path like `../../../../orchestration/shell-content-contract/shell-ceremony.md`. The agent reads them by following markdown links from `SKILL.md`, which gives skills a stable filename surface even when the underlying target moves.

Pointer paths are brittle — easy to miscount the `..` depth, easy to drift across siblings — so they are now generated from each pack's `platform.yaml`:

```yaml
pointers:
  code-review/bill-kotlin-code-review:
    - name: shell-ceremony.md
      target: orchestration/shell-content-contract/shell-ceremony.md
    - name: telemetry-contract.md
      target: orchestration/telemetry-contract/PLAYBOOK.md
```

`target` is repo-rooted; `renderPointer(repoRoot, packRoot, spec)` (in `skillbill.scaffold`) computes the natural forward-slash relative path with no leading `./`, no double slashes, and no trailing newline. The renderer fails loud if the target file is missing.

Workflow:

- Regenerate: `skill-bill upgrade` calls `AuthoringOperations.upgrade`, which invokes `PointerOperations.regenerate(repoRoot)` after the native-agent regeneration step. Only files whose bytes differ are rewritten; rollback bookkeeping is shared with the wrapper/native regeneration.
- Validate: `RepoValidationRuntime.validateRepo` calls `validatePlatformPackPointers(repoRoot)` after the native-agent validation. It reports drift (on-disk content does not match the renderer), missing (declared but absent), and orphan (single-line pointer-shaped file under a pack that is not declared in any `pointers:` block) issues.

Adding a new pointer means editing the manifest, never the pointer file. Hand edits are reverted by the next `upgrade` and rejected by repo validation.
