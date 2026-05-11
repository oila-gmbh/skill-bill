# Desktop Skill Bill App Spec

Status: Draft

## Summary

Skill Bill should ship an optional desktop Skill Bill app for browsing, editing, validating, and publishing governed Skill Bill repo changes. The app is repo-based: the user opens a local `skill-bill` checkout, edits authored source through the existing Skill Bill runtime, validates the repo, then commits and pushes changes through Git.

The Skill Bill app must not become a second implementation of Skill Bill governance. It is a graphical client over the same authoring, scaffolding, validation, rendering, install, and manifest-discovery contracts that already power the CLI and MCP surfaces.

## Product Goal

Make governed skill and platform-pack authoring approachable without weakening the source/generated boundary.

The app should help a user:

- open or clone a Skill Bill repository
- understand the available horizontal skills, platform packs, add-ons, and native agents
- edit only authored source, especially `content.md`
- create platform packs, platform overrides, code-review areas, add-ons, and supported native-agent source through the existing scaffolder
- validate and render with existing runtime behavior
- inspect Git changes
- commit and push to the user's fork
- optionally open a pull request back to the upstream Skill Bill repo

## Core Principles

- Existing system first. Governed reads and writes must use existing runtime services or CLI-equivalent operations.
- Repo as document. The open local repository is the user's workspace and persistence layer.
- Source only. The editor exposes authored source files only, not generated wrappers or install output.
- Loud failure. The UI should surface the same named validation and scaffolding failures the runtime emits.
- Fork-aware publishing. Users may browse the canonical repo, but pushing contribution changes should go to their fork.
- Thin UI. The desktop module owns presentation, local app state, and Git UX. Governance stays in runtime modules.

## Non-Goals

- Do not implement a cloud-hosted Skill Bill editor.
- Do not store skills outside the repository.
- Do not support editing generated `SKILL.md` wrappers, generated pointer files, provider-specific native-agent outputs, or install cache artifacts.
- Do not duplicate manifest parsing, routing rules, scaffold payload validation, native-agent rendering, or repo validation in UI code.
- Do not build a general-purpose Git client beyond the workflows needed to publish Skill Bill changes.
- Do not add mobile targets in the first release.

## Target Users

- Maintainers editing bundled skills and platform packs.
- External contributors creating or improving packs in their own fork.
- Team leads authoring team-owned packs while relying on Skill Bill governance.
- Agent-skill authors who prefer a visual authoring loop over CLI-only workflows.

## Repository Model

The app is distributed from this repository as an optional desktop module, likely under `runtime-kotlin/runtime-desktop`.

The app can operate on:

- the same checkout that launched it
- another local Skill Bill checkout selected by the user
- a newly cloned canonical repo
- a user fork cloned locally

The app must distinguish these remotes when Git actions are shown:

- `upstream`: canonical Skill Bill repository, if configured
- `origin`: user's writable fork, when publishing changes

If the checkout only points at the canonical repo and the user tries to push, the UI should guide them to configure or create a fork instead of pushing directly upstream.

## Information Architecture

The first screen is the Skill Bill app, not a landing page.

### Left Panel

The left panel is a narrow tree navigator.

Top-level groups:

- `Horizontal Skills`
- `Platform Packs`
- `Legacy Platform Overrides`, only when present
- `Repository`

Platform-pack nodes:

- platform display name and slug
- baseline code-review skill
- quality-check skill, when declared
- code-review area specialists
- add-ons
- native agents, when authored source exists

Skill nodes should show status badges:

- valid
- changed
- validation issue
- generated drift
- missing source
- read-only or unsupported source

### Right Panel

The right panel displays the selected authored source.

For governed skills, the primary editable surface is `content.md` only. The right panel should show:

- skill name
- path to `content.md`
- editable Markdown body
- validation state
- save action
- diff action

The editor must not show generated wrapper content as the primary editing surface. A future read-only preview may show rendered output, but it must be clearly labeled as generated.

### Bottom or Side Drawer

A source-control drawer should show:

- current branch
- changed files
- staged files
- selected-file diff
- validation result
- commit message input
- push target
- pull request target, when available

## Governed Authoring Operations

The app should call shared Kotlin services directly when available. When a capability only exists as CLI wiring, the implementation should move the behavior behind a shared application/runtime service first, then have both CLI and desktop call that service.

Initial runtime operations:

- list content-managed skills: equivalent to `skill-bill list --format json`
- show one skill with full content: equivalent to `skill-bill show <skill> --content full --format json`
- write content: equivalent to `skill-bill fill <skill> --body-file <file> --format json`
- replace a section: equivalent to `skill-bill fill <skill> --section <heading> --body-file <file> --format json`
- validate: equivalent to `skill-bill validate --format json`
- render preview: equivalent to `skill-bill render <skill>`
- scaffold: equivalent to `skill-bill new --payload <file> --format json`
- install or upgrade, when exposed: equivalent to existing install and upgrade commands

The UI may read the actual `content.md` text for display only after the runtime has identified the authored file as a valid target. It must not discover editable targets by hand-walking arbitrary files and guessing intent.

## Creation Workflows

The app should provide guided forms that produce the existing scaffold payload contract.

Supported creation types:

- horizontal skill
- platform pack
- platform override for piloted families
- code-review area specialist
- add-on
- supported native-agent source when runtime support exists

New platform pack wizard:

- platform slug
- display name
- description
- routing signals, only when no built-in preset exists
- skeleton mode: starter, full, or custom approved specialist subset
- optional subagent specialists for supported orchestrator sources

The wizard must run a dry-run plan first when available, show the generated operations, then execute the scaffold operation atomically.

## Git and Fork Workflow

Git support exists to publish repo changes, not to replace command-line Git.

Required workflows:

- detect whether the selected path is a Git repository
- show branch and dirty state
- show changed files and diffs
- stage and unstage files
- commit with a user-provided message
- push current branch to `origin`
- detect missing or non-fork `origin`
- help configure `origin` as the user's fork and `upstream` as canonical

Optional GitHub workflow:

- detect GitHub remotes
- open browser to fork creation page
- create pull request when authenticated support exists
- open browser to compare URL when API auth is unavailable

The app should never silently rewrite remotes. Remote changes require explicit user confirmation.

## Validation and Safety

Before save:

- preserve unsaved editor state
- write through runtime operation, not direct file replacement
- report runtime validation errors inline

Before scaffold:

- warn when repo has dirty changes unless the runtime operation can guarantee rollback with dirty state
- run scaffold dry-run when available
- execute scaffold through the runtime

Before commit:

- run `skill-bill validate`
- show changed files
- warn about generated artifacts under source-controlled paths

Before push:

- verify branch has commits not on push target
- verify `origin` points to the user's fork or is explicitly accepted by the user

## Architecture

Preferred Gradle shape:

```text
runtime-kotlin/
  runtime-domain/
  runtime-ports/
  runtime-core/
  runtime-application/
  runtime-cli/
  runtime-mcp/
  runtime-desktop/
    src/commonMain/kotlin/skillbill/desktop/app/
    src/jvmMain/kotlin/skillbill/desktop/
    core/
      common/              # DI scopes, shared dispatchers, cross-cutting contracts
      data/                # app-scoped service implementations and DI bindings
      database/            # Room3 database, DAOs, schema exports, JVM SQLite builder
      datastore/           # lightweight desktop preferences
      designsystem/        # Skill Bill Material 3 theme, colors, metrics, reusable UI primitives
      domain/              # UI/session models and repo/tree/authoring/git contracts
      navigation/          # typed routes, back-stack state, and desktop navigator
      testing/             # desktop KMP test fakes
      ui/                  # shared Compose shell primitives
    feature/
      skillbill/           # screen component, view model, state, and UI
```

The desktop module uses the relevant KMPComposeStarter app/module/source-set and
build-logic shape, trimmed to JVM desktop only: convention plugins configure KMP
library, KMP Compose, KMP application, Compose Material 3, Compose desktop
packaging, Room3/KSP, and kotlin-inject/KSP/Anvil wiring; shared UI, domain,
data, database, datastore, navigation, and feature code live in nested
`:runtime-desktop:core:*` and
`:runtime-desktop:feature:*` KMP modules under `runtime-desktop`; the native
desktop window entrypoint and generated application component live in the
`:runtime-desktop` app module's `jvmMain`; app-shell state and composition-local
user/screen component providers live in `commonMain`; JVM desktop tests live in
the owning feature/core modules. The first target is JVM desktop; Android and
iOS app targets remain out of scope until they are explicitly needed.

The desktop design system uses the Skill Bill README hero palette from
`docs/assets/readme-hero-preview.html` as the source of brand color truth.

Recommended internal layers:

- `SkillBillDesktopApp`: Compose entrypoint and window lifecycle
- `SkillBillViewModel`: screen state, selection, command orchestration
- `RepoSessionService`: open repo, validate identity, track selected repo root
- `SkillTreeService`: build the navigator from runtime authoring/discovery results
- `AuthoringGateway`: thin adapter over existing Skill Bill authoring operations
- `ScaffoldGateway`: thin adapter over existing scaffold operations
- `ValidationGateway`: thin adapter over existing validation operations
- `GitGateway`: narrow wrapper over Git operations
- `ProcessGateway`: only for operations that intentionally remain external commands

Git support can start as a wrapper around the local `git` executable. If JGit is added later, the public `GitGateway` contract should stay stable.

## Data Model

Key UI models:

- `RepoSession`: repo root, repo validity, current branch, remotes, runtime version
- `TreeNode`: id, label, kind, status, children, target
- `AuthoringTarget`: skill name, content path, target kind, editable flag
- `EditorDocument`: target, saved text, draft text, dirty state, validation state
- `ValidationReport`: status, issues, raw runtime payload link
- `ScaffoldDraft`: kind, payload, dry-run result
- `GitStatus`: branch, remotes, changed files, staged files, ahead/behind

These models should represent runtime results. They should not encode independent validation rules that can drift from runtime behavior.

## Testing Strategy

Runtime and service tests:

- repo detection accepts valid Skill Bill repo and rejects unrelated directories
- tree construction matches authoring list and platform manifest discovery
- editor save calls the shared authoring operation and preserves runtime errors
- scaffold wizard emits contract-valid payloads
- GitGateway parses changed files, remotes, and branch state

UI tests:

- opens a valid repo and renders the two-panel Skill Bill app
- selecting a skill loads only `content.md`
- generated files are not offered as editable targets
- dirty editor state blocks accidental selection loss
- validation failures appear inline

End-to-end smoke:

- create a temporary fork-like checkout
- edit a skill
- validate
- commit
- push to a local bare remote

## Release Shape

Early release should be developer-run:

```bash
cd runtime-kotlin
./gradlew :runtime-desktop:run
```

Later release can add:

- packaged desktop distribution
- launch script from repo root
- README entry
- optional installer task

## Open Questions

- Should the first release include GitHub API integration, or only compare-URL generation?
- Should native-agent source editing be first-class in v1, or deferred after `content.md` editing is stable?
- Should the app run validation automatically after every save, or only on demand with an optional preference?
- Should the app support multiple repo windows, or one repo session per process?
- Should `skill-bill list/show` expose richer tree metadata, or should desktop call lower-level shared services directly?

## Iterative Subspecs

The work is decomposed into these specs:

1. [Runtime Desktop Shell](iterations/01-runtime-desktop-shell.md)
2. [Repo Browser and Read-Only Tree](iterations/02-repo-browser-readonly-tree.md)
3. [Content Editor and Validation](iterations/03-content-editor-validation.md)
4. [Scaffold Wizards](iterations/04-scaffold-wizards.md)
5. [Git Fork Publishing](iterations/05-git-fork-publishing.md)
6. [Packaging and Release Polish](iterations/06-packaging-release-polish.md)

## Completion Checklist

Use this checklist as the lightweight implementation tracker. Keep detailed acceptance criteria in the iteration specs; update these boxes as work lands.

### Iteration 01: Runtime Desktop Shell

- [x] Add `runtime-desktop` Gradle module.
- [x] Configure Compose Multiplatform JVM desktop.
- [x] Add desktop app entrypoint.
- [x] Render top toolbar, left panel, right panel, and bottom drawer placeholders.
- [x] Add in-memory repo selection state.
- [x] Verify `./gradlew :runtime-desktop:run`.
- [x] Verify `./gradlew check`.

### Iteration 02: Repo Browser and Read-Only Tree

- [x] Implement local repo directory selection.
- [x] Validate selected directory as a Skill Bill repo.
- [x] Build tree from runtime authoring/discovery services.
- [x] Show horizontal skills.
- [x] Show platform packs and declared skills.
- [x] Show add-ons.
- [x] Show provider-neutral native-agent source targets when present.
- [x] Mark generated artifacts hidden or read-only.
- [x] Add explicit tree refresh.
- [x] Add repo browser tests.

### Iteration 03: Content Editor and Validation

- [ ] Load full `content.md` for selected governed skills.
- [ ] Add Markdown text editor.
- [ ] Track saved text, draft text, and dirty state.
- [ ] Save through runtime authoring operation.
- [ ] Preserve draft text on save failure.
- [ ] Display validation errors inline.
- [ ] Add repo validation action.
- [ ] Add read-only rendered preview, if included.
- [ ] Confirm generated `SKILL.md` is never editable.
- [ ] Add editor and validation tests.

### Iteration 04: Scaffold Wizards

- [ ] Add `New...` action entrypoints.
- [ ] Add horizontal skill wizard.
- [ ] Add platform pack wizard.
- [ ] Add platform override wizard.
- [ ] Add code-review area wizard.
- [ ] Add add-on wizard.
- [ ] Generate scaffold payload contract JSON.
- [ ] Run dry-run before apply when available.
- [ ] Show planned operations.
- [ ] Execute scaffold through runtime operation.
- [ ] Refresh tree after scaffold.
- [ ] Add scaffold wizard tests.

### Iteration 05: Git Fork Publishing

- [ ] Add `GitGateway`.
- [ ] Detect branch, remotes, dirty files, staged files, and ahead/behind state.
- [ ] Show changed-file list.
- [ ] Show selected-file diff.
- [ ] Stage and unstage files.
- [ ] Commit staged files with user message.
- [ ] Push current branch to `origin`.
- [ ] Detect canonical-vs-fork remote risk.
- [ ] Support explicit remote configuration.
- [ ] Generate GitHub compare URL when possible.
- [ ] Add local bare-remote Git smoke test.

### Iteration 06: Packaging and Release Polish

- [ ] Document source-run command.
- [ ] Add package task documentation.
- [ ] Add first-run repo selection guidance.
- [ ] Document fork setup workflow.
- [ ] Document authored-source-only editing boundary.
- [ ] Add README or getting-started link to the Skill Bill app.
- [ ] Add launch smoke coverage.
- [ ] Verify `:runtime-desktop:packageDistributionForCurrentOS` on primary platform.
- [ ] Include desktop checks in the appropriate validation path.

### Cross-Cutting Done Criteria

- [ ] UI calls shared Skill Bill runtime services or CLI-equivalent adapters for governed behavior.
- [ ] UI does not duplicate manifest, scaffold, routing, validation, or native-agent render rules.
- [ ] Generated wrappers and pointer files are never editable.
- [ ] Scaffold and save failures surface runtime errors clearly.
- [ ] Fork publishing never rewrites remotes without explicit confirmation.
- [ ] Documentation explains that the app is optional and repo-based.
