# Getting Started

Skill Bill installs governed agent workflows plus a local runtime. The fastest way to understand the product is to run `/bill-feature-task` on a small feature spec: it plans the work, implements it, reviews it, validates it, records history when relevant, and prepares the PR handoff.

The runtime is packaged Kotlin: normal `skill-bill` and `skill-bill-mcp` use distribution scripts built by `./install.sh`, not Gradle `run` tasks and not a legacy runtime selector.

Use this guide when you want to install Skill Bill, understand the runtime model, and know which behavior is enforced by contracts versus model reasoning.

## What Ships

Skill Bill has three operator surfaces:

- installed slash-command workflows such as `/bill-feature-task`, `/bill-code-review`, and `/bill-code-check`
- the local `skill-bill` CLI
- the local `skill-bill-mcp` stdio MCP server

The bundled skills are reference workflows and reusable workflow components. They are useful defaults, but the framework does not depend on them being preserved. Teams can fork, replace, or remove bundled skills and author their own governed workflows and platform packs.

Those surfaces use the same governed repo structure:

- `skills/` contains canonical user-facing skill sources. A source skill directory contains `content.md` and, only when needed, `native-agents/`.
- `platform-packs/` contains manifest-declared platform packs
- `orchestration/` contains shared contracts and playbooks
- `runtime-kotlin/` contains the packaged CLI and MCP runtime
- `scripts/` and `tests/` contain repo validation and maintainer tooling

## Install

```bash
git clone https://github.com/Sermilion/skill-bill.git ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh
```

The default `./install.sh` is the **prebuilt** path: it downloads and checksum-verifies the prebuilt release runtime images for your host, so it needs no JDK and no Gradle build. Supported prebuilt hosts are `macos-arm64`, `macos-x64`, `windows-x64`, and `linux-x64`; any other host auto-falls back to a from-source build.

For a pinned install, use the `--release TAG` mechanism (or the `SKILL_BILL_RELEASE_TAG` environment variable) instead of cloning a specific branch:

```bash
./install.sh --release v0.x.y
# or, equivalently:
SKILL_BILL_RELEASE_TAG=v0.x.y ./install.sh
```

`--release` pins the prebuilt artifacts to a specific release tag. It is ignored under `--from-source`, where the runtime is built from your current checkout.

On the default prebuilt path the installer fetches and checksum-verifies the Kotlin CLI and MCP distribution images from the matching GitHub release — it does **not** build them locally. With `--from-source` the installer instead builds the Kotlin CLI and MCP distributions with Gradle (JDK required). Either way it then copies the packaged runtime into `~/.skill-bill/runtime/`, verifies the installed bin scripts, installs `skill-bill` and `skill-bill-mcp` launchers into `${SKILL_BILL_BIN_DIR:-~/.local/bin}`, renders selected skills into staging, then links those staged skills into detected agent directories. MCP registrations point at the installed runtime copy so Gradle cleanup or IDE rebuilds inside the checkout do not break agent startup. If that launcher directory is not on `PATH`, install finishes with an explicit warning.

`./install.sh` collects install choices and delegates the durable work to the
packaged runtime command `skill-bill install apply`. The shell script still owns
bootstrap prompts and runtime distribution installation, but the runtime owns
planning, staging, symlink/native-agent linking, telemetry configuration, MCP
registration, replacement cleanup, and structured failure reporting.

Installer prompts cover:

- agent selection: manual or detected `copilot`, `claude`, `codex`,
  `opencode`, and `junie`
- platform packs: all packs, selected packs, or base skills only; selected
  packs are discovered from `platform-packs/` manifests
- telemetry: `anonymous`, `full`, or `off`

MCP registration is always applied for supported agent config entries so guided
workflows, learnings, telemetry, and workflow resume are available after
install.

Base skills are always included. Optional platform packs add their declared
review and quality-check skills after manifest validation. All installed
content-managed skills are rendered into `~/.skill-bill/installed-skills/` and
agent entries link to that staging cache, not directly to source directories.
When a platform pack declares `code_review_composition.baseline_layers`, the
rendered baseline review skill includes review-composition instructions so the
referenced baseline layer runs before pack-local specialists.

On Windows, Skill Bill uses explicit symlink preflight outcomes. If symlink
support is not available, install output tells the operator to enable Developer
Mode, run an elevated shell, or rerun after fixing the target filesystem. The
runtime preserves user-owned files and reports a structured failure rather than
silently replacing non-Skill-Bill content.

Supported install targets:

| Agent                                      | Install path                              |
|--------------------------------------------|-------------------------------------------|
| GitHub Copilot                             | `~/.copilot/skills/`                      |
| Claude Code                                | `~/.claude/commands/`                     |
| Claude Code (native subagent markdown)     | `~/.claude/agents/`                       |
| OpenAI Codex (skills)                      | `~/.codex/skills/` or `~/.agents/skills/` |
| OpenAI Codex (native subagent TOMLs)       | `~/.codex/agents/`                        |
| OpenCode (skills)                          | `~/.config/opencode/skills/`              |
| OpenCode (native subagent markdown)        | `~/.config/opencode/agents/`              |
| JetBrains Junie (skills)                   | `~/.junie/skills/`                        |
| JetBrains Junie (native subagent markdown) | `~/.junie/agents/`                        |

Using GLM as a model in Claude Code? Skill Bill installs to the Claude Code commands directory — no separate target needed. GLM is a model, not a harness.

Installed skills are symlinks to rendered staging directories under `~/.skill-bill/installed-skills/`. Re-run `./install.sh` after changing the checkout so installed agents pick up refreshed `SKILL.md` wrappers, support pointer files, and content hashes.

On Claude, Codex, OpenCode, and Junie, orchestrators that delegate to specialists also install native subagent definitions for supported runtime surfaces. Native subagent sources live as provider-neutral `native-agents/agents.yaml` bundles or standalone `native-agents/<name>.md` files. New and rendered neutral sources include `contract_version: "0.1"`; the parser still accepts older unpinned sources so existing repos can migrate gradually. Install renders those sources into `~/.skill-bill/native-agents/` before linking Claude markdown into `~/.claude/agents/`, Codex TOMLs into `~/.codex/agents/`, OpenCode markdown into `~/.config/opencode/agents/`, and Junie markdown into `~/.junie/agents/`; generated provider files are not checked into the repo. `~/.agents/agents/` is only a Skill Bill compatibility path for Codex homes without a `.codex` root, not the primary documented Codex custom-agent location. Claude and Junie use Markdown/YAML custom-subagent frontmatter, Codex resolves spawn instructions by TOML `name`, and OpenCode resolves by filename-derived agent name and supports manual `@<name>` invocation. Today this covers the `bill-kmp-code-review` specialists, the `bill-kotlin-code-review` specialists, and the `bill-feature-task` workflow phases (pre-planning, planning, implementation, implementation-fix, completeness-audit, quality-check, pr-description). `bill-feature-verify` has no verify-specific native subagents; it delegates review through `bill-code-review` and keeps feature-flag, completeness, and verdict audits inline. Parsing tolerance for `RESULT:` blocks across runtimes is documented inline in `skills/bill-feature-task/content.md`.

## Runtime Model

Normal use is Kotlin-only:

- `skill-bill` launches the packaged Kotlin CLI distribution.
- `skill-bill-mcp` launches the packaged Kotlin stdio MCP distribution.
- The installer registers MCP shims to the packaged Kotlin server.
- Gradle is not used by the default prebuilt install or by installed commands during normal use; it is needed only by maintainers building/validating the runtime and by the opt-in `./install.sh --from-source` install path.
- Repo validation commands are Kotlin-backed. The legacy maintainer stack is no longer required for current install, validation, or maintainer workflows.

If a packaged Kotlin distribution is missing, launcher behavior fails closed with install/build guidance. It does not silently run Gradle and does not fall back to a legacy runtime.

## Desktop App

The optional Skill Bill desktop app lives in `runtime-kotlin/runtime-desktop`.
It is a Compose Desktop client over the same runtime contracts used by the CLI;
it does not implement separate governance, manifest parsing, scaffold rules, or
install staging.

The canonical install path is still `./install.sh`. The script installs the
runtime, agent links, telemetry, MCP registration, and, when selected, a
per-user desktop app for the current host:

```bash
./install.sh --with-desktop-app
```

Use `--no-desktop-app` for a CLI-only install, or `--desktop-app-dir <path>` to
override the app install root. The desktop app install uses the loose
`prepareDesktopAppDistributable` output so the same script can work from macOS,
Linux, and Windows shells. Native DMG/MSI/DEB/RPM outputs are release artifacts,
not the only supported install path.

Uninstall is symmetric for the per-user app install:

```bash
./uninstall.sh
```

If you installed the app with a custom root, pass the same path back to the
uninstaller:

```bash
./uninstall.sh --desktop-app-dir <path>
```

Run from source:

```bash
cd runtime-kotlin
./gradlew :runtime-desktop:run
```

Build a loose desktop distribution:

```bash
cd runtime-kotlin
./gradlew :runtime-desktop:prepareDesktopAppDistributable
```

Build a native package for the current host:

```bash
cd runtime-kotlin
./gradlew :runtime-desktop:packageDistributionForCurrentOS
```

Specific package tasks are available when the host OS and native packaging
toolchain support them:

| Host        | Package task                         | Output intent                              |
|-------------|--------------------------------------|--------------------------------------------|
| macOS       | `:runtime-desktop:packageDmg`         | DMG desktop installer                       |
| Windows     | `:runtime-desktop:packageMsi`         | MSI desktop installer                       |
| Linux       | `:runtime-desktop:packageDeb`         | Debian/Ubuntu-style package                 |
| Linux       | `:runtime-desktop:packageRpm`         | RPM package, the Arch/CachyOS-friendly path |
| Any desktop | `:runtime-desktop:prepareDesktopAppDistributable` | Loose app directory fallback     |

Compose Desktop native packaging is host-limited: a Linux workstation should not
be expected to produce the macOS DMG or Windows MSI locally. For Arch/CachyOS,
try the RPM task first when the local packaging tools are present; otherwise use
the loose distributable or `packageDistributionForCurrentOS` output.

The package build stages a `skill-bill-runtime` app-resource bundle containing
the packaged `runtime-cli`, packaged `runtime-mcp`, authored `skills/`, dynamic
`platform-packs/`, and `orchestration/`. The installed desktop app locates that
bundle from app resources, or an explicit `skillbill.runtime.assets.dir` /
`SKILL_BILL_RUNTIME_ASSETS` override, and feeds those paths into the same
install plan/apply backend.

On first launch, the setup wizard asks for:

- agents: detected or manually selected supported agents
- platform packs: dynamically discovered packs, with base skills always included
- telemetry: `anonymous`, `full`, or `off`, matching CLI behavior

The wizard always includes MCP registration for supported agent config files.

The wizard writes through shared install plan/apply behavior and preserves the
governed source boundary. Authored `content.md`, manifests, add-ons, and
provider-neutral native-agent sources remain source; generated `SKILL.md`,
support pointer files, provider-native agent files, install staging, and native
package outputs remain generated artifacts.

## First Checks

After install:

```bash
skill-bill version
skill-bill doctor
skill-bill telemetry status --format json
```

Then try the stable skill entry points in your agent, in this order:

- `/bill-feature-spec` (optional prep-first path for spec-only sessions)
- `/bill-feature-task`
- `/bill-code-review`
- `/bill-code-check`
- `/bill-feature-verify`

Use `/bill-feature-task` first because it exercises the full governed path: feature spec, planning, implementation, routed review, validation, history, and PR handoff. Use `/bill-feature-spec` when you need standalone spec/decomposition preparation before implementation. Use `/bill-code-review` directly when you only need the review phase.

## Runtime Fallback Boundary

Skill Bill separates fail-closed contract behavior from best-effort operational behavior.

Fail closed:

- missing packaged CLI or MCP distributions
- malformed MCP arguments for strict schemas
- wrong shell contract versions
- missing platform manifests
- missing declared platform-pack content files
- missing generated wrapper sections during render/install validation
- extra files in `skills/` source directories other than `content.md` and optional `native-agents/`
- invalid scaffold payloads
- validation drift in generated wrappers or agent configs

Degrade or report explicitly:

- telemetry can be off or queued locally when sync is unavailable
- remote telemetry reads report capability or network failures without blocking local work
- agent detection may find no local agent directories; install/link commands can still target explicit paths
- learnings may resolve to `none`
- model-mediated review output can be uncertain and should mark confidence accordingly

Rollback for a broken runtime is to install a previous release. Do not expect a legacy runtime selector to recover current CLI or MCP execution.

## Strict vs Model-Mediated Guarantees

Strict and loud-fail guarantees:

- MCP tools publish strict schemas for priority workflow, telemetry, review, learning, scaffold, and workflow-state tools. Unknown top-level arguments are rejected before handler dispatch when the schema is strict.
- Shell and platform-pack fixtures enforce the governed contract version, manifest shape, declared files, generated wrapper sections, and generated support pointers.
- Scaffold and validation commands operate on structured manifests and `content.md` source files; invalid wizard input, invalid payloads, source-shape violations, or render drift fail the command. `skill-bill new --dry-run` previews planned files and manifest edits, including platform-pack baseline composition blocks; `skill-bill new --payload <file> --dry-run` is the scripted equivalent. `skill-bill show <skill>` exposes manifest-declared review composition for inspection. Legacy platform-pack payloads without `baseline_layers` continue to produce manifests with no composition section.
- `install link-skill` creates real symlinks to staged rendered skill directories and is covered by Kotlin CLI tests.

Model-mediated guarantees:

- review reasoning, implementation planning, audit reasoning, and PR description prose are produced by the active model using the governed instructions
- finding severity and confidence are signals, not proof
- workflow handoffs and audits are structured, but the judgement inside them still needs human review for high-risk changes

Use strict guarantees for compatibility and safety boundaries. Use model-mediated output as an expert second opinion that should be checked against the code.

## Bundled Workflows vs Framework

Skill Bill has two layers:

- the governed workflow framework: authoring rules, render/install staging, shell contracts, manifests, validators, CLI/MCP runtime, workflow state, telemetry, and cross-agent installation
- bundled reference workflows: `bill-feature-task`, `bill-code-review`, `bill-code-check`, `bill-feature-verify`, `bill-pr-description`, and supporting skills

The bundled workflows are production-usable defaults, not a lock-in boundary. A team can delete or replace them and still use the framework to build its own governed workflow system.

## Governance System vs Reference Packs

Skill Bill is the governance system: routing, manifests, shell contracts, validation, installer behavior, workflow state, telemetry, and authoring rules.

The shipped platform packs are reference packs. They are real, validated, ready to use, and useful examples, but they are not the product boundary. Teams can fork, replace, or add conforming packs as long as discovery stays manifest-driven and the shell contract version remains locked.

Reference packs currently shipped:

- `kotlin`: Kotlin baseline review and quality-check behavior
- `kmp`: Kotlin baseline plus Android/KMP review depth and governed add-ons

## Common CLI Surfaces

Review and telemetry:

| Command                       | Purpose                                             |
|-------------------------------|-----------------------------------------------------|
| `skill-bill import-review`    | Import review output into the local SQLite store    |
| `skill-bill record-feedback`  | Record feedback for imported findings               |
| `skill-bill triage`           | Record triage decisions                             |
| `skill-bill stats`            | Show review acceptance metrics                      |
| `skill-bill implement-stats`  | Show local `bill-feature-task` metrics         |
| `skill-bill verify-stats`     | Show local `bill-feature-verify` metrics            |
| `skill-bill telemetry status` | Show telemetry configuration and pending sync state |
| `skill-bill telemetry sync`   | Flush queued telemetry                              |
| `skill-bill telemetry capabilities` | Show configured proxy capabilities            |
| `skill-bill telemetry stats`  | Fetch aggregate remote workflow stats from the proxy |

Workflow state:

| Command                               | Purpose                                    |
|---------------------------------------|--------------------------------------------|
| `skill-bill workflow list`            | List persisted implement workflows         |
| `skill-bill workflow latest`          | Show the latest implement workflow         |
| `skill-bill workflow show`            | Show one implement workflow                |
| `skill-bill workflow resume`          | Build a resume/recovery explanation        |
| `skill-bill workflow continue`        | Reopen a resumable implement workflow      |
| `skill-bill verify-workflow list`     | List persisted verify workflows            |
| `skill-bill verify-workflow latest`   | Show the latest verify workflow            |
| `skill-bill verify-workflow show`     | Show one verify workflow                   |
| `skill-bill verify-workflow resume`   | Build a verify resume/recovery explanation |
| `skill-bill verify-workflow continue` | Reopen a resumable verify workflow         |

Authoring and install:

| Command                                 | Purpose                                                                                           |
|-----------------------------------------|---------------------------------------------------------------------------------------------------|
| `skill-bill list`                       | List content-managed skills                                                                       |
| `skill-bill show <skill>`               | Inspect one governed skill                                                                        |
| `skill-bill explain [skill]`            | Explain the governed authoring boundary                                                           |
| `skill-bill validate`                   | Run repo or targeted governed-skill validation                                                    |
| `skill-bill render`                     | Render generated wrappers to stdout or install output without writing generated files into source |
| `skill-bill fill <skill>`               | Write authored `content.md` text and validate                                                     |
| `skill-bill upgrade`                    | Validate governed render output and regenerate native-agent artifacts through runtime guardrails   |
| `skill-bill new`                        | Scaffold a governed skill or platform pack through deterministic prompts                           |
| `skill-bill create-and-fill`            | Scaffold and immediately author one content-managed skill                                         |
| `skill-bill new-addon`                  | Create a pack-owned add-on                                                                        |
| `skill-bill remove`                     | Remove a horizontal skill, platform pack, or governed add-on with cleanup                         |
| `skill-bill doctor`                     | Show local install and telemetry health                                                           |
| `skill-bill install agent-path <agent>` | Print an agent install path                                                                       |
| `skill-bill install detect-agents`      | List detected agents                                                                              |
| `skill-bill install link-skill`         | Render one skill into staging and symlink it into a target path                                   |
| `skill-bill validate-agent-configs`     | Run the agent-config/catalog/workflow-contract validator                                          |

## External Author Dry Run

The supported external-author flow is:

1. Scaffold a platform pack from a payload.
2. Validate the generated repo state.
3. Link one generated skill into an explicit agent path.
4. Remove the generated link and temporary pack artifacts.

Example payload:

```bash
cat > /tmp/skill-bill-pack.json <<'JSON'
{
  "scaffold_payload_version": "1.0",
  "kind": "platform-pack",
  "platform": "java",
  "skeleton_mode": "starter",
  "display_name": "Java",
  "description": "Use when reviewing Java server and library changes."
}
JSON

skill-bill new --payload /tmp/skill-bill-pack.json
skill-bill validate
skill-bill install link-skill \
  --source platform-packs/java/code-review/bill-java-code-review \
  --target-dir /tmp/skill-bill-agent/skills \
  --agent codex
rm /tmp/skill-bill-agent/skills/bill-java-code-review
rm -rf platform-packs/java
```

In normal team usage, remove scaffolded example files with your usual VCS workflow instead of deleting committed pack files by hand. The explicit `link-skill` target receives a symlink to a rendered staging directory, not to the source skill directory.

## Goal Observability

`bill-feature-goal` hands confirmed decompositions to the foreground
`skill-bill goal` runtime. The runtime owns a flat worker model: one runnable
subtask, one durable child workflow, one fresh child process. Native or nested
subagents may help the child session stay focused, but workflow-store state is
the reliability contract.

This keeps the SKILL-56 and SKILL-58 goal-runner behavior intact: status/watch
are read-only, raw child output stays hidden unless debug output is requested,
stale running children are reconciled against authoritative terminal outcomes,
and the goal advances only after a durable child outcome is written.

Run the goal normally to see compact progress while raw child output stays
hidden:

```bash
skill-bill goal SKILL-901
```

```text
goal SKILL-901: heartbeat subtask=1 step=implement liveness=durable_progress
goal_observability: issue_key=SKILL-901 subtask_id=1 workflow_phase=implement worker_role=foreground liveness_class=durable_progress sequence_number=1
```

Use read-only status or watch commands when checking an in-flight run:

```bash
skill-bill goal status SKILL-901
skill-bill goal watch SKILL-901 --interval-seconds 5 --max-refreshes 3
```

```text
latest_observability: phase=implement role=phase_subagent liveness=durable_progress sequence=8
watch_observability: index=2 phase=implement role=phase_subagent liveness=durable_progress sequence=8
```

Add current git activity only when needed:

```bash
skill-bill goal status SKILL-901 --diff-stat
skill-bill goal status SKILL-901 \
  --diff-hunk runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/GoalCliCommands.kt \
  --diff-hunk-max-hunks 2 \
  --diff-hunk-max-lines 20 \
  --diff-hunk-max-bytes 4000
```

```text
diff_stat: files_changed=3 insertions=12 deletions=4
selected_diff_hunks: count=1 truncated=false
selected_diff_line: hunk_index=1 line_index=1 path=runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/GoalCliCommands.kt staged=false text=+new
```

## MCP Server

`skill-bill-mcp` exposes the same local runtime primitives as structured MCP tools. It is useful when an agent can call local tools directly and should not parse CLI text.

Primary MCP groups:

- review and learning tools: `import_review`, `triage_findings`, `resolve_learnings`, `review_stats`
- telemetry tools: `telemetry_proxy_capabilities`, `telemetry_remote_stats`
- workflow stats tools: `feature_implement_stats`, `feature_verify_stats`
- implement workflow tools: `feature_implement_started`, `feature_implement_workflow_open`, `feature_implement_workflow_update`, `feature_implement_workflow_list`, `feature_implement_workflow_latest`, `feature_implement_workflow_get`, `feature_implement_workflow_resume`, `feature_implement_workflow_continue`, `feature_implement_finished`
- verify workflow tools: `feature_verify_started`, `feature_verify_workflow_open`, `feature_verify_workflow_update`, `feature_verify_workflow_list`, `feature_verify_workflow_latest`, `feature_verify_workflow_get`, `feature_verify_workflow_resume`, `feature_verify_workflow_continue`, `feature_verify_finished`
- quality and PR tools: `quality_check_started`, `quality_check_finished`, `pr_description_generated`
- scaffold tool: `new_skill_scaffold`
- health tool: `doctor`
- optional Readian bridge tools when configured: `readian_auth_status`, `readian_get_article`, `readian_get_articles_for_topic_query`, `readian_get_spotlight`, `readian_mark_story_status`, `readian_save_candidate`

## Validation Gate

Maintainers should run the full gate before shipping runtime, scaffold, contract, docs, or agent-config changes:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

`./gradlew check` is a maintainer validation command inside `runtime-kotlin/`; it is not part of normal installed command execution.

## Reference Docs

- [Getting Started for Teams](getting-started-for-teams.md)
- [Skill Source And Generation Model](skill-source-generation.md)
- [Review Telemetry](review-telemetry.md)
- [Roadmap](ROADMAP.md)
- `orchestration/shell-content-contract/PLAYBOOK.md`
- `orchestration/workflow-contract/PLAYBOOK.md`
