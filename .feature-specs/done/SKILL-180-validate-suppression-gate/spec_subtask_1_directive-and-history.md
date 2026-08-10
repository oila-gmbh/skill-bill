# SKILL-180 · Subtask 1: Reach the validate agent

## Scope

Make the no-suppression rule reachable by the agent that actually runs the
validation gate, and stop the repository's own injected memory from teaching the
opposite.

Two changes, both text-only:

**A. Validate phase directive**
`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimePhasePromptDirectives.kt`

- The `PHASE_VALIDATE` entry in `phaseDirectives` (around `:346`) gains an
  explicit instruction to invoke `bill-code-check` — mirroring how
  `skills/bill-pr-review-fix/content.md:114` does it — plus a clause stating that
  findings are fixed at their root cause and never silenced with annotations,
  baselines, disabled rules, weakened configuration, or skipped tests.
- The clause must stay stack-agnostic. `bill-code-check` auto-routes to whichever
  quality-check skill the host repo's platform pack declares, so the directive
  names the router, never `bill-kotlin-code-check`.
- Keep the existing batching contract intact: one gate run, fix the complete
  finding set, rerun once to verify. Do not reword it.
- **Transitional by design.** Subtask 2 moves gate execution into the runtime and
  will replace this invocation clause with "you receive findings, you do not run
  the gate". Until it lands, the agent still runs the gate, so instructing it to
  route through `bill-code-check` is correct for this window. The no-suppression
  clause added here is permanent and survives that change unmodified.

**B. BUILD_ONLY validate variant**
Same file: `BUILD_ONLY_VALIDATE_PHASE_TASK` (around `:249`) and
`BUILD_ONLY_VALIDATE_DIRECTIVE_SECTION` (around `:256`).

- Both already forbid running tests, detekt, spotless, lint, dependency scanners,
  and the full `bill-code-check` gate. That prohibition stays.
- Add a depth-appropriate clause: while fixing compile/build failures, do not
  introduce suppressions, disable rules, or weaken configuration to make the
  build pass.
- Do not add a `bill-code-check` invocation here — it would directly contradict
  the depth contract.

**C. `agent/history.md` correction**
`runtime-kotlin/runtime-infra-fs/agent/history.md:181` and `:204`.

- `:181` — *"resolved with `@Suppress` + one-line rationale (established repo
  pattern)"*
- `:204` — *"resolve with `@Suppress("TooManyFunctions")` + one-line rationale
  (established 67-file pattern), not a refactor"*

Both present suppression as the preferred resolution and `:204` explicitly rules
out refactoring. Rewrite both so they record what happened factually without
prescribing suppression as the pattern for future work. These are historical
entries: correct the guidance they carry, do not delete the history.

Then sweep every other `agent/history.md` in the repository for lines that
instruct an agent to prefer a suppression over a root-cause fix, and correct
those the same way. Lines that merely *record* a suppression having been used, or
that describe resolving something *without* suppression, are fine and stay.

## Acceptance Criteria (this subtask)

1. The `PHASE_VALIDATE` directive instructs the agent to invoke `bill-code-check`
   and forbids silencing findings with annotations, baselines, disabled rules,
   weakened configuration, or skipped tests.
2. The validate directive names `bill-code-check` and not any stack-specific
   quality-check skill.
3. The existing validate batching contract — one gate run, fix the complete
   finding set, rerun once to verify — is preserved verbatim in meaning.
4. `BUILD_ONLY_VALIDATE_PHASE_TASK` and `BUILD_ONLY_VALIDATE_DIRECTIVE_SECTION`
   each forbid introducing suppressions, disabling rules, or weakening
   configuration while repairing compile/build failures.
5. Neither BUILD_ONLY text instructs the agent to invoke `bill-code-check` or to
   run tests, detekt, spotless, lint, or dependency scanners.
6. `runtime-kotlin/runtime-infra-fs/agent/history.md:181` and `:204` no longer
   present `@Suppress` as the established, preferred, or recommended resolution,
   and `:204` no longer rules out refactoring.
7. No `agent/history.md` line anywhere in the repository instructs an agent to
   prefer a suppression over a root-cause fix.
8. Prompt-composition regression coverage asserts the new clause is present in
   the FULL-depth validate task text and absent from any phase that does not run
   the gate.
9. Prompt-composition regression coverage asserts the BUILD_ONLY validate text
   carries its no-suppression clause and still carries its existing prohibitions.

## Non-Goals

- Any runtime enforcement or schema change; that is subtask 2.
- Editing the quality-check pack rule text, which is already correct.
- Removing existing suppressions from source.

## Dependency Notes

None. This subtask is independent and lands first so the justification contract
introduced in subtask 2 arrives after the agent has already been told the rule.

## Validation Strategy

- Prompt-composition tests around `FeatureTaskRuntimePhasePromptComposer` /
  `phaseTaskDirective` for both `ValidationDepth.FULL` and
  `ValidationDepth.BUILD_ONLY`.
- A repository-wide assertion or check over `agent/history.md` files for
  suppression-prescribing guidance.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Proceed to `spec_subtask_2_runtime-owned-gate.md`.
