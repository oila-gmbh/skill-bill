# Skill Bill

A governed system for portable AI-agent behavior: stable skill entry points, shared orchestration, validator-backed contracts, cross-agent installers, scaffolding, workflow state, and local-first telemetry that keep one source of truth from drifting as the repo grows.

Skill Bill is a governance product, not a prompt dump. The product is the framework:

- stable user-facing commands such as `/bill-code-review` and `/bill-quality-check`
- manifest-driven platform packs under `platform-packs/`
- shell/content contracts under `orchestration/`
- runtime surfaces in the `skill-bill` CLI and `skill-bill-mcp` server
- validator-backed authoring and scaffolding flows

The first shipped workflow family is engineering-focused, but the same governed shape now supports editorial workflows. `/bill-editorial-assignment-desk` runs a Readian-backed assignment desk for journalism: fetch Spotlight and topic articles through MCP tools, verify sources, rank candidates, pause for journalist selection, and produce evidence-first story packs without drafting or publishing the article.

## Why it exists

Most prompt or skill repos degrade over time:

- names drift
- overlapping skills appear
- stack-specific behavior leaks into generic prompts
- different agents get different copies

Skill Bill treats skills more like software:

- stable base capabilities
- platform-specific overrides behind routers
- shared contracts instead of prompt folklore
- loud-fail validation instead of silent fallback
- one repo synced across multiple coding agents

## Core experience

Most daily use comes through a small set of stable commands:

- `/bill-code-review` routes to the matching platform review stack
- `/bill-quality-check` routes to the matching stack-specific checker
- `/bill-feature-implement` orchestrates spec-to-PR work
- `/bill-feature-verify` verifies a PR against a spec or design doc
- `/bill-pr-description` generates PR text and QA steps
- `/bill-editorial-assignment-desk` runs a governed editorial candidate desk backed by the Readian MCP boundary

Skill Bill also ships:

- authoring and maintenance skills such as `/bill-create-skill`, `/bill-boundary-decisions`, and `/bill-boundary-history`
- a local CLI for telemetry, learnings, workflow resume, validation, scaffolding, and install primitives
- an MCP server that exposes the same review, workflow, telemetry, and scaffolding primitives as agent tools

## Quick install

```bash
git clone https://github.com/Sermilion/skill-bill.git ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh
```

The installer:

- detects supported agents
- installs selected skills as symlinks back to this repo
- installs selected platform packs
- registers the local Skill Bill MCP server for agents that support MCP config

Supported install targets today:

- GitHub Copilot
- Claude Code
- OpenAI Codex (skills under `~/.codex/skills/`; native subagent TOMLs under `~/.codex/agents/`, both with `~/.agents/...` fallback)
- OpenCode (skills under `~/.config/opencode/skills/`; native subagent markdown under `~/.config/opencode/agents/`)

Using GLM as a model in Claude Code? Skill Bill installs to the Claude Code commands directory — no separate target needed. GLM is a model, not a harness.

Native subagent definitions are installed only for orchestrators that ship them. Discovery is manifest-driven under `platform-packs/<slug>/**/codex-agents/*.toml` and `skills/<slug>/**/codex-agents/*.toml` for Codex, and the matching `opencode-agents/*.md` walks for OpenCode. Codex resolves runtime-neutral spawn prose by TOML `name`; OpenCode resolves by filename-derived markdown agent name and also supports manual `@<name>` invocation. Today this covers the `bill-kmp-code-review` KMP specialists, the `bill-kotlin-code-review` Kotlin specialists, and the `bill-feature-implement` workflow phases (pre-planning, planning, implementation, implementation-fix, completeness-audit, quality-check, pr-description). `bill-feature-verify` has no verify-specific native subagents; it delegates review through `bill-code-review` and keeps its verify audits inline. Parsing tolerance for `RESULT:` blocks across runtimes is documented at [`skills/bill-feature-implement/parsing_tolerance.md`](skills/bill-feature-implement/parsing_tolerance.md).

## Start here

- [Getting Started](docs/getting-started.md): primary onboarding guide, install flow, skill surfaces, full `skill-bill` CLI coverage, and full MCP tool coverage
- [Getting Started for Teams](docs/getting-started-for-teams.md): rollout guidance, customization strategy, trust-vs-verify guidance, and adoption patterns
- [Review Telemetry](docs/review-telemetry.md): telemetry contract, learnings, local DB usage, and remote proxy stats
- [Roadmap](docs/ROADMAP.md): product direction, priorities, and strategic framing

## Reference skill catalog

### Canonical Skills (14 skills)

| Skill | Purpose |
|-------|---------|
| `/bill-boundary-decisions` | Record architectural and implementation decisions in `agent/decisions.md` |
| `/bill-boundary-history` | Record reusable feature history in `agent/history.md` |
| `/bill-code-review` | Stable code-review entry point that routes to the matching platform pack |
| `/bill-create-skill` | Scaffold and bootstrap governed skills and platform packs |
| `/bill-feature-guard` | Add feature-flag rollout safety to an implementation |
| `/bill-feature-guard-cleanup` | Remove feature flags and legacy code after rollout |
| `/bill-feature-implement` | End-to-end feature workflow from spec through review and validation |
| `/bill-feature-verify` | Verify a PR against a task spec or design doc |
| `/bill-editorial-assignment-desk` | Run a governed Readian-backed editorial assignment desk |
| `/bill-grill-plan` | Stress-test a plan or design by walking the decision tree |
| `/bill-pr-description` | Generate a PR title, description, and QA steps |
| `/bill-quality-check` | Stable quality-check entry point that routes to the matching checker |
| `/bill-skill-remove` | Remove an existing skill or platform skill set and clean up installs |
| `/bill-unit-test-value-check` | Review unit tests for low-value or tautological coverage |

## Architecture snapshot

The main governed layers are:

- `skills/`: canonical user-facing skills and pre-shell platform overrides
- `platform-packs/`: manifest-driven platform review and quality-check depth
- `orchestration/`: routing, delegation, workflow, telemetry, and shell-content contracts
- `runtime-kotlin/`: packaged Kotlin CLI, MCP server, workflow state, telemetry, scaffolding, and install primitives
- `skill_bill/`: Python maintainer tooling and compatibility code retained during the remaining retirement subtasks
- `scripts/`: repo validators and migration helpers

Governed pack skills use a thin `SKILL.md` wrapper plus sibling `content.md` and `shell-ceremony.md`. Workflow shells such as `bill-feature-implement`, `bill-feature-verify`, and `bill-editorial-assignment-desk` follow the same split: shell-owned orchestration in `SKILL.md`, authored execution guidance in `content.md`, and stable contracts in sibling references when needed.

## Reference packs

The shipped reference inventory is intentionally narrow and deep:

- `kotlin`: baseline Kotlin review and quality-check behavior
- `kmp`: Kotlin baseline plus Android/KMP depth and governed add-ons

Routing, validation, and installation are manifest-driven, so the system is designed to accept any conforming pack rather than a hardcoded shortlist.

## Validation

Core validation commands:

```bash
.venv/bin/python3 -m unittest discover -s tests
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```

## License

[MIT](LICENSE)
