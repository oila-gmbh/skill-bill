# SKILL-81 · Subtask 5 — Agent install verification + tiered support claims

**Status: completed (commit `04a6b31c`).** CI wiring is a noted fast-follow, not part of
this subtask — see Non-goals.

## Scope

The README's agent support claims are launch-critical and were previously binary
("supported" vs not), which on a public launch risks an overclaim: only Claude Code and
Codex are verified end-to-end; Copilot, OpenCode, and Junie could not be tested by the
maintainer. This subtask makes the claim evidence-backed and tiered.

The skill *content* is agent-agnostic; what was unverified for the untested agents is the
per-agent install/launch plumbing (native config format, directory conventions, MCP
registration). An automated install smoke test verifies exactly that layer without needing
the agent runtimes themselves.

In scope (delivered):
- A repeatable, isolated smoke test (`scripts/agent_install_smoke_test.sh`) that, per
  agent, runs `skill-bill install apply` into a throwaway `--home`, reusing the already
  installed runtime (`--runtime-install-root`, `--runtime-mcp-bin`) so it neither
  downloads nor builds, and never touches the caller's real agent directories. It asserts
  via the `--format json` output and the filesystem: apply exit 0, no top-level failures,
  the agent applied, skills installed (non-empty), native subagents `status: linked` and
  present on disk in the correct format (`.toml` for codex, `.md` with frontmatter for
  the rest), and MCP registration without a `failed` outcome plus a valid on-disk config.
- README re-tiering into three honest tiers: **verified end-to-end** (Claude, Codex),
  **install-verified (automated), runtime not yet confirmed** (Copilot, OpenCode, Junie,
  backed by the smoke test and a "please report runtime feedback" CTA), keeping the
  install-vs-runtime distinction explicit.

## Acceptance Criteria

1. `scripts/agent_install_smoke_test.sh` exists, is executable, runs each agent's
   `install apply` into an isolated `--home`, reuses the installed runtime (no download/
   build), and never writes to the caller's real agent directories. ✅
2. Per agent it asserts: apply exit 0, empty `failures`, agent applied, skills installed,
   native subagents linked and present in the correct format, and MCP registration with no
   `failed` outcome and a valid on-disk config; it exits non-zero if any agent fails. ✅
3. All five agents pass the smoke test on a clean run. ✅
4. The README agent support section presents the three tiers and cites the smoke test as
   the backing evidence for the middle tier, without claiming runtime verification for
   Copilot/OpenCode/Junie. ✅
5. The honest "install-verified ≠ runtime-verified" distinction is preserved in copy. ✅

## Non-goals

- **CI wiring** — running the smoke test in `validate-agent-configs.yml` (or a new
  workflow) so per-agent install regressions are caught automatically. Valuable and
  recommended as a fast-follow, but it requires the runner to build the runtime
  (`--from-source`) or stage one first, so it is tracked separately, not in this subtask.
- Runtime/end-to-end verification of Copilot/OpenCode/Junie (the maintainer has no access;
  this is exactly what the middle tier declines to claim).
- Changing install behavior or agent path resolution — this subtask only verifies and
  documents.

## Dependency notes

Independent of subtasks 1, 3, 4. Touches the same README agent-support region first
introduced in subtask 2; landed after it, so no conflict.

## Validation strategy

- `scripts/agent_install_smoke_test.sh` → all five agents `RESULT: PASS`, overall exit 0.
- Re-run after any change to `install.sh`, the runtime install/apply code, or native-agent
  rendering — a regression there should turn an agent red.
- README renders with three distinct tiers and a working link to the script.

## Next path

```bash
skill-bill goal SKILL-81
```
