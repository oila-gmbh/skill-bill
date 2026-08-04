# SKILL-161 Subtask 1 — Session-adaptive execution matrix with provider profiles

## Scope

Implement session-adaptive matrix selection and provider profiles end to end in one pass:

- `runtime-domain`: parse a top-level `provider_profiles` config key — profile name →
  `{base_url?, auth_token_env?, config_dir?, unset?}` — with strict field allowlists and a
  sealed valid/invalid parse result matching the `ExecutionMatrixParse` pattern (keyPath,
  value, reason). Each profile requires at least one field; profiles are unconditional
  presets. Add optional `profile` to `PhaseModelDirective` and to `DIRECTIVE_FIELDS`, and add
  `sessions` to `EXECUTION_MATRIX_FIELDS` in `ExecutionMatrixModels.kt` — session name →
  `{phase_tiers?, agents?}` parsed with the existing sub-parsers.
- Session selection: read `SKILL_BILL_SESSION_PROFILE` from the process environment once at
  the CLI/adapter seam and pass it in as a value (matching the agent-detection pattern in
  `InstallModels.kt`). A set selector matching a declared session replaces the default matrix
  fields with that overlay's fields (whole-field replace, no deep merge); unset or undeclared
  resolves against the default fields with no degradation record.
- `runtime-application`: surface parsed profiles through `ConfigResolutionService` beside the
  execution matrix; resolve directives against the session-selected matrix; thread the
  resolved profile for a phase directive into the launch request built in
  `FeatureTaskRuntimeRunLoop` (near the existing `modelOverride`/`effortOverride` wiring at
  the `SkillRunRequest` construction).
- `runtime-cli`: extend the preflight beside `refuseUnsupportedModelDirectives`
  (`ModelDirectiveRefusal.kt`), operating on the session-selected matrix — undeclared profile
  reference, missing `auth_token_env` variable in the runtime environment, and
  profile-bearing directive on a non-claude agent all fail as `UsageError` before any phase
  launches.
- `runtime-ports` / `runtime-infra-fs`: add `environmentRemovals: Set<String>` (default empty)
  to `SkillRunRequest` (`AgentRunLauncherModels.kt`). In `ClaudeAgentRunCommandBuilder`
  (`AgentRunCommandBuilders.kt`), resolve the profile into environment sets
  (`ANTHROPIC_BASE_URL` from `base_url`, `ANTHROPIC_AUTH_TOKEN` from the variable named by
  `auth_token_env`, `CLAUDE_CONFIG_DIR` from tilde-expanded `config_dir`) and removals from
  `unset`. In `JvmAgentRunProcessRunner` (env handling at the `inheritEnvironment` seam,
  currently lines 1035-1044), apply removals after inheritance and before
  `putAll(request.environment)`.
- `docs/getting-started.md`: document `provider_profiles`, `execution_matrix.sessions`, and
  the `SKILL_BILL_SESSION_PROFILE` selector with the mixed-provider example, including the
  operator-side shell export.

Profile environment must compose with, never clobber, `goalContinuationEnvironment` and
`compactionEnvironment` keys. Tokens resolve from the named environment variable at spawn time
only; no raw token in config or persisted state.

## Acceptance Criteria (this subtask)

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

- Per-tier agent switching; codex profile application; prose-mode changes; review/quality-check
  launch profiles; deep-merging session overlays; shipping shell-launcher changes; any YAML
  contract under `orchestration/contracts/`.

## Dependency Notes

None. Single subtask; touches `runtime-domain`, `runtime-application`, `runtime-cli`,
`runtime-ports`, `runtime-infra-fs`, and `docs/` in one dependency-ordered pass (domain parse →
session selection → application threading → ports/infra spawn → CLI preflight → docs).

## Validation Strategy

- Profile and `sessions` parse acceptance/rejection tests beside `ExecutionMatrixModelsTest`.
- Session-selection tests: declared overlay, undeclared name, unset selector.
- Directive `profile` field tests in `ExecutionMatrixModelsTest`.
- Preflight refusal tests beside the existing model-directive refusal coverage.
- Claude builder env-composition tests (sets, removals, config-dir expansion, no-profile
  passthrough) in the agent-run builder test suite.
- Process-runner test proving removals apply after inheritance and before request-environment
  overlay.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Feature complete after this subtask; runtime marks the goal complete and proceeds to
commit/PR per the standard phase loop.
