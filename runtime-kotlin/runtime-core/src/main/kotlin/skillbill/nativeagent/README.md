# skillbill.nativeagent

Provider-neutral source-of-truth for subagent prompts plus per-provider rendering and install.

## Source format

Each native agent can be authored in either source form:

- one markdown file at `<skill-or-pack>/native-agents/<name>.md`
- one bundled list at `<skill-or-pack>/native-agents/agents.yaml`

Markdown files use YAML frontmatter plus a markdown body. The frontmatter accepts optional `contract_version`, `name` (lowercase kebab-case, must match the filename stem), `description` (non-blank), and optional `compose: governed-content`. Anything else is rejected.

Example:

```
---
contract_version: "0.1"
name: bill-example-worker
description: Example worker.
---

# Worker

Do the work.
```

Bundles use an optional top-level `contract_version` plus a required `agents:` list. Each entry accepts `name`, `description`, optional `compose: governed-content`, and optional `body`. `body` may be omitted only when `compose: governed-content` is present. Duplicate names fail validation across markdown files and bundle entries.

Example:

```yaml
contract_version: "0.1"
agents:
  - name: bill-example-worker
    description: Example composed worker.
    compose: governed-content
  - name: bill-custom-worker
    description: Example custom worker.
    body: |-
      # Worker

      Do the work.
```

`parseNativeAgentSource` reads markdown files; `parseNativeAgentBundle` reads bundles; `discoverNativeAgentSources` expands both source forms into logical `NativeAgentSource` values. `renderNativeAgentSource` and `renderNativeAgentBundle` emit canonical neutral forms with `contract_version` for round-trip stability and scaffolded bundle stubs. The parser accepts older sources without the version key, but if the key is present schema validation pins it to `NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION`.

When `compose: governed-content` is present, the source may omit a local body and compose from the corresponding governed `content.md`. Platform-pack sources resolve that target through the pack manifest's declared files; skill-local sources resolve only a sibling `content.md` whose frontmatter name matches. Bundle entries use `agents.yaml` as their source path for this resolution, so they follow the same manifest and sibling-content rules as markdown sources. Installed provider-native files are rendered one artifact per logical native agent from the composed body and inline declared local markdown sidecars, so they do not depend on repo-local `content.md` or sidecar files at runtime.

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

Each `platform-packs/<platform>/<category>/<skill>/` directory may declare a handful of generated single-line markdown files (`shell-ceremony.md`, `telemetry-contract.md`, addon pointers, ...). Their entire content is one relative path like `../../../../orchestration/shell-content-contract/shell-ceremony.md`. The agent reads them by following markdown links from generated `SKILL.md`, which gives installed skills a stable filename surface even when the underlying target moves. These pointer files are render/install output and are not committed as authored source.

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
