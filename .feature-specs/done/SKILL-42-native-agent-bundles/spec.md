# SKILL-42: group thin native-agent definitions into bundles

Status: Complete

## Problem

Native-agent source files currently use one markdown file per agent:

```text
<skill-or-pack>/native-agents/<agent-name>.md
```

After SKILL-41 introduced `compose: governed-content`, many of those files became header-only definitions. For example, platform review orchestrators now carry one small file per specialist with only:

```yaml
---
name: bill-kotlin-code-review-architecture
description: Kotlin architecture specialist code reviewer...
compose: governed-content
---
```

That shape is mechanically correct, but it creates noisy source trees. Authors must open many tiny files to inspect one skill's native-agent surface, and changes to a group of specialists produce unnecessary file churn.

## Product Rule

Thin native-agent definitions should be authored as a grouped source surface when they belong to the same owning skill.

Allowed source forms:

- `native-agents/agents.yaml` for grouped native-agent metadata.
- `native-agents/<agent-name>.md` for body-heavy or custom native-agent definitions.

Generated provider-native output must remain one installed agent artifact per native agent. Grouping is only a source-authoring improvement.

## Scope

Add support for bundled native-agent source definitions while preserving the current per-agent markdown file format.

This is a source contract cleanup for native-agent authoring. It must not change provider-specific install locations, installed artifact shape, governed `content.md` composition semantics, or runtime agent behavior.

## In-scope changes

1. **Bundle source format**
   - Add support for `<skill-or-pack>/native-agents/agents.yaml`.
   - Parse a top-level `agents` list.
   - Each entry must support:
     - `name`
     - `description`
     - optional `compose: governed-content`
     - optional `body` for custom provider-neutral body text.
   - `body` may be omitted only when `compose: governed-content` is present.

2. **Parser and model**
   - Expand `agents.yaml` entries into the existing `NativeAgentSource` model.
   - Preserve the existing provider render pipeline after expansion.
   - Keep error messages specific enough to identify the bundle file and the failing agent name.
   - Keep `name` validation lowercase kebab-case.
   - Keep `description` required and nonblank.
   - Reject unsupported keys and unsupported `compose` values.

3. **Discovery**
   - Discover both:
     - `native-agents/*.md`
     - `native-agents/agents.yaml`
   - Return the same logical source set to validation, render, and install flows.
   - Keep discovery deterministic and sorted.
   - Loud-fail duplicate agent names across markdown files and bundle entries in the same repository/install scope.

4. **Composition**
   - Preserve `compose: governed-content` behavior for bundled entries.
   - For platform-pack native agents, composition target resolution must remain manifest-driven through `platform.yaml`.
   - For horizontal skill native agents, sibling `content.md` composition must continue to require matching frontmatter `name`.
   - Installed provider-native agents must remain self-contained after composition and sidecar inlining.

5. **Validation**
   - Apply the same validation rules to bundled and markdown-defined native agents.
   - Keep provider-agnostic body validation.
   - Keep malformed composition directives as loud validation failures.
   - Keep unresolved local markdown link rejection after composition.
   - Keep generated provider artifact guard behavior unchanged.
   - Reject duplicate definitions for the same native-agent name, including one entry in `agents.yaml` plus one `<agent-name>.md`.

6. **Scaffolding**
   - For orchestrator scaffolds with multiple thin composed specialists, generate `native-agents/agents.yaml` instead of many header-only markdown files.
   - Continue to allow per-agent markdown source files for native agents with substantial custom body text.
   - Update scaffold previews and guidance so authors know grouped metadata lives in `native-agents/agents.yaml`.

7. **Migration**
   - Migrate existing header-only Kotlin and KMP specialist native-agent source files into bundle files.
   - Delete only the migrated header-only source files.
   - Do not delete source files that carry custom bodies unless the body is preserved in a bundle `body` block.
   - Keep generated provider-native artifacts out of git.

8. **Docs**
   - Update `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/README.md`.
   - Update `orchestration/shell-content-contract/PLAYBOOK.md` where native-agent source shape is described.
   - Update `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md` for scaffold behavior.
   - State plainly that bundles are source files and install still emits one provider-native artifact per agent.

9. **Tests**
   - Add parser tests for valid and invalid `agents.yaml`.
   - Add discovery tests for bundle-only, markdown-only, and mixed source forms.
   - Add duplicate-name rejection coverage.
   - Add composition coverage for bundled `compose: governed-content`.
   - Add install rendering coverage proving bundled entries generate provider-native artifacts.
   - Add scaffold coverage proving thin specialist stubs are grouped into `agents.yaml`.
   - Add migration or fixture coverage for Kotlin/KMP bundled specialists.

## Acceptance criteria

- A skill can define multiple native agents in `native-agents/agents.yaml`.
- Existing `native-agents/<agent-name>.md` files still work.
- Duplicate native-agent names loud-fail across bundled and markdown source definitions.
- Bundled entries support `compose: governed-content`.
- Bundled entries with custom `body` render the same way as markdown-defined native agents.
- Platform-pack bundled composition resolves content only through `platform.yaml` declarations.
- Horizontal skill bundled composition resolves sibling `content.md` only when frontmatter names match.
- Provider-native install output remains one self-contained artifact per native agent.
- Generated provider-native artifacts remain uncommitted.
- Scaffolding multi-specialist orchestrators creates `native-agents/agents.yaml` for thin composed agents.
- Kotlin and KMP header-only specialist native-agent files are migrated into bundles without behavior changes.
- `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/README.md` documents both source forms.
- `orchestration/shell-content-contract/PLAYBOOK.md` and `SCAFFOLD_PAYLOAD.md` document bundle behavior.
- `(cd runtime-kotlin && ./gradlew check)` passes.
- `npx --yes agnix --strict .` passes.
- `scripts/validate_agent_configs` passes.

## Non-goals

- Do not remove support for `native-agents/<agent-name>.md`.
- Do not change provider-specific install locations.
- Do not change provider-native rendered artifact formats.
- Do not weaken native-agent validation.
- Do not make platform-pack composition path-based when a manifest declaration is required.
- Do not move governed skill `content.md` behavior into native-agent bundle files.
- Do not reintroduce generated governed `SKILL.md` files or platform pointer files.

## Design notes

Preferred bundle shape:

```yaml
agents:
  - name: bill-kotlin-code-review-architecture
    description: Kotlin architecture specialist code reviewer.
    compose: governed-content

  - name: bill-kotlin-code-review-performance
    description: Kotlin performance specialist code reviewer.
    compose: governed-content
```

For custom provider-neutral bodies:

```yaml
agents:
  - name: bill-example-worker
    description: Example native worker.
    body: |
      # Worker

      Handle the bounded task and return a concise result.
```

Use YAML instead of a markdown file with repeated frontmatter blocks. The repo already uses structured YAML for platform manifests, and the bundle is metadata-first. Markdown remains the better source form when a single agent has a large authored body.

The internal runtime can either:

- keep path discovery and add an expansion layer from path to one-or-many `NativeAgentSource` values, or
- migrate discovery to return logical `NativeAgentSource` values directly.

Either approach is acceptable if validation errors remain precise and install/render behavior stays deterministic.

## Validation

Required gates:

```bash
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Manual checks:

```bash
git ls-files '*/native-agents/*.md'
git ls-files '*/native-agents/agents.yaml'
runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli install --provider codex --repo-root . --home <temp-home>
```

The install check should show one provider-native output artifact per bundled native-agent entry.

## Recommended next prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-42-native-agent-bundles/spec.md`.
