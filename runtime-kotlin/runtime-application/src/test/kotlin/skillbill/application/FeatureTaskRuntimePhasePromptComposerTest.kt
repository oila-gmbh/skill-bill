package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.application.featuretask.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimePhasePromptComposerTest {
  @Test
  fun `review prompt forwards selected execution mode through a parallel lane`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("review"),
      parallelReviewAgent = "claude",
      codeReviewMode = CodeReviewExecutionMode.DELEGATED,
    )

    assertContains(prompt, "bill-code-review mode:delegated")
    assertContains(prompt, "parallel:claude")
    assertContains(prompt, "must not launch parallel review recursively")
  }

  @Test
  fun `review prompt preserves every durable execution mode unchanged`() {
    CodeReviewExecutionMode.entries.forEach { mode ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
        ISSUE_KEY,
        briefingFor("review"),
        codeReviewMode = mode,
      )

      assertContains(prompt, "bill-code-review mode:${mode.wireValue}")
    }
  }

  @Test
  fun `second standalone review pass stays inline and receives the materialized immutable-base delta`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("review"),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      reviewPassNumber = 2,
      goalSubtaskReviewInput = GoalSubtaskReviewInput(
        reviewBaseSha = "0".repeat(40),
        currentHeadSha = "1".repeat(40),
        trackedDelta = "tracked delta",
        ownedUntrackedPatches = "owned untracked patch",
      ),
    )

    assertContains(prompt, "bill-code-review mode:inline")
    assertContains(prompt, "context:feature-remediation")
    assertContains(prompt, "review_scope: branch_diff")
    assertContains(prompt, "Immutable-base review scope")
    assertContains(prompt, "${"0".repeat(40)}")
    assertContains(prompt, "committed, staged, unstaged, and owned untracked changes")
    assertContains(prompt, "tracked delta")
    assertContains(prompt, "owned untracked patch")
  }

  @Test
  fun `both review passes receive the same immutable-base complete-delta payload`() {
    val input = GoalSubtaskReviewInput(
      reviewBaseSha = "a".repeat(40),
      currentHeadSha = "b".repeat(40),
      trackedDelta = "committed staged and unstaged delta",
      ownedUntrackedPatches = "run-owned untracked delta",
    )

    val prompts = listOf(1, 2).map { pass ->
      FeatureTaskRuntimePhasePromptComposer.compose(
        ISSUE_KEY,
        briefingFor("review"),
        codeReviewMode = if (pass == 1) CodeReviewExecutionMode.DELEGATED else CodeReviewExecutionMode.INLINE,
        reviewPassNumber = pass,
        goalSubtaskReviewInput = input,
      )
    }

    prompts.forEach { prompt ->
      assertContains(prompt, "durable base `${input.reviewBaseSha}` to current HEAD `${input.currentHeadSha}`")
      assertContains(prompt, input.trackedDelta)
      assertContains(prompt, input.ownedUntrackedPatches)
    }
  }

  @Test
  fun `composes header briefing and output contract for every runtime phase`() {
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.forEach { phaseId ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor(phaseId))

      assertContains(prompt, ISSUE_KEY, false, "issue key for $phaseId")
      assertContains(prompt, "Phase: $phaseId", false, "phase header for $phaseId")
      assertContains(prompt, "# Feature-task-runtime phase briefing", false, "briefing body for $phaseId")
      assertContains(prompt, "feature_size: MEDIUM", false, "feature size for $phaseId")
      assertContains(prompt, "Scaling changes scope and verbosity only", false, "gate integrity for $phaseId")
      assertContains(prompt, SPEC_REFERENCE, false, "spec reference for $phaseId")
      assertContains(prompt, "Required final output", false, "output contract for $phaseId")
      assertContains(prompt, "\"phase_id\": must be \"$phaseId\"", false, "pinned phase id for $phaseId")
      assertContains(
        prompt,
        "\"contract_version\": must be exactly " +
          "\"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION\"",
        false,
        "contract version for $phaseId",
      )
      assertContains(prompt, "\"completed\", \"blocked\", \"failed\"", false, "status enum for $phaseId")
      assertContains(prompt, "failure_disposition", false, "typed failure behavior for $phaseId")
      assertContains(prompt, "produced_outputs", false, "produced_outputs for $phaseId")
    }
  }

  @Test
  fun `each phase carries its own task directive`() {
    val preplanPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("preplan"))
    val planPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("plan"))
    val implementPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("implement"))
    val historyPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("write_history"))
    val commitPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("commit_push"))
    val prPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("pr"))

    assertContains(preplanPrompt, "scaled pre-planning digest")
    assertContains(preplanPrompt, "full preplan covering boundaries")
    assertContains(preplanPrompt, "Do not modify repository files during this phase.")
    assertContains(preplanPrompt, "schema-valid produced_outputs")
    assertContains(planPrompt, "Do not modify repository files during this phase.")
    assertContains(planPrompt, "upstream preplan digest")
    assertContains(implementPrompt, "Reconcile the repository to the intended state")
    assertTrue(
      !implementPrompt.contains("Do not modify repository files during this phase."),
      "implement must not carry the plan directive",
    )
    // The mutating-phase idempotency directive + reconciliation-report output requirement are emitted
    // only for mutating phases; non-mutating phases must not carry them.
    assertContains(implementPrompt, "Mutating-phase idempotency contract")
    assertContains(implementPrompt, "reconciliation report")
    assertTrue(
      !planPrompt.contains("Mutating-phase idempotency contract"),
      "non-mutating plan phase must not carry the idempotency directive",
    )
    assertTrue(
      !historyPrompt.contains("Mutating-phase idempotency contract"),
      "non-mutating write_history phase must not carry the idempotency directive",
    )
    assertContains(historyPrompt, "bill-boundary-history")
    assertContains(historyPrompt, "history_result")
    assertContains(commitPrompt, "commit_push_result")
    assertContains(commitPrompt, "terminal success signal")
    assertContains(prPrompt, "bill-pr-description")
    assertContains(prPrompt, "create or reuse the open")
    assertContains(prPrompt, "pr_result")
  }

  @Test
  fun `small prompts encode lighter ceremony and current unit review scope without skipping gates`() {
    val preplanPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("preplan", FeatureTaskRuntimeFeatureSize.SMALL),
    )
    val reviewPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("review", FeatureTaskRuntimeFeatureSize.SMALL),
    )
    val auditPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("audit", FeatureTaskRuntimeFeatureSize.SMALL),
    )

    assertContains(preplanPrompt, "feature_size: SMALL")
    assertContains(preplanPrompt, "preplan_ceremony: light")
    assertContains(reviewPrompt, "review_scope: current_unit_of_work")
    assertContains(reviewPrompt, "current-unit-of-work review scope")
    assertContains(auditPrompt, "audit_ceremony: light")
    assertContains(auditPrompt, "must not skip or weaken review, audit, validation")
  }

  @Test
  fun `upstream outputs flow into the prompt through the briefing text`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("implement"))

    assertContains(prompt, "### from: plan")
    assertContains(prompt, PLAN_OUTPUT)
  }

  @Test
  fun `does not instruct the goal-continuation activation flow`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("plan"))

    assertTrue(!prompt.contains("goal-continuation mode"))
    assertTrue(!prompt.contains("First execute this exact command"))
    assertContains(prompt, "do not call `skill-bill workflow continue`")
  }

  @Test
  fun `goal-continuation plan does not treat future acceptance work as a prerequisite`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("plan"),
      suppressDecomposition = true,
    )

    assertContains(prompt, "Goal-continuation planning constraint")
    assertContains(prompt, "Never include installer, uninstall, or install-sync commands in the plan")
    assertContains(prompt, "`./install.sh`")
    assertContains(prompt, "it does not require that work to have already")
    assertContains(prompt, "Never block planning merely because a later implementation or validation action")
    assertContains(prompt, "genuinely missing input or an irreconcilable constraint")
    assertTrue(!prompt.contains("return a blocked plan"))
  }

  @Test
  fun `linear commit_push prompt carries the spec-exclusion directive`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("commit_push"),
      specSource = SpecSource.LINEAR,
    )

    assertContains(prompt, "Linear-mode commit exclusion")
    assertContains(prompt, ".feature-specs/$ISSUE_KEY/")
    assertContains(prompt, "never run `git add -A`")
    assertContains(prompt, "decomposition-manifest.yaml")
  }

  @Test
  fun `local commit_push prompt omits the spec-exclusion directive and matches the default`() {
    val linear = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("commit_push"),
      specSource = SpecSource.LINEAR,
    )
    val local = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("commit_push"),
      specSource = SpecSource.LOCAL,
    )
    val default = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("commit_push"))

    assertEquals(default, local, "the spec_source default must be LOCAL (byte-for-byte unchanged)")
    assertTrue(!local.contains("Linear-mode commit exclusion"), "local mode must not carry the exclusion")
    assertTrue(local != linear, "linear mode must add the exclusion section")
  }

  @Test
  fun `local commit_push prompt with specReference includes spec inclusion directive`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("commit_push"),
      specSource = SpecSource.LOCAL,
      specReference = SPEC_REFERENCE,
    )

    assertContains(prompt, "Spec file — stage with this commit")
    assertContains(prompt, SPEC_REFERENCE)
  }

  @Test
  fun `spec inclusion directive is absent when specReference is null or blank`() {
    val noRef = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("commit_push"),
      specSource = SpecSource.LOCAL,
    )
    val blankRef = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("commit_push"),
      specSource = SpecSource.LOCAL,
      specReference = "  ",
    )

    assertTrue(!noRef.contains("Spec file — stage with this commit"), "null specReference must not emit directive")
    assertTrue(!blankRef.contains("Spec file — stage with this commit"), "blank specReference must not emit directive")
  }

  @Test
  fun `spec inclusion directive is absent in linear mode even with specReference`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("commit_push"),
      specSource = SpecSource.LINEAR,
      specReference = SPEC_REFERENCE,
    )

    assertTrue(!prompt.contains("Spec file — stage with this commit"), "linear mode must not emit spec inclusion")
  }

  @Test
  fun `spec inclusion directive is absent on non-commit phases`() {
    val implementPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("implement"),
      specSource = SpecSource.LOCAL,
      specReference = SPEC_REFERENCE,
    )

    assertTrue(!implementPrompt.contains("Spec file — stage with this commit"))
  }

  @Test
  fun `linear spec-exclusion is absent on non-commit phases`() {
    val implementPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("implement"),
      specSource = SpecSource.LINEAR,
    )

    assertTrue(!implementPrompt.contains("Linear-mode commit exclusion"))
  }

  @Test
  fun `a blank issue key loud-fails`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimePhasePromptComposer.compose(" ", briefingFor("plan"))
    }
  }

  @Test
  fun `verifying phases name the exact structured signal the schema gate keys on`() {
    val reviewPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("review"))
    val auditPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("audit"))

    assertContains(reviewPrompt, "VERIFYING phase", false, "review names itself a verifying phase")
    assertContains(reviewPrompt, "\"findings\" array", false, "review names the findings signal")
    assertContains(reviewPrompt, "\"approved\" or \"changes_requested\"", false, "review names the verdict values")
    assertContains(auditPrompt, "VERIFYING phase", false, "audit names itself a verifying phase")
    assertContains(
      auditPrompt,
      "\"unmet_criteria\" array",
      false,
      "audit names the unmet_criteria signal",
    )
    assertContains(
      auditPrompt,
      "\"satisfied\" or \"gaps_found\"",
      false,
      "audit names the verdict values",
    )
    assertContains(auditPrompt, "\"verdict\": optional top-level string", false, "top-level verdict is documented")
    assertContains(auditPrompt, "TEST EXCLUSION", false, "audit makes the test-only exclusion explicit")
    assertContains(auditPrompt, "NEVER audit gaps", false, "audit rejects test-only gaps")
    assertContains(
      auditPrompt,
      "Validation owns test execution and failures",
      false,
      "audit routes tests to validation",
    )
    assertContains(
      auditPrompt,
      "production behavior or production implementation",
      false,
      "audit scopes gaps to production",
    )
    assertContains(
      auditPrompt,
      "PROSPECTIVE REPAIR IMPACT ANALYSIS",
      false,
      "audit requires counterfactual repair analysis before accepting a plan",
    )
    assertContains(
      auditPrompt,
      "already-satisfied criteria as non-regression constraints",
      false,
      "audit protects previously satisfied behavior while planning repairs",
    )
    assertContains(
      auditPrompt,
      "cumulative repair delta and cross-repair interactions",
      false,
      "follow-up audit checks repair interactions instead of only prior symbols",
    )
    assertContains(
      auditPrompt,
      "closure-complete for that blast",
      false,
      "repair plans must cover the complete evidenced blast radius",
    )
  }

  @Test
  fun `audit prompt separates blocking gaps from non blocking findings`() {
    val auditPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("audit"))

    assertContains(auditPrompt, "blocker or major", true, "audit limits remediation gaps by severity")
    assertContains(auditPrompt, "non_blocking_findings", false, "audit preserves minor and nit findings")
    assertContains(auditPrompt, "NEVER trigger gaps_found", false, "non-blocking findings cannot reopen implementation")
  }

  @Test
  fun `non-verifying phases carry no verifying-signal addendum`() {
    listOf("preplan", "plan", "implement", "validate", "write_history", "commit_push", "pr").forEach { phaseId ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor(phaseId))
      assertTrue(!prompt.contains("VERIFYING phase"), "$phaseId must not carry the verifying-signal addendum")
    }
  }

  @Test
  fun `a prior schema-gate failure is surfaced as a corrective directive on retry`() {
    // F-003: the retry directive is phase-independent, so cover both verifying phases to guard against a
    // phase-conditional regression in its placement relative to the verifying-signal addendum.
    val reason = "Audit phase reported 'completed' without a verification signal"

    listOf("review", "audit").forEach { phaseId ->
      val firstAttempt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor(phaseId))
      val retry = FeatureTaskRuntimePhasePromptComposer.compose(
        ISSUE_KEY,
        briefingFor(phaseId),
        priorSchemaFailure = reason,
      )

      assertTrue(!firstAttempt.contains("REJECTED by the schema gate"), "$phaseId first attempt: no correction")
      assertContains(retry, "Previous attempt was REJECTED by the schema gate", false, "$phaseId retry: rejection")
      assertContains(retry, reason, false, "$phaseId retry carries the validator's reason verbatim")
    }
  }

  @Test
  fun `an operator blocked-phase retry decision is delivered only to its matching phase`() {
    val reason = "Use fresh-process isolation for Codex CLI workers."
    val retry = FeatureTaskRuntimeOperatorBlockRetry(
      phaseId = "implement",
      reason = reason,
      retriedAt = "2026-07-21T16:30:00Z",
    )

    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("implement"),
      operatorBlockRetry = retry,
    )

    assertContains(prompt, "Operator-applied blocked-phase retry decision")
    assertContains(prompt, reason)
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimePhasePromptComposer.compose(
        ISSUE_KEY,
        briefingFor("audit"),
        operatorBlockRetry = retry,
      )
    }
  }

  @Test
  fun `an unparseable-root failure appends a phase-correct fill-in skeleton`() {
    // When the runtime could not parse any JSON object out of the prior output (the audit/review prose
    // or array case), the retry must do more than echo the reason: name the mistake and hand back a
    // skeleton carrying this phase's exact verdict and produced_outputs keys.
    val auditRetry = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("audit"),
      priorSchemaFailure = "<root> must be an object.",
    )
    val reviewRetry = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("review"),
      priorSchemaFailure = "<root> must be an object.",
    )

    assertContains(auditRetry, "could NOT parse a single JSON object", false, "audit names the parse failure")
    assertContains(auditRetry, "Markdown table, or a JSON array", false, "audit names the likely mistake")
    assertContains(auditRetry, "<one sentence describing what this phase did>", false, "audit hands back a skeleton")
    assertContains(auditRetry, "\"phase_id\": \"audit\"", false, "skeleton pins the phase id")
    assertContains(auditRetry, "\"verdict\": \"satisfied\"", false, "audit skeleton seeds the audit verdict")
    assertContains(auditRetry, "\"unmet_criteria\": []", false, "audit skeleton seeds the audit signal key")
    assertContains(
      auditRetry,
      "\"non_blocking_findings\": []",
      false,
      "audit skeleton seeds the non-blocking findings key",
    )
    assertContains(reviewRetry, "\"verdict\": \"approved\"", false, "review skeleton seeds the review verdict")
    assertContains(reviewRetry, "\"findings\": []", false, "review skeleton seeds the review signal key")
  }

  @Test
  fun `a malformed-output failure also appends the fill-in skeleton`() {
    val retry = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("audit"),
      priorSchemaFailure = "Phase output is malformed: unexpected end-of-input",
    )

    assertContains(retry, "could NOT parse a single JSON object", false, "malformed output triggers the skeleton")
    assertContains(retry, "<one sentence describing what this phase did>", false, "malformed output hands a skeleton")
  }

  @Test
  fun `a field-level violation echoes the reason without the parse-failure skeleton`() {
    // A reason that already pinpoints an offending field must keep the lean reason-only correction so
    // those retries stay byte-for-byte unchanged.
    val retry = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("audit"),
      priorSchemaFailure = "summary: must be a non-empty string",
    )

    assertContains(retry, "Previous attempt was REJECTED by the schema gate", false, "still corrects")
    assertContains(retry, "summary: must be a non-empty string", false, "still carries the field reason")
    assertTrue(!retry.contains("could NOT parse a single JSON object"), "no parse-failure block for field errors")
    assertTrue(
      !retry.contains("<one sentence describing what this phase did>"),
      "no skeleton for field-level violations",
    )
  }

  @Test
  fun `audit remediation output contract names every carried item and required evidence field`() {
    val briefing = briefingFor("implement").copy(
      auditRepairItemIds = listOf("ac-004-gap-2-item-1", "ac-005-gap-1-item-1"),
    )

    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefing)

    assertContains(prompt, "AUDIT-GAP REMEDIATION")
    assertContains(prompt, "ac-004-gap-2-item-1")
    assertContains(prompt, "ac-005-gap-1-item-1")
    assertContains(prompt, "\"repair_item_results\"")
    assertContains(prompt, "\"changed_paths_or_symbols\"")
    assertContains(prompt, "\"executed_verification\"")
    assertContains(prompt, "\"result_evidence\"")
    assertContains(prompt, "artifact_ref MUST be a repository-relative path")
    assertContains(prompt, "do not put a sentence, spaces, test description, command")
    assertContains(prompt, "\"reconciled_state\"")
  }

  @Test
  fun `audit remediation retry repeats the exact item ids and complete output skeleton`() {
    val briefing = briefingFor("implement").copy(
      auditRepairItemIds = listOf("ac-004-gap-2-item-1", "ac-005-gap-1-item-1"),
    )

    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefing,
      priorSchemaFailure =
      "Audit repair item 'ac-005-gap-1-item-1' executed_verification must contain concrete verification evidence.",
    )

    assertContains(prompt, "Correct every carried item exactly once and in this order")
    assertContains(prompt, "ac-004-gap-2-item-1, ac-005-gap-1-item-1")
    assertContains(prompt, "Required produced_outputs shape")
    assertContains(prompt, "<command and result>")
  }

  @Test
  fun `a blank prior schema failure yields no correction directive`() {
    // F-002: retryCorrectionDirective treats null and blank identically (isNullOrBlank). A blank reason
    // must not emit a no-op "REJECTED" heading with nothing under it.
    listOf("", "   ", "\n").forEach { blank ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
        ISSUE_KEY,
        briefingFor("audit"),
        priorSchemaFailure = blank,
      )
      assertTrue(!prompt.contains("REJECTED by the schema gate"), "blank reason '$blank' must produce no correction")
    }
  }

  @Test
  fun `verifying-phase prompts name the exact keys the runtime gate reads`() {
    // F-004: the gate reads these keys from a phase's output and the prompt instructs the agent to emit
    // them; both sides bind to FeatureTaskRuntimeVerificationSignalKeys. This fails if the prompt ever
    // stops naming a key the gate still consumes — the exact prompt/gate drift this feature prevents.
    val keys = FeatureTaskRuntimeVerificationSignalKeys
    val reviewPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("review"))
    val auditPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("audit"))

    assertContains(reviewPrompt, keys.REVIEW_FINDINGS, false, "review names the findings key")
    assertContains(reviewPrompt, keys.VERDICT, false, "review names the verdict key")
    assertContains(auditPrompt, keys.AUDIT_UNMET_CRITERIA, false, "audit names the unmet_criteria key")
    assertContains(auditPrompt, keys.AUDIT_NON_BLOCKING_FINDINGS, false, "audit names the non-blocking key")
    assertContains(auditPrompt, keys.VERDICT, false, "audit names the verdict key")
  }
}

private const val ISSUE_KEY = "SKILL-66"
private const val SPEC_REFERENCE = ".feature-specs/SKILL-66/spec.md"
private const val PREPLAN_OUTPUT = """{"preplan_digest":"scope-boundaries-risks-rollout"}"""
private const val PLAN_OUTPUT = """{"plan":"ordered-steps"}"""

private fun briefingFor(
  phaseId: String,
  featureSize: FeatureTaskRuntimeFeatureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
) = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
  FeatureTaskRuntimeHandoffContract.assembleHandoff(
    declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(phaseId, featureSize),
    runInvariants = FeatureTaskRuntimeRunInvariants(
      specReference = SPEC_REFERENCE,
      featureSize = featureSize,
      acceptanceCriteria = listOf("AC-1"),
      mandatesAndOverrides = emptyList(),
    ),
    recordedOutputs = listOf(
      FeatureTaskRuntimePhaseOutput("preplan", 1, PREPLAN_OUTPUT),
      FeatureTaskRuntimePhaseOutput("plan", 1, PLAN_OUTPUT),
      FeatureTaskRuntimePhaseOutput("implement", 1, """{"implement":"done"}"""),
      FeatureTaskRuntimePhaseOutput("review", 1, """{"review":"ok"}"""),
    ),
  ),
)
