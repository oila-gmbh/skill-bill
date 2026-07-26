# SKILL-41: make content.md a pure authored skill surface

Status: Draft

## Problem

`content.md` is supposed to be the author's direct skill content. After SKILL-40 removed committed generated `SKILL.md` wrappers, `content.md` still carries wrapper/governance material that existed only to satisfy the generated skill shape:

- `## Descriptor`
- `## Execution`
- `## Ceremony`
- boilerplate such as `Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).`

That violates the product rule for this repo: validation must serve the business contract, not force users to see or edit generated wrapper scaffolding. Anything that belongs to generated `SKILL.md` must be generated as part of render/install output, not stored in the authored `content.md` source.

## Product Rule

Hard rule: `content.md` contains only material a user directly writes for the skill itself.

Allowed in `content.md`:

- Skill-authored instruction prose.
- Skill-authored headings and checklists that are part of the skill's behavior.
- Optional authored frontmatter only if it is treated as user-owned skill identity/routing metadata.

Not allowed in `content.md`:

- Generated wrapper sections such as `## Descriptor`, `## Execution`, or `## Ceremony`.
- Shell ceremony pointers.
- Generated descriptor text derived from manifests, paths, platform pack metadata, family, or area.
- Validation-only boilerplate.
- Native-agent-only framing or duplicated specialist contracts unless the author intentionally wrote them as part of the skill behavior.

## Scope

Change the governed source contract so `content.md` is pure authored source, while generated `SKILL.md` still renders with the governed wrapper shape required by agent runtimes.

This is a source-contract cleanup that builds on SKILL-40. The repo should continue to have no committed generated governed `SKILL.md` files and no committed platform pointer files.

## In-scope changes

1. **Content source cleanup**
   - Remove generated wrapper sections from every governed `content.md`.
   - Move the text currently under `## Execution` to the top-level authored body.
   - Remove `## Descriptor` and `## Ceremony` from source files.
   - Preserve authored skill behavior exactly where possible.

2. **Renderer ownership**
   - Update the `SKILL.md` renderer so it generates:
     - frontmatter from authored metadata and/or manifest-derived metadata,
     - `## Descriptor` from manifest/path/family/platform/area data,
     - `## Execution` around the authored `content.md` body,
     - `## Ceremony` from shell contract metadata.
   - Keep rendered output deterministic and byte-stable.
   - `skill-bill render <skill>` must remain the canonical way to inspect generated wrappers.

3. **Loader and validation contract**
   - Stop requiring `content.md` to contain `## Descriptor`, `## Execution`, or `## Ceremony`.
   - Validate `content.md` as authored source, not generated wrapper shape.
   - Validate generated wrapper shape by rendering in memory and checking the generated output, not by forcing source files to contain wrapper sections.
   - Keep loud-fail behavior for missing manifests, wrong shell contract version, missing declared content files, invalid authored metadata, and invalid generated output.

4. **Scaffold and mutation flows**
   - New governed skills must scaffold clean authored `content.md` files only.
   - `skill-bill fill` and `skill-bill edit --section` must operate on authored sections, not generated wrapper sections.
   - If a user asks to edit `Descriptor`, `Execution`, or `Ceremony`, the CLI should explain that those are generated wrapper sections and point to the correct authored source or manifest field.

5. **Native-agent source composition**
   - Keep `native-agents/*.md` as legitimate provider-neutral native-agent definition files.
   - Do not delete or generate those files in this change.
   - Remove unnecessary duplication by allowing native-agent install/render to derive the execution body from the corresponding `content.md` where a native agent is a direct wrapper around a specialist skill.
   - Installed provider-native agents must stay self-contained: any included `content.md` body or shared contract must be expanded before writing to the install cache.
   - Native-agent source files may remain as thin definitions with metadata and composition directives if that keeps author intent clear.

6. **Docs and policy**
   - Update `AGENTS.md` and shell-content-contract docs to state the new rule plainly: `content.md` is authored skill content only.
   - Document which generated sections belong to rendered `SKILL.md`.
   - Document how native-agent definitions compose with `content.md` without duplicating large instruction bodies.

7. **Tests and fixtures**
   - Update shell-content-contract fixtures to use clean authored `content.md`.
   - Update loader, scaffold, render, install, mutation, and validation tests to assert the new source shape.
   - Add rejection coverage for source `content.md` files that contain generated wrapper sections.
   - Add render coverage proving generated `SKILL.md` still contains the governed wrapper sections.
   - Add native-agent coverage for deriving/expanding specialist execution content without depending on repo-local files at install time.

## Acceptance criteria

- Every governed `content.md` in the repo contains authored skill content only; no `## Descriptor`, `## Execution`, or `## Ceremony` wrapper sections remain.
- Generated `SKILL.md` output still contains the governed wrapper shape: Descriptor, Execution, and Ceremony.
- `ShellContentLoader` accepts clean authored `content.md` and loud-fails invalid/missing authored content.
- Validation rejects reintroduced wrapper boilerplate in source `content.md`.
- `skill-bill render <skill>` produces deterministic generated wrappers for every skill.
- Scaffolding a new governed skill creates clean authored `content.md`, not wrapper-shaped source.
- `skill-bill fill` and `skill-bill edit` target authored content sections and do not require generated wrapper headings.
- Native-agent files remain source files, but direct specialist-native agents can compose from the corresponding `content.md` without duplicated execution prose.
- Installed provider-native agents remain self-contained after native-agent composition/rendering.
- `(cd runtime-kotlin && ./gradlew check)` passes.
- `npx --yes agnix --strict .` passes.
- `scripts/validate_agent_configs` passes.

## Non-goals

- Do not reintroduce committed generated governed `SKILL.md` files.
- Do not reintroduce committed platform pointer files.
- Do not delete `native-agents/*.md` as part of this change.
- Do not make validation weaker; move validation to the correct source/render boundary.
- Do not require users to understand wrapper internals to author a skill.
- Do not change provider-specific native-agent install locations unless required by the composition implementation.

## Design notes

The key distinction is source versus generated output:

- `content.md`: authored skill behavior.
- `platform.yaml`: pack, routing, area, and generated-output declarations.
- `native-agents/*.md`: authored provider-neutral native-agent definitions or thin composition definitions.
- rendered `SKILL.md`: generated wrapper consumed by skill runtimes.
- installed provider-native agents: generated runtime-specific agents consumed by Claude, Codex, OpenCode, Junie, etc.

The implementation should prefer structured parsing over ad hoc string matching where the runtime already has markdown/frontmatter helpers. Wrapper-section rejection in source can be a simple top-level heading check if no richer parser exists, but rendering should remain the source of truth for generated wrapper bytes.

## Validation

Required gates:

```bash
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Manual checks:

```bash
git ls-files '*SKILL.md'
runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli render <skill-id> --repo-root .
```

`git ls-files '*SKILL.md'` should continue to return no governed generated wrappers.

## Recommended next prompt

Run bill-feature-implement on `.feature-specs/SKILL-41-content-md-authored-surface/spec.md`.
