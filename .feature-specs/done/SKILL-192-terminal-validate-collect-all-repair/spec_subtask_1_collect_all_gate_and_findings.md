# SKILL-192 · Subtask 1: Collect-all gate declaration and complete finding extraction

## Intended Outcome

Platform packs can declare a collect-all full-gate command and a cache-bypassing
collect-all variant, plus how the runtime extracts **compiler diagnostics and
test/static findings** from one invocation. The runner can execute those argv
and return the union finding set. No caller switches the FULL validate cycle
yet.

## Scope

- Extend `orchestration/contracts/platform-pack-schema.yaml` `validation_gate`
  with pack-owned collect-all argv (cache-eligible discovery and cache-bypassing
  confirmation) and a compiler-diagnostics locator alongside existing
  `junit_xml` artifacts. Bump the pinned shell-contract version
  (`PLATFORM_PACK_SHELL_CONTRACT_VERSION`, currently `1.4`) in the same change.
- Required when `validation_gate` is present: a pack that declares a gate must
  declare collect-all argv. Malformed or empty arrays loud-fail at pack load
  (`InvalidManifestSchemaError` / existing validation-gate typed errors), never
  degrade to fail-fast `full_gate_command`.
- Update shipped `kotlin` and `kmp` packs. Collect-all is continue-on-failure
  plus the existing full check; confirmation adds the pack's cache-bypass
  tokens. Do not hardcode Gradle flags in Kotlin.
- Extend `FileSystemValidationGateRunner` (and the `ValidationGateRunRequest`
  cache-mode / argv selection) so a new collect-all mode selects the new argv
  and parses the union finding set: compiler diagnostics from the pack-declared
  parser, JUnit (or current `findings.format`) from `artifact_globs`.
- Distinct findings by the existing identity
  (`module|ruleOrTestId|message|location`). Compiler diagnostics that parsed
  must not be replaced by `unparseable_gate_failure`.
- `unparseable_gate_failure` remains only when the gate failed and **both**
  sources are empty.
- Keep `full_gate_command` / `build_only_command` for BUILD_ONLY and for any
  caller not yet on collect-all. This subtask does not change
  `FeatureTaskRuntimeValidationGateCoordinator` cycle shape.
- Schema-to-Kotlin anchored bijection and pack coherence tests stay green.

## Acceptance Criteria

1. `validation_gate` in the platform-pack schema declares collect-all full-gate
   argv, cache-bypassing collect-all argv, and a compiler-diagnostics extraction
   contract; the pinned shell-contract version increments in schema and Kotlin
   together.
2. Shipped `kotlin` and `kmp` packs declare collect-all argv that continue after
   a failed task and a cache-bypassing collect-all confirmation variant, without
   the runtime hardcoding `--continue`, `--rerun-tasks`, or `--no-build-cache`.
3. A present `validation_gate` missing collect-all argv, or with empty/blank
   tokens, loud-fails at pack load and never resolves to `full_gate_command`.
4. The validation-gate runner, given collect-all mode, executes the pack-declared
   collect-all argv and returns the union of compiler diagnostics and JUnit (or
   equivalent) findings from that one process.
5. A compile-failing module plus a later module that still compiles yields
   compiler findings for the failed module and test findings from the module
   that compiled, in one runner result.
6. When compiler diagnostics parse, the runner does not emit
   `unparseable_gate_failure` for that run.
7. `unparseable_gate_failure` is emitted only when the run failed and both
   compiler diagnostics and artifact findings are empty.
8. BUILD_ONLY argv and JUnit-only parsing for non-collect-all requests stay
   byte-stable for existing callers.
9. `PlatformPackSchemaAnchoredBijectionTest`, pack schema contract-version
   pinning, and shipped-pack load tests pass.
10. No coordinator cycle, prompt, or goal-depth stamping change lands in this
    subtask.

## Non-Goals

- Replacing the FULL `while (true)` repair cycle (subtask 2).
- Repair plans, substantiation receipts, or confirmation identity matching
  (subtask 3).
- Changing SKILL-173 last-vs-intermediate depth assignment.
- Parsing raw stdout into the agent handoff; only structured findings leave
  the runner.

## Dependency Notes

No dependencies. Subtasks 2 and 3 consume the new argv mode and union finding
set.

Coordinate with SKILL-191 only by not editing files it is actively validating
unless the change is required here; prefer additive schema/pack/runner seams.

## Validation Strategy

- Schema rejection tests: missing collect-all keys, empty argv, blank tokens.
- Pack load: kotlin and kmp manifests parse and expose collect-all argv.
- Runner fixture: one process with a compiler failure in module A and a test
  failure in module B returns both finding kinds; fail-fast argv (control) does
  not return B.
- Runner fixture: compiler-only failure produces compiler findings, not
  `unparseable_gate_failure`.
- Existing BUILD_ONLY and cache-eligible `full_gate_command` unit tests remain
  passing.
- Module checks for `runtime-contracts`, `runtime-infra-fs`, and pack schema
  tests.

## Next Path

Subtask 2 switches FULL validate onto this collect-all runner mode.
