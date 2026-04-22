# Getting Started

A practical guide to installing Skill Bill, understanding the shipped surfaces, and using the full local CLI and MCP server without having to reverse-engineer the repo.

## What Skill Bill ships

Skill Bill has three primary operator surfaces:

- slash-command skills installed into your coding agent
- the local `skill-bill` CLI
- the local `skill-bill-mcp` server

Those surfaces sit on top of the same governed system:

- canonical skills in `skills/`
- manifest-driven platform packs in `platform-packs/`
- shared contracts in `orchestration/`
- runtime code in `skill_bill/`

## Install

### 1. Clone the repo

```bash
git clone https://github.com/Sermilion/skill-bill.git ~/Development/skill-bill
cd ~/Development/skill-bill
```

For a pinned install instead of `main`:

```bash
TAG=v0.x.y
git clone --branch "$TAG" --depth 1 https://github.com/Sermilion/skill-bill.git ~/Development/skill-bill
cd ~/Development/skill-bill
```

### 2. Run the installer

```bash
./install.sh
```

The installer asks for:

- which agents to install into
- which platform packs to install

Canonical skills in `skills/` are always installed. Platform packs are selected from the manifests discovered under `platform-packs/`.

### 3. Supported agent targets

| Agent | Install path |
|-------|--------------|
| GitHub Copilot | `~/.copilot/skills/` |
| Claude Code | `~/.claude/commands/` |
| GLM | `~/.glm/commands/` |
| OpenAI Codex | `~/.codex/skills/` or `~/.agents/skills/` |
| OpenCode | `~/.config/opencode/skills/` |

Installed skills are symlinks back to this repo, so a `git pull` updates installed behavior without reinstalling.

### 4. Optional local Python entry points

The package exposes:

- `skill-bill`
- `skill-bill-mcp`

If you want the local Python entry points available directly, install the package in your environment:

```bash
pip install -e .
```

## First run

The fastest way to confirm the install is healthy:

```bash
skill-bill doctor
skill-bill telemetry status --format json
```

Then use the stable skill entry points from your agent:

- `/bill-code-review`
- `/bill-quality-check`
- `/bill-feature-implement`
- `/bill-feature-verify`

## Shipped skill surfaces

### Stable base skills

These are the main user-facing entry points:

- `/bill-code-review`
- `/bill-quality-check`
- `/bill-feature-implement`
- `/bill-feature-verify`
- `/bill-pr-description`
- `/bill-create-skill`
- `/bill-grill-plan`
- `/bill-boundary-decisions`
- `/bill-boundary-history`
- `/bill-feature-guard`
- `/bill-feature-guard-cleanup`
- `/bill-unit-test-value-check`
- `/bill-skill-remove`

### Reference platform packs

The shipped reference packs are:

- `kotlin`: Kotlin baseline review and quality-check behavior
- `kmp`: KMP review depth layered on top of Kotlin, including Android/KMP add-ons

The platform-specific skill implementations live under `platform-packs/<platform>/`.

### Skills vs workflows

Skill Bill keeps these separate on purpose:

- skills are reusable, user-facing units such as routed code review or quality check
- workflows are top-level orchestrators that need durable step state, artifact handoff, and resume semantics

Current workflow families:

- `bill-feature-implement`
- `bill-feature-verify`

Those workflows are exposed both as skills and through local CLI/MCP workflow-state surfaces.

## The `skill-bill` CLI

The local CLI is the operator and maintainer surface for telemetry, workflow state, learnings, governed skill authoring, validation, and install primitives.

### Review import and triage

| Command | Purpose |
|---------|---------|
| `skill-bill import-review` | Import a review output file or stdin into the local SQLite store |
| `skill-bill record-feedback` | Record explicit feedback for one or more imported findings |
| `skill-bill triage` | List numbered findings and record triage decisions |
| `skill-bill stats` | Show aggregate or per-run review acceptance metrics |
| `skill-bill implement-stats` | Show aggregate `bill-feature-implement` local metrics |
| `skill-bill verify-stats` | Show aggregate `bill-feature-verify` local metrics |

### Learnings management

| Command | Purpose |
|---------|---------|
| `skill-bill learnings add` | Create a learning from a rejected review finding |
| `skill-bill learnings list` | List stored learnings |
| `skill-bill learnings show` | Show one learning entry |
| `skill-bill learnings resolve` | Resolve active learnings for a repo or skill context |
| `skill-bill learnings edit` | Edit a learning entry |
| `skill-bill learnings disable` | Disable a learning entry |
| `skill-bill learnings enable` | Re-enable a learning entry |
| `skill-bill learnings delete` | Delete a learning entry |

### Telemetry

| Command | Purpose |
|---------|---------|
| `skill-bill telemetry status` | Show local telemetry configuration and pending sync state |
| `skill-bill telemetry sync` | Flush pending telemetry events to the active proxy target |
| `skill-bill telemetry capabilities` | Show proxy/relay read-write capabilities |
| `skill-bill telemetry stats verify` | Fetch remote org-wide `bill-feature-verify` metrics |
| `skill-bill telemetry stats implement` | Fetch remote org-wide `bill-feature-implement` metrics |
| `skill-bill telemetry enable` | Enable telemetry at `anonymous` or `full` level |
| `skill-bill telemetry disable` | Disable telemetry |
| `skill-bill telemetry set-level` | Set telemetry to `off`, `anonymous`, or `full` |

### Workflow state

`bill-feature-implement` workflow state:

| Command | Purpose |
|---------|---------|
| `skill-bill workflow list` | List recent persisted implement workflows |
| `skill-bill workflow show` | Show raw persisted workflow state |
| `skill-bill workflow resume` | Explain how to resume or recover a workflow |
| `skill-bill workflow continue` | Reopen a resumable workflow and emit a continuation brief |

`bill-feature-verify` workflow state:

| Command | Purpose |
|---------|---------|
| `skill-bill verify-workflow list` | List recent persisted verify workflows |
| `skill-bill verify-workflow show` | Show raw persisted verify workflow state |
| `skill-bill verify-workflow resume` | Explain how to resume or recover a verify workflow |
| `skill-bill verify-workflow continue` | Reopen a resumable verify workflow and emit a continuation brief |

### Governed skill authoring and maintenance

| Command | Purpose |
|---------|---------|
| `skill-bill list` | List content-managed skills and authoring status |
| `skill-bill show <skill>` | Show one governed skill, its content status, and next commands |
| `skill-bill explain [skill]` | Explain the governed authoring boundary and workflow |
| `skill-bill validate` | Run full repo validation or targeted skill validation |
| `skill-bill upgrade` | Regenerate scaffold-managed wrappers without touching sidecars |
| `skill-bill render` | Alias for `upgrade` |
| `skill-bill edit <skill>` | Edit `content.md` and regenerate the wrapper |
| `skill-bill fill <skill>` | Write authored body text into `content.md` and validate |
| `skill-bill new-skill` | Scaffold a new skill from JSON payload or interactive prompts |
| `skill-bill new` | Alias for `new-skill` |
| `skill-bill create-and-fill` | Scaffold one skill, then immediately author `content.md` |
| `skill-bill new-addon` | Create a governed add-on file in an existing platform pack |

### Install and health primitives

| Command | Purpose |
|---------|---------|
| `skill-bill doctor` | Show local install and telemetry health |
| `skill-bill version` | Show the installed Skill Bill version |
| `skill-bill install agent-path <agent>` | Print the canonical install path for one agent |
| `skill-bill install detect-agents` | List detected agents and install paths |
| `skill-bill install link-skill` | Symlink one skill directory into a target agent path |

## The `skill-bill-mcp` server

The MCP server exposes Skill Bill’s local primitives as agent tools. This is the primary integration path when an agent can call local MCP tools directly.

### Review telemetry and learnings tools

| MCP tool | Purpose |
|----------|---------|
| `import_review` | Import code-review output into the local store |
| `triage_findings` | Record triage decisions for imported findings |
| `resolve_learnings` | Resolve active learnings for a repo or skill context |
| `review_stats` | Show aggregate or per-run review metrics |
| `feature_implement_stats` | Show aggregate local implement metrics |
| `feature_verify_stats` | Show aggregate local verify metrics |
| `telemetry_remote_stats` | Fetch remote org-wide workflow metrics |
| `telemetry_proxy_capabilities` | Show proxy/relay capability support |
| `doctor` | Show local install and telemetry health |

### `bill-feature-implement` workflow tools

| MCP tool | Purpose |
|----------|---------|
| `feature_implement_started` | Emit the started lifecycle event |
| `feature_implement_workflow_open` | Create a persisted workflow record |
| `feature_implement_workflow_update` | Update workflow state, steps, and artifacts |
| `feature_implement_workflow_get` | Get one workflow by id |
| `feature_implement_workflow_list` | List recent implement workflows |
| `feature_implement_workflow_latest` | Fetch the most recently updated implement workflow |
| `feature_implement_workflow_resume` | Build a resume/recovery payload |
| `feature_implement_workflow_continue` | Reopen a resumable workflow and build a continuation brief |
| `feature_implement_finished` | Emit the finished lifecycle event |

### Quality-check, verify, and PR-description tools

| MCP tool | Purpose |
|----------|---------|
| `quality_check_started` | Emit quality-check started telemetry |
| `quality_check_finished` | Emit quality-check finished telemetry |
| `feature_verify_started` | Emit feature-verify started telemetry |
| `feature_verify_finished` | Emit feature-verify finished telemetry |
| `feature_verify_workflow_open` | Create a persisted verify workflow record |
| `feature_verify_workflow_update` | Update verify workflow state |
| `feature_verify_workflow_get` | Get one verify workflow by id |
| `feature_verify_workflow_list` | List recent verify workflows |
| `feature_verify_workflow_latest` | Fetch the most recently updated verify workflow |
| `feature_verify_workflow_resume` | Build a verify resume/recovery payload |
| `feature_verify_workflow_continue` | Reopen a resumable verify workflow and build a continuation brief |
| `pr_description_generated` | Emit PR-description telemetry |

### Scaffolding tool

| MCP tool | Purpose |
|----------|---------|
| `new_skill_scaffold` | Scaffold a new governed skill from a validated payload |

## When to use each surface

Use slash-command skills when:

- you want the stable end-user workflow inside your coding agent
- you want routed platform behavior rather than raw local primitives

Use the CLI when:

- you are operating on local files, telemetry, validation, or workflow state directly
- you are authoring, editing, scaffolding, or validating governed skills
- you want text or JSON output outside the agent runtime

Use MCP when:

- your agent can call local tools directly
- you want agent-accessible workflow state, telemetry, or scaffolding without shelling out
- you want orchestration code to consume structured tool outputs rather than parsing CLI text

## Customization

Project-local customization flows through:

- `AGENTS.md` for repo-wide guidance
- `.agents/skill-overrides.md` for skill-specific overrides

For rollout strategy, trust-vs-verify guidance, and team customization patterns, use [Getting Started for Teams](getting-started-for-teams.md).

## Reference docs

- [Getting Started for Teams](getting-started-for-teams.md)
- [Review Telemetry](review-telemetry.md)
- [Roadmap](ROADMAP.md)
- `orchestration/shell-content-contract/PLAYBOOK.md`
- `orchestration/workflow-contract/PLAYBOOK.md`
