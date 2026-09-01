
package skillbill.application

import skillbill.application.featuretask.AUDIT_READONLY_EVIDENCE_SENTENCE
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimePhasePromptComposerTest {

  @Test
  fun `review prompt forwards selected execution mode through a parallel lane`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("review"),
    ) { copy(codeReviewMode = CodeReviewExecutionMode.INLINE) }

    assertContains(prompt, "The runtime owns this review")
    assertFalse(prompt.contains("Run `bill-code-review"))
    assertFalse(prompt.contains("parallel:claude"))
  }

  @Test
  fun `initial preplan prompt excludes review mode, commit-PR, and finalization mandate text`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN),
    )

    assertContains(prompt, "non-blank value")
    assertContains(prompt, "produced_outputs", false, "preplan warns about produced_outputs shape")
    assertContains(prompt, "\"projection_kind\": \"preplanning_digest\"", false, "preplan teaches stuffed digest JSON")
    assertFalse(prompt.contains("bill-code-review mode:"), "review execution mode must not reach preplan")
    assertFalse(prompt.contains("Review execution mode"), "review execution directive must not reach preplan")
    assertFalse(
      prompt.contains("commit_push") && prompt.contains("Run no git command in this phase"),
      "commit/PR instructions must not reach preplan",
    )
    assertFalse(prompt.contains("PR URL"), "PR finalization language must not reach preplan")
    assertFalse(prompt.contains("boundary history"), "history finalization language must not reach preplan")
  }

  @Test
  fun `preplan shape example declares value prose`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN),
    )

    val shapeExample = prompt.substringAfter("Required produced_outputs shape")
      .substringAfter("```json")
      .substringBefore("```")
    assertContains(shapeExample, "\"value\":", false, "the copyable shape example must name value prose")
  }

  @Test
  fun `plan prompt names exactly the phase prose required fields`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
    )

    assertContains(prompt, "\"value\":", false, "the copyable shape example must name value prose")
    assertContains(prompt, "prompt", false, "plan may optionally carry prompt prose")
    assertContains(prompt, "Inner object to stuff into value", false, "plan teaches stuffed executable_plan JSON")
    assertContains(
      prompt,
      "\"projection_kind\": \"executable_plan\"",
      false,
      "plan inner example names executable_plan",
    )
  }

  @Test
  fun `implement prompt names the implementation-receipt required fields`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
    )

    assertContains(prompt, "Inner object to stuff into value", false, "implement teaches stuffed receipt JSON")
    assertContains(prompt, "completed_task_ids")
    assertContains(prompt, "changed_paths")
    assertContains(prompt, "tests_executed")
    assertContains(prompt, "reconciliation_evidence")
    assertContains(prompt, "runtime-owned")
    assertContains(prompt, "omit it entirely")
    assertContains(
      prompt,
      "\"projection_kind\": \"implementation_receipt\"",
      false,
      "implement shows the stuffed receipt inner shape",
    )
    assertContains(
      prompt,
      "\"reconciliation_evidence\": { \"reconciled\": true",
      false,
      "implement shows the receipt evidence",
    )
    assertContains(
      prompt,
      "deviations entries are objects { \"ref\", \"note\" }",
      false,
      "implement shows the deviation object item shape",
    )
  }

  @Test
  fun `implement_fix prompt carries the repair receipt census shape and the unchanged scope prohibition`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX),
    )

    assertContains(prompt, "\"repair_receipt\": {")
    assertContains(prompt, "\"contract_version\": \"0.3\"")
    assertContains(prompt, "\"finding_id\": \"F-001\", \"outcome\": \"addressed\"")
    assertContains(prompt, "finding_id")
    assertContains(prompt, "Coverage matches on finding_id and outcome alone")
    assertContains(prompt, "Recommended optional fields")
    assertFalse(prompt.contains("HARD SIZE LIMITS enforced by the schema"))
    assertFalse(prompt.contains("no Kotlin backtick"))
    assertFalse(prompt.contains("over-length field is rejected"))
    assertFalse(
      prompt.contains("pre_fix_checkpoint_sha"),
      "The remediation base sha is runtime-owned and absent from the briefing, so asking for it can " +
        "only produce an unrepairable rejection loop.",
    )
    assertContains(prompt, "specialist narratives and raw review output are not")
    assertContains(prompt, "Do not re-apply the plan from scratch")
    assertContains(prompt, "repair_receipt")
  }

  @Test
  fun `verify_findings prompt carries the disposition census shape and envelope verdict`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS),
    )

    assertContains(prompt, "VERIFYING phase")
    assertContains(prompt, "\"findings_verified\" or \"no_findings_verified\"")
    assertContains(prompt, "Required example: {\"finding_id\":\"F-001\",\"disposition\":\"verified\"}")
    assertContains(prompt, "Recommended optional fields")
    assertFalse(prompt.contains("at least one finding is verified"))
    assertFalse(prompt.contains("HARD SIZE LIMITS enforced by the schema"))
    assertFalse(prompt.contains("no Kotlin backtick"))
    assertFalse(prompt.contains("over-length field is rejected"))
  }

  @Test
  fun `only validate may run the pack check gate`() {
    val ownershipTitle = "Validation ownership"
    val phasesRequiringValidationOwnership = listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
    )
    phasesRequiringValidationOwnership.forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      assertContains(prompt, ownershipTitle, false, "ownership title for $phaseId")
      assertContains(prompt, "Only the validate phase may run the pack validation gate", false, phaseId)
      assertContains(prompt, "./gradlew check", false, phaseId)
      assertContains(prompt, "must not compile, build,", false, phaseId)
    }

    val validatePrompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )
    assertFalse(
      validatePrompt.contains(ownershipTitle),
      "validate must not carry the non-validate forbid; it owns the gate",
    )
    assertContains(validatePrompt, "Invoke bill-code-check for collect-all and confirmation")
    assertContains(validatePrompt, "validation_gate")

    val reviewPrompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
    )
    assertContains(reviewPrompt, "validate owns those")

    val buildPrompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
    )
    assertFalse(buildPrompt.contains(ownershipTitle), "build owns compile proof, not validate gate ownership")
    assertContains(buildPrompt, "pack build_command")
  }

  @Test
  fun `audit with gate-proof AC may run the named gate and must inventory every finding`() {
    val criteria = listOf("detekt reports zero LongMethod issues under maxIssues 0")
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("audit", PromptComposerBriefingOptions(acceptanceCriteria = criteria)),
    )
    assertContains(prompt, "Validation ownership")
    assertContains(prompt, "require mechanical gate proof")
    assertContains(prompt, "COMPLETE remaining finding inventory")
    assertFalse(prompt.contains("Only the validate phase may run the pack validation gate"))
    assertFalse(prompt.contains(AUDIT_READONLY_EVIDENCE_SENTENCE))
    assertContains(prompt, "inventory every remaining finding for that proof")
  }

  @Test
  fun `audit-gap implement with gate-proof AC may clear the full gate inventory`() {
    val criteria = listOf("detekt reports zero issues for complexity rules")
    val memory = FeatureTaskRuntimePriorGapMemory(
      round = 1,
      priorAuditValues = listOf("""{"gaps":[{"criterion":"AC-003","note":"LongMethod peers remain"}]}"""),
    )
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(
        "implement",
        PromptComposerBriefingOptions(
          priorGapMemory = memory,
          auditGapReentry = true,
          acceptanceCriteria = criteria,
        ),
      ),
    )
    assertContains(prompt, "require mechanical gate proof")
    assertContains(prompt, "Clear every finding from that inventory")
    assertContains(prompt, "AUDIT-GAP REMEDIATION with gate-proof")
    assertFalse(prompt.contains("Only the validate phase may run the pack validation gate"))
    assertContains(prompt, "re-run that same gate once at the end")
  }

  @Test
  fun `forward implement still forbids the pack gate even when ACs mention detekt`() {
    val criteria = listOf("detekt reports zero LongMethod issues")
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement", PromptComposerBriefingOptions(acceptanceCriteria = criteria)),
    )
    assertContains(prompt, "Only the validate phase may run the pack validation gate")
    assertContains(prompt, "must not compile, build,")
    assertFalse(prompt.contains("require mechanical gate proof"))
    assertContains(prompt, "do not run builds or tests here")
  }

  @Test
  fun `validate prompt shows repository checkpoint as a fingerprint object`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )

    assertContains(prompt, "\"validation_result\": {")
    assertContains(prompt, "\"repository_checkpoint\": { \"fingerprint\":")
    assertContains(prompt, "never a prefixed string")
  }

  @Test
  fun `validate prompt batches repair from runtime finding set`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )

    assertContains(prompt, "Invoke bill-code-check for collect-all and confirmation")
    assertContains(prompt, "validation_gate")
    assertContains(prompt, "only validate agent for this step")
    assertContains(prompt, "do not spawn delegated subagents")
    assertContains(prompt, "up to three repair turns")
    assertContains(prompt, "Do not run `skill-bill validate`")
    assertContains(prompt, "`npx agnix`")
    assertTrue(prompt.contains("Invoke bill-code-check"))
  }

  @Test
  fun `validate prompt names the pack collect-all argv and forbids extra checklists`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    ) { copy(packCollectAllCommand = "./gradlew check --continue") }

    assertContains(prompt, "Invoke bill-code-check for collect-all and confirmation")
    assertContains(prompt, "`./gradlew check --continue`")
    assertContains(prompt, "Do not run `skill-bill validate`")
    assertContains(prompt, "`npx agnix`")
    assertContains(prompt, "scripts/validate_agent_configs")
    assertContains(prompt, "exactly that argv")
    assertFalse(prompt.contains("Do not run `bill-code-check`"))
  }

  @Test
  fun `build prompt names pack build_command and forbids collect-all and validate checklists`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
    ) { copy(packBuildCommand = "./gradlew compileKotlin") }
    assertContains(prompt, "./gradlew compileKotlin")
    assertContains(prompt, "collect_all_full_gate_command")
    assertContains(prompt, "skill-bill validate")
    assertContains(prompt, "bill-code-check")
    assertContains(prompt, "check --continue")
    assertContains(prompt, "do not emit build_receipt")
    assertContains(prompt, "up to three repair turns")
  }

  @Test
  fun `absent gate agent-run validate prompt restores bill-code-check and surfaces degradation`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    ) { copy(agentRunValidateFallback = true) }

    assertContains(prompt, "Invoke bill-code-check for collect-all and confirmation")
    assertContains(prompt, "Validation gate degradation")
    assertContains(prompt, "declares no validation_gate")
    assertContains(
      prompt,
      "never silence them with annotations, baselines, disabled rules, weakened configuration, or skipped tests",
    )
    assertFalse(prompt.contains("runtime owns collect-all execution"))
  }

  @Test
  fun `full and non-goal validate prompts carry bill-code-check pack gate contract`() {
    val fullPrompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )
    val defaultPrompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )

    listOf(fullPrompt, defaultPrompt).forEach { prompt ->
      assertContains(prompt, "Invoke bill-code-check for collect-all and confirmation")
      assertContains(prompt, "validation_gate")
      assertContains(prompt, "Do not run `skill-bill validate`")
      assertContains(prompt, "`npx agnix`")
      assertContains(prompt, "scripts/validate_agent_configs")
      assertContains(prompt, "run bill-code-check once to confirm")
      assertContains(prompt, "only validate agent for this step")
      assertContains(prompt, "do not spawn delegated subagents")
      assertContains(prompt, "up to three repair turns")
      assertContains(prompt, "delegated subagents")
      assertContains(prompt, "numbered free-form checklist")
      assertContains(prompt, "After you have attempted a fix for every open finding")
      assertContains(prompt, "project-wide `./gradlew spotlessApply`")
      assertContains(prompt, "`detekt`")
      assertContains(prompt, "`ktlintCheck`")
      assertContains(prompt, "`compileKotlin`")
      assertContains(prompt, "`test`")
      assertFalse(prompt.contains("First action every repair turn"))
      assertFalse(prompt.contains("Do not start the next checklist item"))
      assertFalse(prompt.contains("narrowest allowed proof"))
      assertFalse(prompt.contains("You may also run targeted"))
      assertContains(
        prompt,
        "never silence them with annotations, baselines, disabled rules, weakened configuration, or skipped tests",
      )
      assertFalse(prompt.contains("Do not run `bill-code-check`"))
      assertFalse(prompt.contains("Goal-continuation validate depth"))
      assertFalse(prompt.contains("runtime owns execution of the repository validation gate"))
    }
  }

  @Test
  fun `agent-run validate prompts allow targeted proof after all fixes and forbid a second agent`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    ) { copy(agentRunValidateFallback = true) }

    assertContains(prompt, "After you have attempted a fix for every open finding")
    assertContains(prompt, "project-wide `./gradlew spotlessApply`")
    assertContains(prompt, "`detekt`")
    assertContains(prompt, "`ktlintCheck`")
    assertContains(prompt, "`test`")
    assertContains(prompt, "`compileKotlin`")
    assertContains(prompt, "only validate agent for this step")
    assertContains(prompt, "do not spawn delegated subagents")
    assertContains(prompt, "up to three repair turns")
    assertContains(prompt, "Do not rerun the full gate, bill-code-check, a cache-bypassing full check")
    assertContains(prompt, "Do not run `skill-bill validate`")
    assertContains(prompt, "`npx agnix`")
    assertFalse(prompt.contains("First action every repair turn"))
    assertFalse(prompt.contains("You may also run targeted"))
    assertFalse(prompt.contains("Rerun early only when"))
    assertFalse(prompt.contains("allowed work is read, search, and source edits only"))
    assertFalse(prompt.contains("findings_open"))
  }

  @Test
  fun `FULL and default runtime-owned validate prompts name the complete finding set`() {
    val finding = ValidationGateFinding("m", "t", "broken", "loc")
    val page = ValidationFindingSetProjection(
      findings = listOf(finding),
    )
    val fullPrompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    ) { copy(validationGateFindings = page, validationGateRepair = true) }
    val defaultPrompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    ) { copy(validationGateFindings = page, validationGateRepair = true) }
    listOf(fullPrompt, defaultPrompt).forEach { prompt ->
      assertContains(prompt, "A prior gate run parsed these items")
      assertContains(prompt, "full open set for this repair turn")
      assertContains(prompt, "validate repair agent")
      assertFalse(prompt.contains("Invoke bill-code-check for collect-all and confirmation"))
      assertContains(prompt, "collect_all_full_gate_command")
      assertContains(prompt, "Do not run `skill-bill validate`")
      assertContains(prompt, "Do not spawn delegated subagents")
      assertContains(prompt, "numbered free-form checklist")
      assertContains(prompt, "After you have attempted a fix for every open finding")
      assertContains(prompt, "project-wide `./gradlew spotlessApply`")
      assertFalse(prompt.contains("First action every repair turn"))
      assertFalse(prompt.contains("Do not start the next checklist item"))
      assertFalse(prompt.contains("You may also run targeted"))
      assertContains(prompt, "Gate repair — prose only, no phase-output schema")
      assertContains(prompt, "blast radius")
      assertFalse(prompt.contains("Required final output (validated schema gate)"))
    }
  }

  @Test
  fun `runtime-owned build prompt names the complete finding set`() {
    val finding = ValidationGateFinding("m", "t", "broken", "loc")
    val page = ValidationFindingSetProjection(
      findings = listOf(finding),
    )
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
    ) {
      copy(
        validationGateFindings = page,
        validationGateRepair = true,
        packBuildCommand = "./gradlew compileKotlin",
      )
    }
    assertContains(prompt, "## Runtime build gate findings")
    assertContains(prompt, "A prior gate run parsed these items")
    assertContains(prompt, "full open set for this repair turn")
    assertContains(prompt, "pack-declared build command")
    assertContains(prompt, "collect_all_full_gate_command")
    assertContains(prompt, "Do not spawn delegated subagents")
    assertContains(prompt, "module=m id=t location=loc message=broken")
    assertContains(prompt, "Gate repair — prose only, no phase-output schema")
    assertContains(prompt, "blast radius")
    assertFalse(prompt.contains("Required final output (validated schema gate)"))
    assertFalse(prompt.contains("Required produced_outputs shape: emit a build_receipt"))
  }

  @Test
  fun `validate triage prompt forbids gate argv with same strength as repair prompt`() {
    val finding = ValidationGateFinding("m", "t", "broken", "loc")
    val page = ValidationFindingSetProjection(
      findings = listOf(finding),
    )
    val triagePrompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    ) { copy(validationGateFindings = page, validationGateTriage = true) }
    val repairPrompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    ) { copy(validationGateFindings = page, validationGateRepair = true) }
    val forbiddenPhrases = listOf(
      "Do not run `skill-bill validate`",
      "bill-code-check",
      "./gradlew check",
      "collect_all_full_gate_command",
    )
    forbiddenPhrases.forEach { phrase ->
      assertContains(triagePrompt, phrase)
      assertContains(repairPrompt, phrase)
    }
    assertContains(triagePrompt, "triage")
    assertContains(triagePrompt, "validation_repair_plan")
  }

  @Test
  fun `repair prompt includes triage working notes when plan captured`() {
    val finding = ValidationGateFinding("m", "t", "broken", "loc")
    val page = ValidationFindingSetProjection(
      findings = listOf(finding),
    )
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    ) {
      copy(
        validationGateFindings = page,
        validationGateRepair = true,
        validationGateTriagePlan = "module=m: run spotlessApply then fix Foo.kt",
      )
    }
    assertContains(prompt, "## Triage working notes")
    assertContains(prompt, "module=m: run spotlessApply then fix Foo.kt")
  }

  @Test
  fun `repair prompt omits triage section when plan empty`() {
    val finding = ValidationGateFinding("m", "t", "broken", "loc")
    val page = ValidationFindingSetProjection(
      findings = listOf(finding),
    )
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    ) { copy(validationGateFindings = page, validationGateRepair = true) }
    assertFalse(prompt.contains("## Triage working notes"))
  }

  @Test
  fun `full validate prompt carries no-suppression clause absent from non-validate phases`() {
    val validatePrompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )
    assertContains(
      validatePrompt,
      "never silence them with annotations, baselines, disabled rules, weakened configuration, or skipped tests",
    )
    assertContains(validatePrompt, "Invoke bill-code-check for collect-all and confirmation")
    assertFalse(validatePrompt.contains("Invoke bill-kotlin-code-check"))

    val nonValidatePhases = listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
    )
    nonValidatePhases.forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      assertFalse(
        prompt.contains("Invoke bill-code-check for collect-all and confirmation"),
        "phase $phaseId must not carry the validate gate-invocation clause",
      )
      assertFalse(
        prompt.contains("never silence them with annotations, baselines, disabled rules"),
        "phase $phaseId must not carry the validate no-suppression clause",
      )
    }
  }

  @Test
  fun `review prompt preserves every durable execution mode unchanged`() {
    CodeReviewExecutionMode.entries.forEach { mode ->
      val prompt = composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("review"),
      ) { copy(codeReviewMode = mode) }

      assertFalse(prompt.contains("Run `bill-code-review"))
      assertFalse(prompt.contains("bill-code-review mode:${mode.wireValue}"))
    }
  }

  @Test
  fun `review prompt lists the durable baseline-untracked inventory without CLI exclude flags`() {
    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("review"),
    ) {
      copy(
        codeReviewMode = CodeReviewExecutionMode.INLINE,
        reviewPassNumber = 1,
        baselineUntrackedPaths = listOf("z-before.tmp", "a-before.tmp"),
      )
    }

    assertContains(prompt, "Baseline-untracked review policy")
    assertFalse(prompt.contains("--baseline-untracked-exclude"))
    assertContains(prompt, "- `a-before.tmp`")
    assertContains(prompt, "- `z-before.tmp`")
  }

  @Test
  fun `the single review pass receives immutable-base scope framing`() {
    val input = GoalSubtaskReviewInput(
      reviewBaseSha = "a".repeat(40),
      currentHeadSha = "b".repeat(40),
      trackedDelta = "scope-fingerprint:abc\n",
      ownedUntrackedPatches = "",
    )

    val prompt = composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("review"),
    ) {
      copy(
        codeReviewMode = CodeReviewExecutionMode.INLINE,
        reviewPassNumber = 1,
        goalSubtaskReviewInput = input,
      )
    }

    assertFalse(prompt.contains("scope-fingerprint:abc"))
    assertContains(prompt, "durable base `${input.reviewBaseSha}`")
    assertContains(prompt, "resolves that scope itself")
  }

  @Test
  fun `composes header briefing and output contract for every runtime phase`() {
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)

      assertContains(prompt, PROMPT_COMPOSER_ISSUE_KEY, false, "issue key for $phaseId")
      assertContains(prompt, "Phase: $phaseId", false, "phase header for $phaseId")
      assertContains(prompt, "# Feature-task-runtime phase briefing", false, "briefing body for $phaseId")
      assertContains(prompt, "feature_size: MEDIUM", false, "feature size for $phaseId")
      assertContains(prompt, "Scaling changes scope and verbosity only", false, "gate integrity for $phaseId")
      assertContains(prompt, PROMPT_COMPOSER_SPEC_REFERENCE, false, "spec reference for $phaseId")
      assertContains(prompt, "Required final output", false, "output contract for $phaseId")
      assertContains(
        prompt,
        "Do not read orchestration/contracts",
        false,
        "installed-runtime authority for $phaseId",
      )
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
}
