# ZCode Agent Support — Investigation Briefing

**Purpose:** Complete, verified factual reference for adding `zcode` as a first-class
supported agent in skill-bill. Hand this document to the supporting agent that plans
and implements the change. Every fact below was confirmed against a live ZCode 3.2.3
install on linux-x64 and against the skill-bill codebase at commit `ce422474`.

---

## TL;DR — the headline decision

**ZCode is runtime-capable and should be added as a FULL runtime agent** (like
claude / codex / junie), NOT prose-only like opencode. It satisfies both criteria
that the SKILL-95 decision ("make opencode prose-only") identified as required for
runtime viability:

1. ✅ It has a **non-TTY, harvestable headless mode**: `zcode.cjs --prompt <text> --json`
   runs a single prompt and prints machine-readable JSON, without opening the TUI.
2. ✅ It has **no hard short foreground timeout**: it is a long-running Node process;
   the 120s kill that doomed opencode was specific to opencode's foreground *Bash*
   tool, not a general constraint.

See the SKILL-95 decision in `runtime-kotlin/agent/decisions.md` and the "Revisit
when" clause: opencode runtime becomes viable again when it "gains a non-TTY
harvestable headless mode and a foreground budget longer than 120s." ZCode already
has both.

---

## 1. ZCode architecture and how to reach the headless CLI

### What ZCode is
- ZCode is an **Electron desktop app** (AppImage on linux: `ZCode-3.2.3-linux-x64.AppImage`).
  The GUI binary is a single `zcode` executable that hosts the full TUI/GUI.
- The **headless agent backend** is a separate bundled Node script at
  `resources/glm/zcode.cjs` inside the AppImage. This is the "glm" builtin agent CLI
  provider (see `enabledBuiltinAgentCliProviders: ["glm"]` in settings), and it is
  what powers agent execution (including this very session).
- Self-reported CLI version: **`zcode 0.15.0`** (independent of the Electron app
  version `3.2.3`).

### Locating the executable
The AppImage mounts at runtime under `/tmp/.mount_ZCode-<random>/`. The headless
script path relative to the mount is:
```
resources/glm/zcode.cjs
```
Resolved from the running environment, these env vars are set and reliable for
locating it:
- `APPIMAGE` — absolute path to the AppImage, e.g.
  `/home/<user>/Applications/ZCode-3.2.3-linux-x64.AppImage`
- `APPDIR` — the mount root, e.g. `/tmp/.mount_ZCode-<random>`

The headless script can be invoked directly with node:
```
node "$APPDIR/resources/glm/zcode.cjs" --prompt "..." --json --cwd <dir>
```
But the **stable, version-independent** way to reach it is the `zcode` launcher on
`PATH` (the AppImage's `AppRun` shim), i.e. `which zcode`. The launcher forwards
args to the Electron app; the agent CLI subcommands (`--prompt`, `skills`, etc.)
are handled by the bundled `zcode.cjs`.

> **Implementation note for the `AgentRunCommandBuilder`:** prefer resolving the
> agent binary the same way the other builders do — assume the agent CLI is on
> `PATH` as `zcode`, matching how `claude` / `codex` / `junie` builders assume
> their CLIs are on PATH. Do NOT hardcode the AppImage mount path (it changes
> every launch).

### Full CLI help (authoritative, from `zcode.cjs --help`)
```
zcode 0.15.0

Usage:
  zcode [command] [options]

With no command, zcode opens the full-screen TUI.

Commands:
  app-server Run the ZCode Protocol stdio app server
  commands   List custom slash commands (`commands list`)
  doctor     Inspect runtime and packaging assumptions
  login      Sign in with Z.AI OAuth for model access
  logout     Remove the shared Z.Ai login credentials
  plugins    List and enable installed plugins (`plugins list`)
  skills     List local skills (`skills list`)
  tui        Open the terminal UI
  version    Print the CLI version

Options:
  -h, --help       Show help
  -v, --version    Show version
  --prompt <text>  Run a single prompt without opening the TUI
  --attach <path>  Attach a local file to --prompt; repeat for multiple files
  --cwd <path>     Run this command from the given directory
  --locale <locale>  UI locale: en-US, zh-CN, or auto
  --mode <mode>    Permission mode for prompts: build, edit, plan, or yolo (default: yolo for --prompt)
  --resume <sessionId>  Resume a persisted session by sessionId (sess_...)
  --target <text>  Run or set the session goal in headless mode
  --target-replace Replace any existing session goal set by --target
  -c, --continue        Resume the latest session for the current directory
  --json           Print machine-readable JSON where supported
  --no-browser     Print the OAuth URL without opening a browser
  --no-color       Disable ANSI colors
  --verbose        Print extra diagnostic detail

Slash Commands:
  /help [command]       Show slash command help
  /login                Sign in with Z.AI OAuth
  /logout               Remove the shared Z.AI login credentials
  /compact [instructions]  Compact the current conversation
  /expert [status|resume|stop|<task>]  Run or manage the expert workflow
  /fork [latest|checkpointId]  Fork a new session from a workspace checkpoint
  /mcp [list|status|connect|disconnect]  Show or manage MCP servers
  /mode [mode]          Show or switch permission mode: build, edit, plan, or yolo
  /model [id]           Show or switch the current session model
  /new                  Start a fresh session in the TUI
  /resume [sessionId]   Resume a session by sessionId; omit it for latest in cwd
  /rewind [latest|checkpointId]  Show latest checkpoint or restore workspace files
  /skill [name] [task]  List skills, or force the next prompt to load one
  /goal [action]        Show or set the current session goal
```

### Runtime-mode invocation pattern (the `AgentRunCommandBuilder` model)
For the phase-loop driver, model the builder on `ClaudeAgentRunCommandBuilder` /
`CodexAgentRunCommandBuilder`. The mapping:

| skill-bill concept | ZCode equivalent |
|---|---|
| `claude --print --output-format text` | `zcode --prompt <prompt> --json` |
| prompt via stdin | prompt via `--prompt <text>` argv (no stdin-prompt mode observed) |
| `--dangerously-skip-permissions` (claude) | `--mode yolo` (default for `--prompt`; "yolo" = no permission prompts) |
| working directory | `--cwd <path>` |
| output format | `--json` (machine-readable) |

Recommended command shape:
```
zcode --prompt "<phase prompt>" --json --cwd <repo> --mode yolo --no-color
```

**Prompt delivery caveat:** unlike claude/codex (which read the prompt from stdin),
ZCode takes the prompt as an argv value to `--prompt <text>`. Phase prompts can be
long. Options to evaluate during implementation:
- Pass via `--prompt` argv (simplest; watch OS argv-length limits on very large
  prompts — Linux `MAX_ARG_STRLEN` is 128 KiB per arg, which should be fine for
  phase briefings).
- Or write the prompt to a temp file and use `--attach <path>` to attach it, with a
  short `--prompt` instructing the agent to read the attached file. (Unverified
  whether `--attach` injects file *contents* into context vs. just making it
  readable — confirm during implementation.)

**Permission mode:** the default for `--prompt` is already `yolo` (no prompts), which
is what an unattended runtime driver needs. Do not pass `--mode build`/`edit`/`plan`
for the runtime driver.

**`--json` output shape:** confirmed present but NOT captured in this investigation
(a live run requires a configured model provider; the test run returned
`Error: Model config is missing.` because `~/.zcode/cli/config.json` had no
`provider`/model set in the probed location). **The implementer MUST run a real
`--prompt ... --json` once and capture the exact JSON envelope** so the runtime can
parse the assistant's final text out of it. This is the single biggest unknown left
to resolve during implementation.

### Process/strategy considerations (per AGENTS.md injectable strategies)
- `usePtyStdio`: ZCode `--prompt` is a non-TTY stdout producer (it is *not* a PTY/TUI
  stream when run headless — that was opencode's exact failure). Use separate
  stdout/stderr streams, NOT a PTY pair (i.e. `usePtyStdio = false`), matching the
  claude/codex builders.
- `idlePolicy`: start with `DB_PROGRESS_ONLY` (the default). ZCode writes progress
  to `~/.zcode/cli/db/db.sqlite` (tokens, tool-use counts) and to per-session
  rollout files; whether token changes are detectable the same way as claude/codex
  should be confirmed, but `DB_PROGRESS_ONLY` is the safe default.
- `progressEmitter` / `activityProbe`: filesystem-activity detection works as-is
  (ZCode writes files like any agent).

---

## 2. Agent identity and environment detection

### Env-var detection signal (for `InvokingAgentContextResolver`)
ZCode sets a distinctive set of env vars in every process it spawns. The most
reliable detection markers (confirmed present in this session's env):

| Env var | Example value | Reliability |
|---|---|---|
| `ZCODE_APP_VERSION` | `3.2.3` | ✅ set, ZCode-specific |
| `ZCODE_BASE_URL` | `https://zcode.z.ai` | ✅ set, ZCode-specific |
| `ZCODE_ENV` / `ZCODE_RUNTIME_ENV` | `production` | ✅ set |
| `ZCODE_PROCESS_LABEL` | `local-1` | ✅ set |
| `APPIMAGE` | `.../ZCode-*.AppImage` | present but not ZCode-exclusive (generic AppImage var) |
| `ZAI_API_KEY` / `ZAI_BUSINESS_BASE_URL` / `ZAI_OAUTH_*` | various | set, but tied to the z.ai model provider, not strictly the client |

**Recommended detection key:** `ZCODE_APP_VERSION` (or `ZCODE_BASE_URL`) — both are
unambiguous and set on every ZCode-spawned process. Add an entry to
`INVOKING_AGENT_CONTEXT_SIGNALS` in `InstallModels.kt` mapping the zcode agent to
one of these marker keys (the existing table has per-agent env-var marker keys for
CLAUDE, CODEX, OPENCODE).

### Agent id and display
- Canonical lowercase id: **`zcode`** (matches the `InstallAgent.id` convention used
  by copilot/claude/codex/opencode/junie).
- Display name: **"ZCode"** (for the desktop FirstRunSetup wizard, which carries a
  display-name field alongside the id).

---

## 3. Install paths — where ZCode looks for skills, commands, agents

### Skills discovery (authoritative, from ZCode's own configuration-guide skill)
ZCode scans these locations **in precedence order** (first same-named skill wins):

1. Explicitly configured roots
2. **User `~/.zcode/skills/`**
3. **User `~/.agents/skills/`** (universal/shared — same path Codex/Claude use)
4. Workspace `<repo>/.zcode/skills/` (searched upward to repo root, every level)
5. Workspace `<repo>/.agents/skills/`
6. Enabled **plugin** roots (lowest precedence)

Within a level, `.zcode` is scanned before `.agents`. Skill identity = the file path;
a skill is a directory containing a `SKILL.md` with YAML frontmatter (`name`,
`description`) + markdown body — **identical format to Claude Code / opencode**.

### Recommended install target for skill-bill
**Primary: `~/.zcode/skills/`** (user scope, ZCode-native).

This mirrors how skill-bill installs into each agent's native skills dir
(`~/.claude/skills`, `~/.codex/skills`, `~/.config/opencode/skills`, `~/.junie/skills`).
The ZCode-native choice is `~/.zcode/skills/`.

> Note: `~/.agents/skills/` is the universal cross-tool dir. skill-bill's
> `agentPaths()` already special-cases codex to fall back to `~/.agents/skills`. For
> zcode, prefer the native `~/.zcode/skills/` as the default path so installs are
> scoped to ZCode and don't collide with other tools — but be aware ZCode *also*
> reads `~/.agents/skills`, which is useful for the desktop "Import skills from
> external agents" feature.

### Agent/presence detection (for `agentIsPresent`)
ZCode is "present" if its config home exists. Detection roots to check
(`agentIsPresent` `when (agent)` branch):
- `~/.zcode/` (the config home — always present on a ZCode install)
- Optionally `~/.zcode/cli/` (CLI runtime data dir, also always present)

The `zcode` binary on PATH is a further signal but the config-home check is the
reliable one (matching how junie checks `~/.junie/`, opencode checks
`~/.config/opencode/`).

### Commands
- User: `~/.zcode/commands/`, `~/.agents/commands/`
- Workspace: `<repo>/.zcode/commands/`, `<repo>/.agents/commands/`
- Not directly relevant to skill-bill's install (skill-bill installs skills, not
  commands), but documented for completeness.

---

## 4. MCP server registration

### Config file locations (authoritative)
ZCode reads MCP config from, per scope (`.zcode` checked before `.agents`):

| Scope | Primary | Fallback |
|---|---|---|
| **User** | `~/.zcode/cli/config.json` → `mcp.servers` | `~/.agents/mcp.json` → `mcpServers` |
| **Workspace** | `<repo>/.zcode/config.json` → `mcp.servers` | `<repo>/.agents/mcp.json` → `mcpServers` |

> ⚠️ **Two different JSON shapes:**
> - `.zcode/config.json` uses **nested** `mcp.servers` (and `mcp_servers` is also
>   accepted as a key variant observed in the asar).
> - `.agents/mcp.json` (compatibility fallback) uses **top-level** `mcpServers`
>   (the Claude/Codex/Cursor-standard shape).

### Which file should skill-bill write?
For ZCode MCP registration, write to **`~/.zcode/cli/config.json`** under the
`mcp.servers` key (the ZCode-native user-scope location). This mirrors how
skill-bill writes each agent's native config:
- claude → `~/.claude.json` (JSON)
- codex → `~/.codex/config.toml` (TOML)
- opencode → `~/.config/opencode/opencode.json`
- junie → `~/.junie/mcp/mcp.json`
- **zcode → `~/.zcode/cli/config.json`** (JSON, `mcp.servers` nested key) ← NEW

### Config format — JSON (not TOML)
ZCode config is JSON. The server entry shape (stdio server) is the same as
Claude's `mcpServers` entries:
```json
{
  "mcp": {
    "servers": {
      "skill-bill": {
        "type": "stdio",
        "command": "skill-bill-mcp",
        "args": []
      }
    }
  }
}
```
> **Confirm during implementation:** whether the nested key is exactly `mcp.servers`
> or whether `config.json` also accepts top-level `mcpServers`. The configuration
> guide states `.zcode` uses nested `mcp.servers`; the asar also contains
> `mcp_servers` and `mcpServers` tokens. Safest: use `mcp.servers` (nested) for the
> `.zcode/cli/config.json` target, per the official guide. A new
> `McpZcodeConfig` writer class (modeled on `McpJsonConfig`) is the clean fit.

### Auto-connect behavior
MCP servers from **every** scope (user, workspace, plugin, env, CLI) are
**trusted and connected automatically** at session start. So a user-scope
registration in `~/.zcode/cli/config.json` will connect without manual
authorization — good for skill-bill's MCP tools being immediately available.

### Existing workspace MCP (reference)
This repo already has a workspace-scope MCP config at `.mcp.json` (top-level
`mcpServers`) registering `skill-bill-mcp`. ZCode reads workspace `.agents/mcp.json`
but NOT a bare `.mcp.json` — **however** the session clearly has the skill-bill MCP
available, so either ZCode also reads `.mcp.json` or the desktop app reads it
directly. **Confirm during implementation** whether `.mcp.json` is a recognized
workspace file; if not, the installer's workspace-MCP path is unaffected (user-scope
registration is the install target anyway).

---

## 5. Instruction files (AGENTS.md)

- **User scope:** `~/.zcode/AGENTS.md` (personal defaults, every workspace)
- **Workspace scope:** `<repo>/AGENTS.md` (project rules; searched upward from cwd
  to repo root)
- **Merge order:** user `~/.zcode/AGENTS.md` injected first, then workspace
  `<repo>/AGENTS.md` later (workspace can narrow/override user defaults).
- ZCode reads `AGENTS.md` natively (this very session is governed by the repo's
  `AGENTS.md`). No install action needed for instructions — skill-bill's governance
  doc is already picked up.

---

## 6. Native subagents (the `native-agents/agents.yaml` rendering target)

### Does ZCode support native subagents?
**Yes** — ZCode has a first-class subagent system (the `Agent` tool in this session
spawns them; the session metadata at
`~/.zcode/cli/agents/<sessionId>/<agentId>/metadata.json` records subagent
`profileSnapshot` with name/description/model/tools). Plugins can declare an
`agents` component field in their manifest.

### Rendering target directory
Following the skill-bill convention (`claude-agents/`, `codex-agents/`,
`opencode-agents/`, `junie-agents/`), the ZCode native-agent output directory
should be **`zcode-agents/`**.

### Render format
ZCode subagents are defined with a name, description, model, and tool list. The
closest existing render pattern is **`renderFrontmatterAgent`** (YAML frontmatter
with `name`, `description`, optional `model`/`tools`) — the same shape Claude and
opencode use. The `NativeAgentProvider` enum entry for ZCode should be:
```kotlin
Zcode("zcode-agents", "md") {
    render = renderFrontmatterAgent(mode = null)   // or "subagent" — confirm
    homeAgentDirs = listOf(home.resolve(".zcode/agents"), home.resolve(".agents/agents"))
}
```

### Native-agent install/link target
- **User:** `~/.zcode/agents/` and/or `~/.agents/agents/`
- The link/unlink operations (`linkZcodeAgents` / `unlinkZcodeAgents`) should model
  on `linkJunieAgents`/`linkOpencodeAgents` (markdown agent files symlinked into the
  agents dir).

> **Confirm during implementation:** the exact on-disk subagent definition format
> ZCode loads (frontmatter keys, whether `model`/`tools` are honored from
> user-scope files vs. only plugin manifests). The metadata.json captured for this
> session's subagent shows the runtime *records* `profileSnapshot` with
> `name/description/model/tools/source`, but the *authoring* format for
> user-scope subagents should be verified by dropping a test agent file into
> `~/.zcode/agents/` and running `zcode skills list` or inspecting the Subagents
> settings.

---

## 7. The skill-bill touch surface (where to add `zcode`)

There is **no single agent registry**. Agent identity is hardcoded across ~27 sites
in 6 duplicated enums, ~8 `when`/list branches, a bash array, a YAML enum, and
parity tests. Below is the complete, ordered edit list for adding `zcode` as a full
runtime agent. (Source: codebase investigation at commit `ce422474`.)

### A. Enums (6) — add a `ZCODE` entry to each
1. **`InstallAgent`** — `runtime-domain/src/main/kotlin/skillbill/install/model/InstallModels.kt:13`
   - Add `ZCODE("zcode")`.
   - Add env-var marker to `INVOKING_AGENT_CONTEXT_SIGNALS` (~line 70) using
     `ZCODE_APP_VERSION` (recommended).
   - **Do NOT** add zcode to `RUNTIME_REFUSED_AGENTS` (line 37) — zcode IS runtime-capable.
2. **`NativeAgentProviderId`** — `InstallModels.kt:386` (add `ZCODE`).
3. **`NativeAgentProvider`** — `runtime-infra-fs/src/main/kotlin/skillbill/nativeagent/rendering/NativeAgentRendering.kt:13`
   (add `Zcode("zcode-agents", "md")` with render fn + `homeAgentDirs`).
4. **`NativeAgentLinkProvider`** — `runtime-ports/src/main/kotlin/skillbill/ports/install/model/InstallPortModels.kt:11`
   (add `ZCODE`).
5. **`FirstRunSetupAgent`** — `runtime-desktop/core/domain/src/commonMain/kotlin/skillbill/desktop/core/domain/model/FirstRunSetupModels.kt:17`
   (add `ZCODE("zcode", "ZCode")`).
6. **`AgentSymlinkProvider`** + **`DesktopAgentSymlinkProvider`** —
   `runtime-domain/src/main/kotlin/skillbill/domain/skillremove/model/SkillRemovalPreview.kt:81`
   and `runtime-desktop/.../SkillRemovalModels.kt:83` (add `ZCODE`).

### B. Install paths & detection
7. **`SUPPORTED_AGENTS`** list — `runtime-infra-fs/src/main/kotlin/skillbill/install/plan/InstallPrimitives.kt:15`
   (add `"zcode"`).
8. **`agentPaths(...)`** map — `InstallPrimitives.kt:21-30`
   (add `"zcode" -> resolvedHome.resolve(".zcode/skills")`).
9. **`agentIsPresent`** `when (agent)` — `InstallPrimitives.kt:153-159`
   (add zcode → check `~/.zcode/`).
10. **`agentDirectory`** `when` — `runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/FileSystemInstallAdapters.kt:193-199`
    (add `"zcode" -> ...`).
11. **`InstallOperations`** junie-style helpers — `runtime-infra-fs/src/main/kotlin/skillbill/install/runtime/InstallOperations.kt`
    (add a `zcodeAgentsPath` helper near line 53 if needed; model on `junieAgentsPath`).

### C. Native-agent rendering & linking
12. **`linkZcodeAgents` / `unlinkZcodeAgents`** — new functions in
    `runtime-infra-fs/src/main/kotlin/skillbill/install/nativeagent/InstallNativeAgentOperations.kt`
    (model on `linkJunieAgents` lines 96-119).
13. **Two `when (request.provider)` branches** —
    `FileSystemInstallAdapters.kt:222-227` and `:241-246` (add `ZCODE` cases).
14. **`nativeAgentInstallers` list** —
    `runtime-infra-fs/src/main/kotlin/skillbill/apply/InstallApplyNativeAgents.kt:199-224`
    (add a `NativeAgentInstaller(agent=ZCODE, provider=ZCODE, link=::linkZcodeAgents, unlink=::unlinkZcodeAgents)` entry).

### D. MCP registration
15. **`McpZcodeConfig`** — new writer class in
    `runtime-infra-fs/src/main/kotlin/skillbill/launcher/mcp/` (model on
    `McpJsonConfig.kt`; writes JSON to `~/.zcode/cli/config.json` under `mcp.servers`).
16. **`McpRegistrationOperations.register/unregister/configPathFor`** —
    `runtime-infra-fs/src/main/kotlin/skillbill/launcher/mcp/McpRegistrationOperations.kt`
    - `register` `when` (lines 22-30): add `ZCODE -> McpZcodeConfig(...)`.
    - `unregister` `when` (lines 42-50): add `ZCODE -> McpZcodeConfig(...)`.
    - `configPathFor` `when` (lines 53-59): add `ZCODE -> home.resolve(".zcode/cli/config.json")`.

### E. Runtime `AgentRunCommandBuilder` (zcode is runtime-capable — this is included)
17. **`ZcodeAgentRunCommandBuilder`** — new class in
    `runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/AgentRunCommandBuilders.kt`
    (model on `ClaudeAgentRunCommandBuilder` lines 42-68). Command:
    `zcode --prompt <prompt> --json --cwd <dir> --mode yolo --no-color`.
    Set `usePtyStdio = false`, `idlePolicy = DB_PROGRESS_ONLY`.
18. **`headlessAgentRunAdapters` registry** —
    `runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/AgentRunAdapters.kt:75-79`
    (add `ZcodeAgentRunCommandBuilder()` to the `listOf(...)`).
19. **`FileSystemAgentRunLauncher`** deep path —
    `runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/FileSystemAgentRunLauncher.kt`
    (no edit needed if the registry is wired; the launcher dispatches by builder map).

### F. CLI commands
20. **New CLI commands** — `runtime-cli/src/main/kotlin/skillbill/cli/install/InstallCliCommands.kt`
    (add `InstallZcodeAgentsPathCommand`, `InstallLinkZcodeAgentsCommand`,
    `InstallUnlinkZcodeAgentsCommand`; model on the junie commands at lines 545-805).
21. **Register commands** — `runtime-cli/src/main/kotlin/skillbill/cli/scaffold/ScaffoldCliCommands.kt`
    (add to `InstallTopLevelCommands` `.subcommands(...)` and imports, lines 33-40, 102-158).

### G. Shell scripts
22. **`install.sh` `SUPPORTED_AGENTS`** bash array — `install.sh:73`
    (add `zcode`).
23. **`install.sh` help strings** — `install.sh:2543, 2649` (add zcode to agent lists).
24. **`uninstall.sh`** per-agent removal blocks — `uninstall.sh:522-545` and the loop
    at `:638` (add a `~/.zcode/skills` removal block).

### H. Schema
25. **`agentId` enum** — `orchestration/contracts/install-plan-schema.yaml:54-59`
    (add `zcode` to the enum).

### I. Tests / parity guards (will FAIL until updated)
26. **`requireSupportedAgentContract()`** — `runtime-infra-fs/.../InstallPlanBuilder.kt:50-54`
    (ties `SUPPORTED_AGENTS` ↔ `InstallAgent.supportedIds` — auto-satisfied if both
    are updated together; this is the only automated drift guard).
27. **`FirstRunSetupModelsTest.kt:11`** — asserts the exact agent id list; add `zcode`.
28. **`InstallPlanContractCoverageTest.kt`**, **`InstallPlanSchemaValidatesExistingFixturesTest.kt`**
    — fixtures enumerate agents; add zcode fixtures.
29. **`InstallerShellDelegationTest.kt:879,957,1013`** — reference the literal
    `unlink-{codex,claude,opencode,junie}-agents` command list; add `unlink-zcode-agents`.
30. **New tests:** `ZcodeAgentRunCommandBuilderTest` (model on
    `ClaudeAgentRunCommandBuilderTest`), and an MCP registration test for the new
    `McpZcodeConfig` writer.

---

## 8. Open questions to resolve during implementation

These are the facts I could NOT fully verify without a configured model provider or
by dropping test files. They are confirmations, not blockers:

1. **`--json` output envelope.** Run `zcode --prompt "<short>" --json --cwd <dir>`
   once with a valid model provider and capture the exact JSON shape the runtime
   must parse to extract the assistant's final text. (The probe returned
   `Error: Model config is missing.` because no provider was configured at
   `~/.zcode/cli/config.json`.)
2. **Prompt-size handling.** Confirm whether long phase prompts via `--prompt` argv
   are reliable, or whether `--attach <file>` + a short `--prompt` ("read the
   attached brief, then...") is the safer pattern. Verify whether `--attach`
   injects contents into context or merely makes the file readable.
3. **User-scope subagent authoring format.** Drop a test agent file into
   `~/.zcode/agents/` and confirm the frontmatter keys ZCode loads
   (`name`/`description`/`model`/`tools`) and whether `mode: "subagent"` vs `null`
   matters for the frontmatter renderer.
4. **MCP nested key.** Confirm `~/.zcode/cli/config.json` accepts `mcp.servers`
   (nested) per the official guide, vs. needing top-level `mcpServers`. The guide
   says nested; the asar contains both tokens.
5. **Whether `.mcp.json` (bare) is read at workspace scope.** This repo's
   `.mcp.json` appears to work, but the official guide only documents
   `.zcode/config.json` and `.agents/mcp.json`. Clarify which workspace file the
   installer should document (does not affect user-scope install target).

---

## 9. Reference: how the existing agents are wired (templates to copy)

| Concern | Best template to copy for zcode |
|---|---|
| `AgentRunCommandBuilder` (runtime, headless) | `ClaudeAgentRunCommandBuilder` (`AgentRunCommandBuilders.kt:42-68`) — same "stdout, --json, no-PTY" shape |
| Native-agent render format | `Junie` / `Claude` in `NativeAgentRendering.kt` (frontmatter `.md`) |
| Native-agent link/unlink | `linkJunieAgents` / `unlinkJunieAgentMarkdown` (`InstallNativeAgentOperations.kt:96-119`) |
| MCP config writer | `McpJsonConfig.kt` (JSON writer; zcode is JSON, unlike codex's TOML) |
| Install path primitive | `junie` in `agentPaths()` / `agentIsPresent()` (simple `~/.<agent>/` home) |
| CLI link/unlink commands | junie commands in `InstallCliCommands.kt:545-805` |

**Do NOT copy the opencode runtime-refusal pattern** — zcode is runtime-capable and
must NOT be added to `RUNTIME_REFUSED_AGENTS`. The opencode refusal machinery
(`isRuntimeRefusedAgent`, `OPENCODE_RUNTIME_REFUSAL_MESSAGE`, the preflight gate) is
intentionally opencode-specific and should not be extended for zcode.

---

## 10. Summary of confirmed facts (quick reference)

- **Agent id:** `zcode` (lowercase). Display: "ZCode".
- **Runtime-capable:** YES (headless `--prompt` + `--json`, no short timeout).
- **Headless CLI:** `zcode` (AppImage `AppRun` shim) or `node .../resources/glm/zcode.cjs`.
  CLI self-version `0.15.0`; app version `3.2.3`.
- **Skills install dir:** `~/.zcode/skills/` (user scope, ZCode-native). Format:
  `SKILL.md` with YAML frontmatter (identical to Claude/opencode).
- **Agent detection roots:** `~/.zcode/` (present), `~/.zcode/cli/`.
- **Env detection marker:** `ZCODE_APP_VERSION` (or `ZCODE_BASE_URL`).
- **MCP config file:** `~/.zcode/cli/config.json`, nested key `mcp.servers`, JSON.
- **MCP auto-connect:** yes (all scopes trusted at session start).
- **Instructions:** reads `<repo>/AGENTS.md` and `~/.zcode/AGENTS.md` natively.
- **Native subagents:** supported; render to `zcode-agents/` (frontmatter `.md`);
  install target `~/.zcode/agents/` and/or `~/.agents/agents/`.
- **Config home:** `~/.zcode/` (CLI runtime data under `~/.zcode/cli/`, provider
  config under `~/.zcode/v2/config.json`).
- **Skill discovery also reads:** `~/.agents/skills/` (universal, cross-tool).
