# SKILL-161 — Session-adaptive execution matrix with provider profiles

## Intended Outcome

The execution matrix adapts to the Claude session profile that launched the runtime, and a tier
directive can pin a *provider*, not just a model name. Two additions to the machine-wide
`config.json`:

- `provider_profiles`: named environment presets (`base_url`, `auth_token_env`, `config_dir`,
  `unset`) a tier directive references by name.
- `execution_matrix.sessions`: per-session matrix overlays selected by the
  `SKILL_BILL_SESSION_PROFILE` environment variable the operator's shell launcher exports.

With this config:

```json
{
  "provider_profiles": {
    "anthropic-default": {
      "config_dir": "~/.claude",
      "unset": ["ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN",
                "ANTHROPIC_MODEL", "ANTHROPIC_SMALL_FAST_MODEL"]
    }
  },
  "execution_matrix": {
    "sessions": {
      "deep": {
        "agents": {
          "claude": {
            "reasoning": { "model": "claude-opus-5", "profile": "anthropic-default" },
            "implementation": { "model": "deepseek-ai/DeepSeek-V4-Flash-0731" }
          }
        }
      }
    }
  }
}
```

and `SKILL_BILL_SESSION_PROFILE=deep` exported by the `cc deep` shell profile, a runtime
launched from that DeepInfra session runs reasoning phases on `claude-opus-5` against the real
Anthropic backend (default account) while implementation phases inherit the session's DeepSeek
environment untouched. Sessions exporting no selector, or a name with no overlay (`cc`,
`cc work`, `cc glm`), use the default matrix fields and keep their own account and provider
end to end. The interactive session itself stays on whatever provider launched it; only
spawned phase children are re-pointed, and only under a matching overlay.

The operator has multiple Claude accounts as separate config dirs (`~/.claude`,
`~/.claude-work`, `~/.claude-glm`, `~/.claude-deep`), launched through a fish `cc` wrapper.
Each account can carry its own overlay under `sessions` later (different models per account)
without any further contract change. A profile's `config_dir` is optional; omitting it
inherits the session's `CLAUDE_CONFIG_DIR`.

## Motivation (observed, not hypothetical)

The operator launches Claude Code through per-account shell profiles; the `deep` profile exports
`CLAUDE_CONFIG_DIR=~/.claude-deep`, `ANTHROPIC_BASE_URL=https://api.deepinfra.com/anthropic`,
`ANTHROPIC_AUTH_TOKEN=$DEEPINFRA_TOKEN`, and `ANTHROPIC_MODEL=deepseek-ai/DeepSeek-V4-Flash-0731`.
Phase children inherit that environment wholesale, so the matrix today can steer the model *name*
but every request still lands on DeepInfra. Setting the reasoning tier to `claude-opus-5` sends
`--model claude-opus-5` to a backend that cannot serve it. Mixed-provider tiers — expensive
reasoning on Anthropic, cheap implementation on DeepSeek — are unreachable by configuration.

Because the machine-wide matrix is static, a naive provider override would also be wrong for
the operator's other accounts: hard-coding `config_dir: ~/.claude` into a reasoning directive
would silently re-point `cc work` sessions' reasoning phases at the personal default account.
The matrix must therefore *select by session*, explicitly, not apply one shape everywhere. An
explicit selector variable was chosen over sniffing `ANTHROPIC_BASE_URL`: it also covers
providers configured inside a config dir's settings rather than exported env, and it lets each
account carry distinct model choices.

## Current State (verified in this repository)

- `ExecutionMatrixModels.kt` (`runtime-domain/.../config/model/`) parses `execution_matrix`
  with strict field allowlists; `EXECUTION_MATRIX_FIELDS = {phase_tiers, agents}` and
  `DIRECTIVE_FIELDS = {model, effort}`; any other field loud-fails via
  `ExecutionMatrixParse.Invalid`. The `model` string is free-form.
- `ConfigResolutionService.kt:43-44` (`runtime-application/.../config/`) reads
  `execution_matrix` from the machine-wide config payload through `parseExecutionMatrix`.
- `FeatureTaskRuntimeModelResolver` resolves per-phase CLI directives first, then
  `matrix.directiveFor(agentId, phaseId)`.
- `refuseUnsupportedModelDirectives` (`runtime-cli/.../core/ModelDirectiveRefusal.kt`)
  preflights directives against `MODEL_DIRECTIVE_CAPABLE_AGENTS = {claude, codex}` before any
  phase launches.
- `AgentRunCommandBuilders.kt:127-134` forwards the directive as `claude --model X`
  (`--effort` likewise); the spawn uses `inheritEnvironment = true` on the normal path, so the
  child receives the session's full provider environment.
- `JvmAgentRunProcessRunner.kt:1035-1044`: when `inheritEnvironment` is true the builder keeps
  the inherited map and `putAll`s `request.environment`. **There is no mechanism to remove an
  inherited variable.** Overriding a provider therefore requires a new env-removal capability
  on `SkillRunRequest`, honored at the process-builder seam.
- `SkillRunRequest` lives in `runtime-ports/.../agentrun/model/AgentRunLauncherModels.kt`;
  `environment`, `inheritEnvironment`, and `environmentPassthroughKeys` already exist there.
- The execution matrix is a Kotlin-parsed region of `config.json`, not a YAML contract under
  `orchestration/contracts/`; the new keys follow the same Kotlin parse pattern
  (`ExecutionMatrixParse`-style sealed valid/invalid result), not the YAML schema recipe.

## Design

1. **`provider_profiles` parsing** (`runtime-domain`): top-level config key mapping profile
   name → `{base_url?, auth_token_env?, config_dir?, unset?}`. At least one field required per
   profile; unknown fields, blank names, blank values, and non-list `unset` loud-fail with the
   same keyPath/value/reason shape as `ExecutionMatrixParse.Invalid`. Raw secrets never appear
   in config: `auth_token_env` names an environment variable, it does not carry a token.
   Profiles are unconditional presets; adaptivity lives entirely in session selection.
2. **Directive field**: `DIRECTIVE_FIELDS` gains optional `profile`; `PhaseModelDirective`
   gains `val profile: String? = null`.
3. **Session overlays**: `EXECUTION_MATRIX_FIELDS` gains `sessions` — a map of session name →
   `{phase_tiers?, agents?}` parsed with the existing phase-tier and agent parsers. Selection
   reads `SKILL_BILL_SESSION_PROFILE` from the runtime process environment once at startup:
   set and matching → that overlay's fields *replace* the default matrix fields (whole-field
   replace, no deep merge); unset or no matching key → default fields. A set selector with no
   matching overlay is normal (accounts without overlays), not a fallback, and emits no
   degradation record. The environment read stays at the CLI/adapter seam and is passed in as
   a value, matching the existing agent-detection pattern in `InstallModels.kt`.
4. **Preflight**: extend the existing model-directive preflight, operating on the
   session-selected matrix — a directive referencing an undeclared profile, or a referenced
   profile whose `auth_token_env` variable is absent from the runtime process environment,
   fails as a `UsageError` before any phase launches. Profile directives remain restricted to
   `MODEL_DIRECTIVE_CAPABLE_AGENTS`; this feature wires application for the claude builder
   only (codex refuses profile-bearing directives at the same preflight).
5. **Spawn application** (`runtime-infra-fs`): `SkillRunRequest` gains
   `environmentRemovals: Set<String>` (default empty). The claude command builder resolves the
   directive's profile into env sets (`ANTHROPIC_BASE_URL` from `base_url`,
   `ANTHROPIC_AUTH_TOKEN` from the resolved token, `CLAUDE_CONFIG_DIR` from tilde-expanded
   `config_dir`) plus removals from `unset`. `JvmAgentRunProcessRunner` applies removals after
   inheritance and before `putAll(request.environment)`. Profile env composes with — never
   clobbers — `goalContinuationEnvironment` and `compactionEnvironment` keys.
6. **No selector match, no profile → byte-for-byte today's behavior**: default matrix fields,
   empty removals, unchanged environment map, unchanged command line apart from model/effort
   flags.
7. **Operator side** (documented, not shipped): each `cc` account branch exports
   `SKILL_BILL_SESSION_PROFILE=<name>`; only `deep` needs it for the motivating use case.

## Acceptance Criteria

1. `provider_profiles` parses from machine-wide config; malformed entries (unknown field, blank
   profile name, blank field value, non-list `unset`, profile with zero fields) loud-fail with a
   typed invalid result naming keyPath, value, and reason.
2. Execution-matrix tier directives accept an optional `profile` field; any other new directive
   field still loud-fails.
3. `execution_matrix.sessions` parses as session name → `{phase_tiers?, agents?}` using the
   existing sub-parsers; unknown overlay fields and blank session names loud-fail.
4. With `SKILL_BILL_SESSION_PROFILE` set to a declared session name, that overlay's fields
   replace the default matrix fields for directive resolution; unset, or set to an undeclared
   name, resolves against the default fields with no degradation record.
5. A directive in the session-selected matrix referencing an undeclared profile fails at CLI
   preflight before any phase launches, naming the directive path and the declared profile
   names.
6. A referenced profile whose `auth_token_env` variable is missing from the runtime environment
   fails at CLI preflight before any phase launches.
7. A profile-bearing directive resolving to an agent other than claude fails at the existing
   model-directive preflight seam.
8. The spawned claude phase child receives the profile environment: `unset` variables removed
   from the inherited environment, `base_url`/token/`config_dir` set, all other inherited
   variables intact; skill-bill goal-continuation and compaction keys are never removed or
   overwritten by a profile.
9. A profile omitting `config_dir` leaves the session's `CLAUDE_CONFIG_DIR` untouched.
10. Directives without a `profile` field, under any session selection, produce today's command
    line and environment byte-for-byte apart from model/effort flags.
11. Raw tokens never appear in config or in persisted runtime state; tokens resolve from the
    named environment variable at spawn time only.
12. `docs/getting-started.md` documents `provider_profiles`, `execution_matrix.sessions`, and
    the `SKILL_BILL_SESSION_PROFILE` selector with the mixed-provider example (deep session:
    reasoning on Anthropic, implementation inherited DeepSeek; other sessions untouched).

## Non-Goals

- Per-tier agent switching (a tier always runs on the session's resolved agent).
- Codex profile application (codex keeps model/effort directives; profile-bearing directives
  refuse at preflight).
- Prose-mode changes; opencode/zcode runtime refusal is unchanged.
- Provider profiles for review/quality-check launches outside the feature-task phase runtime.
- Deep-merging session overlays into the default matrix (whole-field replace only).
- A YAML contract under `orchestration/contracts/` (the matrix is Kotlin-parsed config).
- Shipping shell-launcher changes (`cc.fish` exports are operator-side, documented only).

## Validation Strategy

- Unit: profile and `sessions` parse acceptance/rejection tests beside
  `ExecutionMatrixModelsTest`; session-selection tests (declared, undeclared, unset selector);
  directive `profile` field tests; preflight refusal tests beside the existing model-directive
  refusal coverage; claude builder env-composition tests; process-runner removal-ordering test.
- `(cd runtime-kotlin && ./gradlew check)`.
- Manual: from a `cc deep` session exporting `SKILL_BILL_SESSION_PROFILE=deep`, run a feature
  task and confirm reasoning phases hit Anthropic (`claude-opus-5`) while implementation phases
  stay on DeepSeek; from `cc work` with no overlay, confirm phases stay on the work account.

## Next Path

```bash
skill-bill goal SKILL-161
```
