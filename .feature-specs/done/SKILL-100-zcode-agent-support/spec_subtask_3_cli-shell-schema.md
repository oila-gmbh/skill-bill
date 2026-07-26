# SKILL-100 · Subtask 3 — CLI commands, shell scripts, schema

Parent overview: [spec.md](./spec.md)

The final, integrating subtask. Exposes subtask 2's link/unlink/path
capabilities through the CLI, extends `install.sh`/`uninstall.sh` and the
install-plan schema, updates the shell-delegation test's literal command
lists, and closes with the full `./gradlew check` gate to confirm no drift
remains anywhere in the 30-item touch surface.

Branch: `feat/SKILL-100-zcode-agent-support` (same-branch model, one commit
for this subtask).

## Dependencies

- depends_on: `[1, 2]`
- dependency_reason: The new CLI unlink/link commands call
  `linkZcodeAgents`/`unlinkZcodeAgents` (subtask 2) and resolve zcode's
  install path (subtask 1). `install.sh`/`uninstall.sh` and
  `InstallerShellDelegationTest`'s literal command lists must reference the
  exact `unlink-zcode-agents` subcommand name this subtask's CLI
  registration introduces, so shell/test wiring is sequenced after the CLI
  commands within this same subtask.

## Scope (owns)

### 3a. CLI commands

- `InstallZcodeAgentsPathCommand`, `InstallLinkZcodeAgentsCommand`, and
  `InstallUnlinkZcodeAgentsCommand` in
  `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/install/InstallCliCommands.kt`,
  modeled on `InstallJunieAgentsPathCommand` (near lines 575-583),
  `InstallLinkJunieAgentsCommand` (near lines 752-778), and
  `InstallUnlinkJunieAgentsCommand` (near lines 780-805).
- `ScaffoldCliCommands.kt` imports the three new commands and registers them
  as subcommands of `InstallTopLevelCommands` alongside the existing junie
  entries (constructor params and `.subcommands(...)` registration).

### 3b. Shell scripts

- `install.sh`'s `SUPPORTED_AGENTS` bash array (near line 73) includes
  `zcode`, and both help strings (near lines 2543 and 2649) list zcode among
  supported agents.
- `uninstall.sh` gets a zcode removal block (removing `~/.zcode/skills`,
  modeled on the adjacent junie block) and the MCP-unregister agent loop
  (near line 638) includes zcode.

### 3c. Schema

- `orchestration/contracts/install-plan-schema.yaml`'s `agentId` enum (near
  lines 54-59) includes `zcode`.

### 3d. Shell-delegation test

- `InstallerShellDelegationTest.kt`'s three literal pipe-separated command
  lists (near lines 879, 957, 1013) each include `"install
  unlink-zcode-agents"`.

### 3e. Final integration gate

- Run `(cd runtime-kotlin && ./gradlew check)` to confirm the full
  enum/install-path/native-agent/MCP/runtime-builder/CLI/shell/schema/test
  surface is consistent with zero suppressions.

## Reusable patterns / pitfalls

- Model every new CLI command directly on its junie counterpart — junie has
  the simplest, most recently added command shape.
- Re-locate line numbers by symbol/pattern search before editing; they are
  approximate.
- No unnecessary comments.

## Acceptance Criteria

1. `InstallZcodeAgentsPathCommand`, `InstallLinkZcodeAgentsCommand`, and
   `InstallUnlinkZcodeAgentsCommand` exist in `InstallCliCommands.kt`,
   modeled on the junie equivalents.
2. `ScaffoldCliCommands.kt` imports and registers the three new commands as
   subcommands of `InstallTopLevelCommands` alongside the existing junie
   entries.
3. `install.sh`'s `SUPPORTED_AGENTS` bash array includes `zcode`, and both
   help strings list zcode among supported agents.
4. `uninstall.sh` has a zcode removal block (removing `~/.zcode/skills`) and
   the MCP-unregister agent loop includes zcode.
5. `orchestration/contracts/install-plan-schema.yaml`'s `agentId` enum
   includes `zcode`.
6. `InstallerShellDelegationTest.kt`'s three literal command lists each
   include `"install unlink-zcode-agents"`, and the test passes.
7. `(cd runtime-kotlin && ./gradlew check)` passes with zero suppressions.

## Non-goals

- Any further live verification of ZCode CLI behavior (already deferred at
  the parent-spec level).
- Modifying the opencode prose-only refusal machinery.
- Adding zcode marketing/README claims about e2e verification status.

## Validation strategy

```bash
cd runtime-kotlin && ./gradlew :runtime-cli:build
cd runtime-kotlin && ./gradlew :runtime-core:test --tests "*InstallerShellDelegationTest*"
bash -n install.sh && bash -n uninstall.sh
cd runtime-kotlin && ./gradlew check
```

## Handoff prompt

Run bill-feature-task on
`.feature-specs/SKILL-100-zcode-agent-support/spec_subtask_3_cli-shell-schema.md`.
