# Remove GLM as a first-class supported agent

- Issue key: SKILL-34
- Status: Complete
- Date: 2026-05-02
- Implementation: PR #97
- Deprecation posture: hard removal from supported agents; legacy uninstall cleanup only, with no first-class GLM install surface retained.
- Sources:
  - User reconsideration during SKILL-33 follow-up review on 2026-05-02: "stating GLM as supported agent for skill-bill doesn't really make sense — since it has to be used by things like Claude Code or OpenCode."
  - Git history: GLM was present in skill-bill since the initial commit (`2bff151`, Feb 2026) with a GLM commands directory under the user's home config as its install target. No documented rationale exists for which concrete tool that path corresponds to.
  - External research (2026-05-02): Three distinct things share the "GLM" name in the CLI ecosystem, none of which cleanly own that GLM commands directory:
    1. **GLM the model family** (Z.ai's GLM-4.5/4.6/4.7/5/5.1) — runs *inside* a host harness like Claude Code or Cline, the same way GPT-4 or Sonnet does. Not a harness.
    2. **GLM CLI launchers** like `xqsit94/glm` — thin wrappers that boot Claude Code preconfigured against the GLM model. Auth uses the GLM user config file, but skill commands land in the Claude Code commands directory because Claude Code is the actual harness underneath.
    3. **Z.AI's own CLI** (`@guizmo-ai/zai-cli`) — an independent terminal agent that stores config in the Z.AI user config directory, not the GLM commands directory.

## Background

skill-bill's supported-agent list today is `claude, copilot, glm, codex, opencode`. After investigating each, GLM is the only entry that does not correspond to a verified host harness with a public per-skill or per-subagent install convention. The GLM commands install path skill-bill ships against does not match any of the three real CLI tools that share the "GLM" name. It is most likely a speculative placeholder from the initial skill-bill commit that was never validated against a concrete tool.

Keeping GLM as a peer of `claude`, `codex`, and `opencode` causes three concrete problems:

1. **Misleading docs.** Users see GLM in the supported-agent list and assume skill-bill knows where their GLM-powered workflow lives. In practice, anyone running GLM-the-model inside Claude Code already gets full skill-bill coverage via the Claude Code commands directory; the GLM commands install does nothing useful for them.
2. **Implementation drag.** Every install-target change (recent example: SKILL-33's Codex native agents primitive) carries a parallel GLM branch, parallel test fixtures, and parallel doc rows that exist only because the GLM entry exists.
3. **Wrong shape if Z.AI's CLI is the real target.** Z.AI's own CLI uses its own Z.AI config directory, not the GLM commands directory. If someone wants real Z.AI CLI support, the right move is a new `zai` agent target — not bending the existing GLM target. That's a separate follow-up issue, not part of this work.

This issue removes GLM as a first-class supported agent, leaving Claude Code, Codex, OpenCode, and Copilot as the documented harnesses. It explicitly does NOT add Z.AI CLI support — that's a separate future issue.

## Acceptance criteria

1. `skill_bill/install.py` no longer treats `glm` as a member of `SUPPORTED_AGENTS`. The `glm` entry is removed from the agent registry, from `agent_paths`, from `detect_agents` (and from any helper such as `_glm_path` if one exists), and from `__all__` if exported.
2. `install.sh` no longer offers `glm` as a selectable agent. The `get_agent_path()` shell-case branch for `glm`, the `AGENT_NAMES` registration, the supported-agents echo, and any `glm`-specific code paths are removed.
3. `uninstall.sh` continues to clean up an existing GLM commands install path for users who installed under the previous version of skill-bill, and prints a one-line note explaining that GLM is no longer a first-class supported agent. The cleanup branch is removed in a follow-up after a deprecation window (out of scope here — see non-goals).
4. README.md no longer lists GLM in the supported-agents list. A short note ("Using GLM as a model? Install via the Claude Code path — GLM is a model, not a harness.") is added either in the supported-agents section or in a small "Models vs harnesses" subsection.
5. `docs/getting-started.md` removes the GLM install-path table row and adds the same explanatory note.
6. `docs/getting-started-for-teams.md` is updated to drop any GLM references.
7. `AGENTS.md` is updated to drop GLM from the supported-agents enumeration if it appears there.
8. Tests no longer create GLM home-directory fixtures or assert on GLM-specific install/uninstall behavior. `tests/test_install_script.py`, `tests/test_uninstall_script.py`, `tests/test_validate_agent_configs_e2e.py`, and any other test that mentions `glm` are updated. The uninstall test continues to assert that an existing GLM commands install (seeded by the test) is cleaned up correctly to satisfy AC #3.
9. `agent/history.md` is updated per `bill-boundary-history` rules with a single entry citing the user-reconsideration source and explaining the practical reason (GLM is a model, not a harness; speculative path that did not correspond to a real tool).
10. Validation gate passes: `python3 -m unittest discover -s tests`, `python3 scripts/validate_agent_configs.py`, and `npx --yes agnix --strict .` all return without regressions vs main.

## Non-goals

- Adding Z.AI CLI support as a new supported agent. That's a separate future issue and depends on confirming the zai CLI's per-skill install convention.
- Investigating GitHub Copilot's agent-mode subagent surface (separate parking-lot item from the SKILL-33 follow-up review).
- Removing the GLM commands cleanup branch from `uninstall.sh` immediately. Per AC #3, the cleanup branch stays for one deprecation window so existing installs can be removed cleanly. A follow-up issue removes the cleanup branch after a documented sunset date.
- Re-running the SKILL-33 install-primitive work or touching the Codex / OpenCode install paths.
- Changing the skill content or specialist contracts of any platform pack.

## Open questions

1. Is there any user who actually relies on the GLM commands install path today? If yes, the deprecation note in AC #3 should be louder. If no (most likely — the path was speculative), a quiet single-line note suffices. Defer to pre-planning to scan recent issues / Slack / repo for any explicit GLM usage; if nothing found, ship the quiet variant.
2. How long is the deprecation window for AC #3's cleanup branch? Proposal: until the next minor release after this ships. Pre-planning sets the concrete sunset date in the docs.

## Consolidated spec

### Code removal

`skill_bill/install.py`:
- Drop `"glm"` from the `SUPPORTED_AGENTS` tuple.
- Drop the `glm` entry from `agent_paths` (and the corresponding `_glm_path` helper if one exists).
- Drop the `glm` branch from `_agent_is_present` / `detect_agents`.
- Update the module docstring listing supported agents.
- Update `__all__` if any GLM-specific name was exported.

`install.sh`:
- Drop the `glm)` case from `get_agent_path`.
- Drop `glm` from any inline `SUPPORTED_AGENTS` array or echo.
- Drop the `glm` branch from any per-agent dispatch.

`uninstall.sh`:
- Keep one-shot cleanup logic for the GLM commands path so existing installs are removed cleanly. The cleanup runs unconditionally (idempotent — does nothing if that path does not exist) and prints a single info line: "GLM is no longer a first-class supported agent. If you used Skill Bill with GLM as a model inside Claude Code, your skills are unaffected — they live under the Claude Code commands directory."
- A follow-up issue removes this cleanup branch after the deprecation window.

### Documentation

- `README.md`: remove the GLM bullet from the supported-agents list. Add a one-line note: "Using GLM as a model in Claude Code? Skill Bill installs to the Claude Code commands directory — no separate target needed. GLM is a model, not a harness."
- `docs/getting-started.md`: remove the GLM install-path table row. Add the same note in prose form.
- `docs/getting-started-for-teams.md`: remove any `--agent glm` examples. If a "Runtime expectations" section exists (introduced in SKILL-33), update it to drop GLM.
- `AGENTS.md`: drop GLM from the supported-agents enumeration if it appears there.

### Tests

- `tests/test_install_script.py`: drop GLM home-directory setup from `prepare_agent_homes`. Drop any GLM-specific assertions.
- `tests/test_uninstall_script.py`: keep one assertion that an existing GLM commands directory is cleaned up correctly (this exercises AC #3's deprecation cleanup branch). All other GLM assertions are removed.
- `tests/test_validate_agent_configs_e2e.py`: drop GLM-specific cases.

### Boundary history

Append a single entry to `agent/history.md` explaining:
- GLM was a speculative supported-agent target since the initial commit (Feb 2026) with no documented concrete tool behind the GLM commands directory.
- Removed because GLM is a model family, not a harness; users running GLM models inside Claude Code already get full coverage via the Claude Code commands directory.
- Z.AI's actual CLI uses its own Z.AI config directory and is a candidate for a future `zai` supported-agent target — not part of this work.
- Cleanup branch in `uninstall.sh` retained one deprecation window so existing installs sunset cleanly.

### Validation

The standard validation gate from SKILL-33 applies: `python3 -m unittest discover -s tests && python3 scripts/validate_agent_configs.py && npx --yes agnix --strict .`. No new test infrastructure is required; this issue is a pure removal plus a doc note.
