# Skill Bill

Skill Bill is a governed workflow platform for AI coding agents. Its flagship bundled workflow, `/bill-feature-implement`, turns a feature spec into planned, reviewed, validated, documented code.

The bundled skills are replaceable reference workflows. Teams can use them as-is, fork them, delete them, or build something completely different on the same governed framework.

Skill Bill is a governance product, not a prompt dump. The durable product is the framework:

- stable user-facing commands such as `/bill-code-review` and `/bill-quality-check`
- manifest-driven platform packs under `platform-packs/`
- shell/content contracts under `orchestration/`
- runtime surfaces in the `skill-bill` CLI and `skill-bill-mcp` server
- validator-backed authoring and scaffolding flows

The shipped workflow family is engineering-focused. `/bill-feature-implement` is the primary out-of-the-box workflow; `/bill-code-review`, `/bill-quality-check`, `/bill-pr-description`, boundary history, telemetry, workflow state, platform packs, add-ons, and native subagents are reusable workflow components that can also be invoked directly.

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

Most daily use starts with the feature workflow:

- `/bill-feature-implement` orchestrates spec-to-PR work

It composes the rest of the shipped system:

- `/bill-code-review` routes to the matching platform review stack
- `/bill-quality-check` routes to the matching stack-specific checker
- `/bill-pr-description` generates PR text and QA steps
- `/bill-boundary-history` records reusable implementation history when the change is significant
- workflow state and telemetry make long-running work resumable and measurable

The same components are still useful as standalone entry points:

- `/bill-code-review` for reviewing staged changes, PRs, commits, or files
- `/bill-quality-check` for running and fixing stack-specific validation
- `/bill-feature-verify` verifies a PR against a spec or design doc

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

After install:

```bash
skill-bill version
skill-bill doctor
skill-bill validate
skill-bill install detect-agents
skill-bill telemetry status
```

The installer:

- installs `skill-bill` and `skill-bill-mcp` launchers into `${SKILL_BILL_BIN_DIR:-~/.local/bin}`
- detects supported agents
- renders selected skills into `~/.skill-bill/installed-skills/` and links agent skill entries to those staged outputs
- installs selected platform packs
- registers the local Skill Bill MCP server for agents that support MCP config

Supported install targets today:

- GitHub Copilot
- Claude Code (skills under `~/.claude/commands/`; native subagent markdown under `~/.claude/agents/`)
- OpenAI Codex (skills under `~/.codex/skills/`, with `~/.agents/skills/` compatibility fallback; native subagent TOMLs under `~/.codex/agents/`)
- OpenCode (skills under `~/.config/opencode/skills/`; native subagent markdown under `~/.config/opencode/agents/`)
- JetBrains Junie (skills under `~/.junie/skills/`; native subagent markdown under `~/.junie/agents/`; MCP config under `~/.junie/mcp/mcp.json`)

Using GLM as a model in Claude Code? Skill Bill installs to the Claude Code commands directory — no separate target needed. GLM is a model, not a harness.

Native subagent definitions are installed only for orchestrators that ship them. The source of truth is either provider-neutral markdown files under `native-agents/<name>.md` or bundled entries in `native-agents/agents.yaml`; provider-specific Claude markdown, Codex TOML, OpenCode markdown, and Junie markdown are generated at install time into `~/.skill-bill/native-agents/` and linked into each runtime's agent directory. Skill Bill installs Codex native subagents to `~/.codex/agents/`; `~/.agents/agents/` is only a Skill Bill compatibility path for homes that do not have a `.codex` root. `skill-bill render` validates source files without committing generated provider artifacts, and `scripts/validate_agent_configs` fails if generated provider artifacts are checked into the repo. Today this covers the `bill-kmp-code-review` KMP specialists, the `bill-kotlin-code-review` Kotlin specialists, and the `bill-feature-implement` workflow phases (pre-planning, planning, implementation, implementation-fix, completeness-audit, quality-check, pr-description). `bill-feature-verify` has no verify-specific native subagents; it delegates review through `bill-code-review` and keeps its verify audits inline. Parsing tolerance for `RESULT:` blocks across runtimes is documented inline in `skills/bill-feature-implement/content.md`.

## Start here

- [Getting Started](docs/getting-started.md): primary onboarding guide, install flow, skill surfaces, full `skill-bill` CLI coverage, and full MCP tool coverage
- [Getting Started for Teams](docs/getting-started-for-teams.md): rollout guidance, customization strategy, trust-vs-verify guidance, and adoption patterns
- [Skill Source And Generation Model](docs/skill-source-generation.md): `content.md` vs generated `SKILL.md`, support pointers, install staging, scaffolding, and native-agent generation
- [Review Telemetry](docs/review-telemetry.md): telemetry contract, learnings, local DB usage, and remote proxy stats
- [Roadmap](docs/ROADMAP.md): product direction, priorities, and strategic framing

## Reference skill catalog

These are the bundled reference skills. They are useful defaults, not a lock-in boundary. A team can remove, replace, or fork them and still use the framework for its own governed workflows and platform packs.

### Canonical Skills (13 skills)

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
| `/bill-grill-plan` | Stress-test a plan or design by walking the decision tree |
| `/bill-pr-description` | Generate a PR title, description, and QA steps |
| `/bill-quality-check` | Stable quality-check entry point that routes to the matching checker |
| `/bill-skill-remove` | Remove an existing skill or platform skill set and clean up installs |
| `/bill-unit-test-value-check` | Review unit tests for low-value or tautological coverage |

## Architecture snapshot

The main governed layers are:

- `skills/`: canonical user-facing skill sources. Each skill directory contains `content.md` and, only when needed, `native-agents/`.
- `platform-packs/`: manifest-driven platform review and quality-check depth
- `orchestration/`: routing, delegation, workflow, telemetry, and shell-content contracts
- `runtime-kotlin/`: packaged Kotlin CLI, MCP server, workflow state, telemetry, scaffolding, and install primitives
- `scripts/`: Kotlin-backed repo validation wrappers and retirement notes for obsolete migration helpers

`content.md` is the source-authored surface for governed skills. Generated `SKILL.md` wrappers and support pointer files such as `shell-ceremony.md`, `telemetry-contract.md`, stack-routing pointers, and add-on pointers are install/render output, not source files. Install staging materializes those generated files under `~/.skill-bill/installed-skills/<skill>-<hash>/` so agent runtimes still see a complete skill directory without generated artifacts being committed to `skills/`.

## Reference packs

The shipped reference inventory is intentionally narrow and deep:

- `kotlin`: baseline Kotlin review and quality-check behavior
- `kmp`: Kotlin baseline plus Android/KMP depth and governed add-ons

Routing, validation, and installation are manifest-driven, so the system is designed to accept any conforming pack rather than a hardcoded shortlist.

## Validation

Normal authoring check:

```bash
skill-bill validate
```

Full maintainer gate before shipping runtime, scaffold, contract, docs, or agent-config changes:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## License

[MIT](LICENSE)
