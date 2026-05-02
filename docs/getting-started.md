# Getting Started

Skill Bill installs governed agent skills plus a local runtime. The runtime is packaged Kotlin: normal `skill-bill` and `skill-bill-mcp` use the built distribution scripts built by `./install.sh`, not Gradle `run` tasks and not a Python runtime selector.

Use this guide when you want to install Skill Bill, understand the runtime model, and know which behavior is enforced by contracts versus model reasoning.

## What Ships

Skill Bill has three operator surfaces:

- installed slash-command skills such as `/bill-code-review` and `/bill-quality-check`
- the local `skill-bill` CLI
- the local `skill-bill-mcp` stdio MCP server

Those surfaces use the same governed repo structure:

- `skills/` contains canonical user-facing skills
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

For a pinned install:

```bash
TAG=v0.x.y
git clone --branch "$TAG" --depth 1 https://github.com/Sermilion/skill-bill.git ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh
```

The installer builds the Kotlin CLI and MCP distributions, verifies the packaged bin scripts, then links selected skills into detected agent directories.

Supported install targets:

| Agent | Install path |
|-------|--------------|
| GitHub Copilot | `~/.copilot/skills/` |
| Claude Code | `~/.claude/commands/` |
| GLM | `~/.glm/commands/` |
| OpenAI Codex (skills) | `~/.codex/skills/` or `~/.agents/skills/` |
| OpenAI Codex (native subagent TOMLs) | `~/.codex/agents/` or `~/.agents/agents/` |
| OpenCode (skills) | `~/.config/opencode/skills/` |
| OpenCode (native subagent markdown) | `~/.config/opencode/agents/` |

Installed skills are symlinks back to the checkout. Updating the checkout updates installed skill behavior.

On Codex and OpenCode, orchestrators that delegate to specialists also install native subagent definitions for supported runtime surfaces. Codex installs TOMLs discovered from `platform-packs/<slug>/**/codex-agents/*.toml` and `skills/<slug>/**/codex-agents/*.toml` into `~/.codex/agents/` (with `~/.agents/agents/` fallback) and resolves spawn instructions by TOML `name`. OpenCode installs markdown agents discovered from the matching `opencode-agents/*.md` walks into `~/.config/opencode/agents/` and resolves by filename-derived agent name; operators can also invoke them manually with `@<name>`. Today this covers the `bill-kmp-code-review` specialists, the `bill-kotlin-code-review` specialists, and the `bill-feature-implement` workflow phases (pre-planning, planning, implementation, implementation-fix, completeness-audit, quality-check, pr-description). `bill-feature-verify` has no verify-specific native subagents; it delegates review through `bill-code-review` and keeps feature-flag, completeness, and verdict audits inline. Parsing tolerance for `RESULT:` blocks across runtimes is documented at [`skills/bill-feature-implement/parsing_tolerance.md`](../skills/bill-feature-implement/parsing_tolerance.md).

## Runtime Model

Normal use is Kotlin-only:

- `skill-bill` launches the packaged Kotlin CLI distribution.
- `skill-bill-mcp` launches the packaged Kotlin stdio MCP distribution.
- The installer registers MCP shims to the packaged Kotlin server.
- Gradle is only used by maintainers to build and validate the runtime, not by installed commands during normal use.
- Python remains for explicit repo tooling such as validation scripts. It is not a normal CLI or MCP runtime fallback.

If a packaged Kotlin distribution is missing, launcher behavior fails closed with install/build guidance. It does not silently run Gradle and does not fall back to Python.

## First Checks

After install:

```bash
skill-bill version
skill-bill doctor
skill-bill telemetry status --format json
```

Then try the stable skill entry points in your agent:

- `/bill-code-review`
- `/bill-quality-check`
- `/bill-feature-implement`
- `/bill-feature-verify`

## Runtime Fallback Boundary

Skill Bill separates fail-closed contract behavior from best-effort operational behavior.

Fail closed:

- missing packaged CLI or MCP distributions
- malformed MCP arguments for strict schemas
- wrong shell contract versions
- missing platform manifests
- missing declared platform-pack content files
- missing required governed `SKILL.md` sections
- invalid scaffold payloads
- validation drift in generated wrappers or agent configs

Degrade or report explicitly:

- telemetry can be off or queued locally when sync is unavailable
- remote telemetry reads report capability or network failures without blocking local work
- agent detection may find no local agent directories; install/link commands can still target explicit paths
- learnings may resolve to `none`
- model-mediated review output can be uncertain and should mark confidence accordingly

Rollback for a broken runtime is to install a previous release. Do not expect a Python runtime selector to recover current CLI or MCP execution.

## Strict vs Model-Mediated Guarantees

Strict and loud-fail guarantees:

- MCP tools publish strict schemas for priority workflow, telemetry, review, learning, scaffold, and workflow-state tools. Unknown top-level arguments are rejected before handler dispatch when the schema is strict.
- Shell and platform-pack fixtures enforce the governed contract version, manifest shape, declared files, required wrapper sections, and sibling sidecars.
- Scaffold and validation commands operate on structured manifests and generated wrappers; invalid payloads or drift fail the command.
- `install link-skill` creates real symlinks to explicit directories and is covered by Kotlin CLI tests.

Model-mediated guarantees:

- review reasoning, implementation planning, audit reasoning, and PR description prose are produced by the active model using the governed instructions
- finding severity and confidence are signals, not proof
- workflow handoffs and audits are structured, but the judgement inside them still needs human review for high-risk changes

Use strict guarantees for compatibility and safety boundaries. Use model-mediated output as an expert second opinion that should be checked against the code.

## Governance System vs Reference Packs

Skill Bill is the governance system: routing, manifests, shell contracts, validation, installer behavior, workflow state, telemetry, and authoring rules.

The shipped `kotlin` and `kmp` platform packs are reference packs. They are real, validated, ready to use, and useful examples, but they are not the product boundary. Teams can fork, replace, or add conforming packs as long as discovery stays manifest-driven and the shell contract version remains locked.

Reference packs currently shipped:

- `kotlin`: Kotlin baseline review and quality-check behavior
- `kmp`: Kotlin baseline plus Android/KMP review depth and governed add-ons

## Common CLI Surfaces

Review and telemetry:

| Command | Purpose |
|---------|---------|
| `skill-bill import-review` | Import review output into the local SQLite store |
| `skill-bill record-feedback` | Record feedback for imported findings |
| `skill-bill triage` | Record triage decisions |
| `skill-bill stats` | Show review acceptance metrics |
| `skill-bill implement-stats` | Show local `bill-feature-implement` metrics |
| `skill-bill verify-stats` | Show local `bill-feature-verify` metrics |
| `skill-bill telemetry status` | Show telemetry configuration and pending sync state |
| `skill-bill telemetry sync` | Flush queued telemetry |

Workflow state:

| Command | Purpose |
|---------|---------|
| `skill-bill workflow list` | List persisted implement workflows |
| `skill-bill workflow show` | Show one implement workflow |
| `skill-bill workflow resume` | Build a resume/recovery explanation |
| `skill-bill workflow continue` | Reopen a resumable implement workflow |
| `skill-bill verify-workflow list` | List persisted verify workflows |
| `skill-bill verify-workflow show` | Show one verify workflow |
| `skill-bill verify-workflow resume` | Build a verify resume/recovery explanation |
| `skill-bill verify-workflow continue` | Reopen a resumable verify workflow |

Authoring and install:

| Command | Purpose |
|---------|---------|
| `skill-bill list` | List content-managed skills |
| `skill-bill show <skill>` | Inspect one governed skill |
| `skill-bill explain [skill]` | Explain the governed authoring boundary |
| `skill-bill validate` | Run repo or targeted governed-skill validation |
| `skill-bill render` | Regenerate scaffold-managed wrappers |
| `skill-bill fill <skill>` | Write authored `content.md` text and validate |
| `skill-bill new --payload <file>` | Scaffold a governed skill or platform pack |
| `skill-bill new-addon` | Create a pack-owned add-on |
| `skill-bill doctor` | Show local install and telemetry health |
| `skill-bill install agent-path <agent>` | Print an agent install path |
| `skill-bill install detect-agents` | List detected agents |
| `skill-bill install link-skill` | Symlink one skill directory into a target path |

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

In normal team usage, remove generated artifacts with your usual VCS workflow instead of deleting committed pack files by hand.

## MCP Server

`skill-bill-mcp` exposes the same local runtime primitives as structured MCP tools. It is useful when an agent can call local tools directly and should not parse CLI text.

Primary MCP groups:

- review and learning tools: `import_review`, `triage_findings`, `resolve_learnings`, `review_stats`
- telemetry tools: `telemetry_proxy_capabilities`, `telemetry_remote_stats`
- implement workflow tools: `feature_implement_started`, `feature_implement_workflow_open`, `feature_implement_workflow_update`, `feature_implement_workflow_get`, `feature_implement_workflow_continue`, `feature_implement_finished`
- verify workflow tools: `feature_verify_started`, `feature_verify_workflow_open`, `feature_verify_workflow_update`, `feature_verify_workflow_get`, `feature_verify_workflow_continue`, `feature_verify_finished`
- quality and PR tools: `quality_check_started`, `quality_check_finished`, `pr_description_generated`
- scaffold tool: `new_skill_scaffold`
- health tool: `doctor`

## Validation Gate

Maintainers should run the full gate before shipping runtime, scaffold, contract, docs, or agent-config changes:

```bash
.venv/bin/python3 -m unittest discover -s tests
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```

`./gradlew check` is a maintainer validation command inside `runtime-kotlin/`; it is not part of normal installed command execution.

## Reference Docs

- [Getting Started for Teams](getting-started-for-teams.md)
- [Review Telemetry](review-telemetry.md)
- [Roadmap](ROADMAP.md)
- `orchestration/shell-content-contract/PLAYBOOK.md`
- `orchestration/workflow-contract/PLAYBOOK.md`
