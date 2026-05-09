# SKILL-43: align product positioning and clean authored-surface drift

Status: Draft

## Context

The current repository has successfully moved the biggest generated artifacts out of the source tree:

- governed `SKILL.md` wrappers are render/install output
- support pointer files are render/install output
- `content.md` is the authored skill surface
- native-agent provider artifacts are generated into install cache

Early usage has also clarified the product shape:

- `bill-feature-implement` is the flagship bundled workflow.
- `bill-code-review`, `bill-quality-check`, `bill-pr-description`, `bill-boundary-history`, telemetry, workflow state, platform packs, add-ons, and native subagents are reusable subsystems inside that workflow.
- Users can still delete or replace every bundled skill. The durable product is the governed authoring, render, install, validation, routing, CLI, and MCP framework.
- External users have created PHP and Golang packs for themselves, so the replaceable-framework story is real and should be reflected in docs and CLI language.

The remaining problem is not architectural direction. The problem is product and authoring-surface drift: some user-facing text still describes generated `SKILL.md` paths as source, docs present all bundled skills too equally, and the CLI still exposes generated wrapper concepts in places where the source model says users should think in `content.md` and `skill-bill render`.

## Problem

Several small inconsistencies make the project harder to understand than the architecture actually is:

1. **Flagship vs framework positioning is not sharp enough.** The README and onboarding still mostly present a catalog of skills and governance concepts. They should say that `bill-feature-implement` is the flagship bundled workflow while also making clear that bundled skills are replaceable reference workflows, not the product boundary.

2. **Generated-wrapper language leaks into authored surfaces.** Some skill content still says new skills live at `.../SKILL.md` or that routed skills should follow a source `SKILL.md`, even though source-authoring now lives in `content.md` and rendered `SKILL.md` is install output.

3. **CLI inspection output implies nonexistent source files.** `skill-bill show` and `skill-bill list` still expose `skill_file: .../SKILL.md` for governed source skills. Those files are generated and not committed. The CLI should expose the authored `content_file` and point users to `skill-bill render <skill>` for generated wrapper inspection.

4. **Completion status is noisy for shipped bundled workflows.** Some shipped skills appear as `draft` only because of empty organizing headings such as `## Patterns` or `## Audit Rubrics`. A bundled workflow should not look unfinished unless there is an intentional TODO/FIXME or genuinely missing authored behavior.

5. **Validation-gate language is slightly inconsistent.** Docs alternate between three-command and four-command gates, and some wording describes maintainer validation differently from normal user validation. The project should have one clear validation matrix.

6. **External pack authoring success is not reflected.** The docs correctly describe external authoring, but they do not yet make the intended replaceability explicit enough: teams may remove bundled skills and keep the framework.

## Product Rule

Skill Bill has two user-facing layers:

1. **Bundled reference workflows.** `bill-feature-implement` is the flagship. The other bundled skills are reusable workflow components and standalone entry points.
2. **Governed workflow platform.** Teams may fork, replace, or delete bundled skills and use the framework to author their own governed workflows and platform packs.

Docs, CLI output, scaffold previews, and skill authoring guidance must preserve both truths.

## Scope

Clean up product positioning, source-model wording, CLI inspection output, and completion-status noise without changing the core runtime architecture or adding new platform behavior.

## In-scope changes

1. **README repositioning**
   - Lead with `bill-feature-implement` as the flagship bundled workflow.
   - Explain that `bill-code-review`, `bill-quality-check`, `bill-pr-description`, history, telemetry, workflow state, platform packs, add-ons, and native subagents are reusable components inside the flagship workflow.
   - State plainly that bundled skills are replaceable reference workflows; the framework remains useful even if a team deletes all shipped skills and creates its own.
   - Keep the existing catalog, but make it secondary reference material rather than the main product story.

2. **Getting-started docs**
   - Update `docs/getting-started.md` so the first normal-user path starts with `/bill-feature-implement`.
   - Keep `/bill-code-review` as the second most important standalone path.
   - Add a short "replaceable bundled workflows" section: shipped skills are defaults, not a lock-in boundary.
   - Keep Kotlin/KMP positioned as reference packs, not the complete platform list.

3. **Team adoption docs**
   - Update `docs/getting-started-for-teams.md` to explain the two adoption modes:
     - use the bundled flagship workflow
     - use Skill Bill as a governed workflow platform and author/fork/delete workflows
   - Add a short external-authoring evaluation checklist based on real PHP/Golang pack authoring:
     - time to scaffold or create the pack
     - confusing docs or CLI output
     - validation failures that helped
     - commands that became habitual
     - whether the author could proceed without maintainer context

4. **Source-model language audit**
   - Replace user-facing source-path references to governed `SKILL.md` files with `content.md` or `skill-bill render <skill>` as appropriate.
   - Keep `SKILL.md` wording only when referring to generated installed/runtime output.
   - Audit at least:
     - `skills/bill-create-skill/content.md`
     - `skills/bill-quality-check/content.md`
     - `skills/bill-code-review/content.md`
     - `skills/bill-feature-implement/content.md`
     - `skills/bill-feature-verify/content.md`
     - `docs/*.md`
     - `orchestration/shell-content-contract/*.md`
     - CLI help and scaffold output strings

5. **CLI inspection output**
   - Change `skill-bill show` and `skill-bill list` payloads so they do not report a source `skill_file` path for generated governed wrappers.
   - Preferred shape:
     - `content_file`: source authored file
     - `render_command`: command to inspect generated runtime wrapper
     - optional `rendered_artifact`: present only in render/install output, not source inspection
   - Keep JSON compatibility deliberately: either add new fields first and deprecate `skill_file`, or remove `skill_file` only if tests and downstream consumers prove it is not depended on.
   - Update golden fixtures and docs for the chosen compatibility posture.

6. **Completion status cleanup**
   - Make every shipped bundled skill report `complete` unless it has a real unresolved TODO/FIXME or missing authored behavior.
   - Fix source content where empty organizing headings cause false `draft` status.
   - If an empty grouping heading is useful for readability, either give it a short introductory sentence or teach the parser to treat explicitly allowed grouping headings as non-draft.
   - Do not hide real incomplete content by weakening placeholder detection.

7. **Validation-gate normalization**
   - Define one validation matrix:
     - normal author check: `skill-bill validate`
     - maintainer runtime check: `(cd runtime-kotlin && ./gradlew check)`
     - docs/config check: `npx --yes agnix --strict .`
     - agent config and generated artifact guard: `scripts/validate_agent_configs`
   - Update README, `docs/getting-started.md`, `docs/getting-started-for-teams.md`, `docs/skill-source-generation.md`, and relevant spec templates to use the same wording.

8. **Test coverage**
   - Add or update tests proving `show` and `list` no longer imply committed source `SKILL.md` wrappers.
   - Add regression coverage for source-model wording if there is already a docs/reference validator surface; otherwise add an agent-config guard check for forbidden source-path phrases in governed content.
   - Add completion-status tests for representative content with grouping headings.

## Acceptance criteria

- README leads with `bill-feature-implement` as the flagship bundled workflow.
- README also states that bundled skills are replaceable references and the framework can be used with entirely user-owned workflows.
- Getting-started docs guide new users to `/bill-feature-implement` first and `/bill-code-review` second.
- Team docs describe both adoption modes: bundled workflow adoption and framework/platform-pack authoring.
- User-facing docs and governed skill content no longer describe committed governed `SKILL.md` files as source artifacts.
- `SKILL.md` references remain only where they clearly mean generated installed/runtime output.
- `skill-bill show` and `skill-bill list` no longer present nonexistent source `SKILL.md` paths as if they are editable files.
- `skill-bill render <skill>` remains the documented way to inspect generated runtime wrappers.
- All shipped bundled skills report `complete`, unless a maintainer intentionally marks one as draft and documents why.
- Empty grouping headings no longer create false draft status.
- Validation-gate wording is consistent across README and docs.
- Tests and golden fixtures are updated for any CLI payload changes.
- `skill-bill validate` passes.
- `(cd runtime-kotlin && ./gradlew check)` passes.
- `npx --yes agnix --strict .` passes.
- `scripts/validate_agent_configs` passes.

## Non-goals

- Do not change the shell/content contract version.
- Do not add PHP or Golang as bundled reference packs in this feature.
- Do not make `bill-feature-implement` mandatory for teams that want only custom workflows.
- Do not remove standalone entry points for `bill-code-review`, `bill-quality-check`, `bill-pr-description`, or other bundled components.
- Do not reintroduce committed generated `SKILL.md` wrappers or pointer files.
- Do not weaken validation to hide real incomplete skill content.
- Do not redesign telemetry, workflow state, native-agent rendering, or install staging.

## Design notes

The docs should use this hierarchy:

```text
Skill Bill framework
  -> governed authoring model
  -> renderer/install/runtime
  -> validators/contracts
  -> CLI/MCP tooling
  -> optional bundled reference workflows
      -> bill-feature-implement
      -> bill-code-review
      -> bill-quality-check
      -> bill-pr-description
      -> ...
  -> user/team-owned workflows and platform packs
```

Suggested concise positioning:

> Skill Bill is a governed workflow platform for AI coding agents. Its flagship bundled workflow, `bill-feature-implement`, turns a feature spec into planned, reviewed, validated, documented code. The bundled skills are replaceable reference workflows; teams can keep the framework and author their own workflows and platform packs.

CLI language should distinguish source from rendered output:

- Source: `content.md`
- Inspect generated runtime wrapper: `skill-bill render <skill>`
- Installed runtime output: staged directory under `~/.skill-bill/installed-skills/<skill>-<hash>/`

## Validation

Required gates:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Manual checks:

```bash
skill-bill list
skill-bill show bill-feature-implement
skill-bill render bill-feature-implement
rg -n 'skills/.+/SKILL.md|platform-packs/.+/SKILL.md|Destination: .*SKILL.md|source .*SKILL.md' README.md docs skills orchestration runtime-kotlin
```

The manual `rg` should return only generated-output references, test fixtures, or explicitly explained compatibility/deprecation text.

## Recommended next prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-43-product-positioning-and-authoring-polish/spec.md`.
