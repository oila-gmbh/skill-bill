@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.application.featuretask.PhaseTaskDirectiveInputs
import skillbill.application.featuretask.phaseDeclaration
import skillbill.application.featuretask.phaseRequestedAction
import skillbill.application.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("LargeClass") // single suite over one composer; splitting would scatter the per-phase prompt contract
class FeatureTaskRuntimePhasePromptComposerTest {
  @Test
  fun `review prompt forwards selected execution mode through a parallel lane`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("review"),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )

    assertContains(prompt, "The runtime owns this review")
    assertFalse(prompt.contains("Run `bill-code-review"))
    assertFalse(prompt.contains("parallel:claude"))
  }

  @Test
  fun `initial preplan prompt excludes review mode, commit-PR, and finalization mandate text`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN),
    )

    assertContains(prompt, "projection_kind \"preplanning_digest\"")
    assertContains(prompt, "emit these fields DIRECTLY on produced_outputs", false, "preplan warns against nesting")
    assertContains(prompt, "\"rollout\": { \"flag_required\": false", false, "preplan shows rollout as an object")
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
  fun `preplan shape example itself declares selected_boundary_headings`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN),
    )

    val shapeExample = prompt.substringAfter("emit these fields DIRECTLY on produced_outputs")
      .substringAfter("```json")
      .substringBefore("```")
    assertContains(
      shapeExample,
      "\"selected_boundary_headings\": [",
      false,
      "the copyable shape example must name selected_boundary_headings, not only trailing prose",
    )
  }

  @Test
  fun `plan prompt names exactly the executable-plan required fields`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
    )

    assertContains(prompt, "projection_kind \"executable_plan\"")
    assertContains(prompt, "mode")
    assertContains(prompt, "task_id")
    assertContains(prompt, "depends_on")
    assertContains(prompt, "criterion_refs")
    assertContains(prompt, "test_obligations")
    assertContains(prompt, "validation_strategy")
    assertContains(prompt, "^[a-z][a-z0-9-]*", false, "plan shows the task_id pattern")
    assertContains(prompt, "\"task_id\": \"task-1\"", false, "plan shows a lowercase-kebab task_id example")
  }

  @Test
  fun `implement prompt names the implementation-receipt required fields`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
    )

    assertContains(prompt, "projection_kind \"implementation_receipt\"")
    assertContains(prompt, "completed_task_ids")
    assertFalse(prompt.contains("changed_paths"))
    assertContains(prompt, "tests_executed")
    assertContains(prompt, "runtime-owned")
    assertContains(prompt, "Omit them entirely")
    assertContains(
      prompt,
      "\"projection_kind\": \"implementation_receipt\"",
      false,
      "implement shows the flat receipt shape",
    )
    assertContains(prompt, "reconciliation evidence")
    assertContains(
      prompt,
      "\"deviations\": [ { \"ref\": \"task-1\", \"note\"",
      false,
      "implement shows the deviation object item shape",
    )
    assertContains(
      prompt,
      "never a free-text string",
      false,
      "implement warns against string deviations",
    )
  }

  @Test
  fun `implement_fix prompt carries the repair receipt shape and the unchanged scope prohibition`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX),
    )

    assertContains(prompt, "\"repair_receipt\": {")
    assertContains(prompt, "\"contract_version\": \"0.2\"")
    assertContains(prompt, "\"symbol\": \"Type.member\"")
    assertContains(prompt, "finding_id")
    assertContains(prompt, "Coverage matches on finding_id alone")
    assertFalse(
      prompt.contains("pre_fix_checkpoint_sha"),
      "The remediation base sha is runtime-owned and absent from the briefing, so asking for it can " +
        "only produce an unrepairable rejection loop.",
    )
    assertContains(prompt, "specialist narratives and raw review output are not")
    assertContains(prompt, "Do not re-apply the plan from scratch")
    assertContains(prompt, "repair_receipt")
    assertContains(prompt, "\"symbol\": \"Type.member\"")
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
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor(phaseId))
      assertContains(prompt, ownershipTitle, false, "ownership title for $phaseId")
      assertContains(prompt, "Only the validate phase may run the pack validation gate", false, phaseId)
      assertContains(prompt, "./gradlew check", false, phaseId)
      assertContains(prompt, "must not compile, build,", false, phaseId)
    }

    val validatePrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )
    assertFalse(
      validatePrompt.contains(ownershipTitle),
      "validate must not carry the non-validate forbid; it owns the gate",
    )
    assertContains(validatePrompt, "Invoke bill-code-check")
    assertContains(validatePrompt, "Loop until green")
    assertContains(validatePrompt, "If everything is green, stop")

    val reviewPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
    )
    assertContains(reviewPrompt, "validate owns those")

    val buildPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
    )
    assertFalse(buildPrompt.contains(ownershipTitle), "build owns compile proof, not validate gate ownership")
    assertContains(buildPrompt, "pack build_command")
  }

  @Test
  fun `validate prompt shows repository checkpoint as a fingerprint object`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )

    assertContains(prompt, "\"validation_result\": {")
    assertFalse(prompt.contains("\"repository_checkpoint\""))
    assertFalse(prompt.contains("\"gate_run_count\""))
    assertFalse(prompt.contains("\"gate_runs\""))
  }

  @Test
  fun `validate prompt batches repair from runtime finding set`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )

    assertContains(prompt, "Invoke bill-code-check")
    assertContains(prompt, "only validate agent for this step")
    assertContains(prompt, "do not spawn delegated subagents")
    assertContains(prompt, "Loop until green")
    assertContains(prompt, "Do not run `skill-bill validate`")
    assertContains(prompt, "`npx agnix`")
    assertTrue(prompt.contains("Invoke bill-code-check"))
  }

  @Test
  fun `validate prompt names the pack collect-all argv and forbids extra checklists`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      packCollectAllCommand = "./gradlew check --continue",
    )

    assertContains(prompt, "Invoke bill-code-check")
    assertContains(prompt, "`./gradlew check --continue`")
    assertContains(prompt, "Do not run `skill-bill validate`")
    assertContains(prompt, "`npx agnix`")
    assertContains(prompt, "scripts/validate_agent_configs")
    assertContains(prompt, "run exactly that")
    assertFalse(prompt.contains("Do not run `bill-code-check`"))
  }

  @Test
  fun `build prompt names pack build_command and forbids collect-all and validate checklists`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
      packBuildCommand = "./gradlew compileKotlin",
    )
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
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      agentRunValidateFallback = true,
    )

    assertContains(prompt, "Invoke bill-code-check")
    assertContains(prompt, "Validation gate degradation")
    assertContains(prompt, "declares no validation_gate")
    assertContains(prompt, "Do not suppress findings")
    assertFalse(prompt.contains("runtime owns collect-all execution"))
  }

  @Test
  fun `full and non-goal validate prompts carry bill-code-check pack gate contract`() {
    val fullPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )
    val defaultPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )

    listOf(fullPrompt, defaultPrompt).forEach { prompt ->
      assertContains(prompt, "Invoke bill-code-check")
      assertContains(prompt, "Loop until green")
      assertContains(prompt, "If everything is green, stop")
      assertContains(prompt, "Do not run `skill-bill validate`")
      assertContains(prompt, "`npx agnix`")
      assertContains(prompt, "scripts/validate_agent_configs")
      assertContains(prompt, "only validate agent for this step")
      assertContains(prompt, "do not spawn delegated subagents")
      assertContains(prompt, "Do not suppress findings")
      assertContains(prompt, "targeted repair tasks")
      assertFalse(prompt.contains("Do not run `bill-code-check`"))
      assertFalse(prompt.contains("Goal-continuation validate depth"))
      assertFalse(prompt.contains("runtime owns execution of the repository validation gate"))
    }
  }

  @Test
  fun `agent-run validate prompts allow targeted checks and forbid a second agent`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      agentRunValidateFallback = true,
    )

    assertContains(prompt, "Invoke bill-code-check")
    assertContains(prompt, "Loop until green")
    assertContains(prompt, "targeted repair tasks")
    assertContains(prompt, "only validate agent for this step")
    assertContains(prompt, "do not spawn delegated subagents")
    assertContains(prompt, "Do not run `skill-bill validate`")
    assertContains(prompt, "`npx agnix`")
    assertFalse(prompt.contains("Rerun early only when"))
    assertFalse(prompt.contains("rerun the failing command after each fix"))
    assertFalse(prompt.contains("allowed work is read, search, and source edits only"))
    assertFalse(prompt.contains("findings_open"))
  }

  @Test
  fun `FULL and default runtime-owned validate prompts name the complete finding set`() {
    val finding = skillbill.ports.validation.model.ValidationGateFinding("m", "t", "broken", "loc")
    val page = skillbill.application.featuretask.validation.model.ValidationFindingSetProjection(
      findings = listOf(finding),
    )
    val fullPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      validationGateFindings = page,
    )
    val defaultPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      validationGateFindings = page,
    )
    listOf(fullPrompt, defaultPrompt).forEach { prompt ->
      assertContains(prompt, "A prior gate run parsed these items")
      assertContains(prompt, "full open set for this repair turn")
      assertContains(prompt, "collect_all_full_gate_command")
      assertContains(prompt, "Do not run `skill-bill validate`")
      assertContains(prompt, "Do not spawn delegated subagents")
      assertContains(prompt, "Gate repair — prose only")
      assertFalse(prompt.contains("Required final output (validated schema gate)"))
    }
    val repairAction = FeatureTaskRuntimePhasePromptComposer.composeAgentPhaseInput(
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      inputs = PhaseTaskDirectiveInputs(validationGateFindings = page),
    ).requestedAction
    assertContains(repairAction, "validate repair agent")
    assertFalse(repairAction.contains("Invoke bill-code-check"))
    assertContains(repairAction, "Do not suppress findings")
  }

  @Test
  fun `runtime-owned build prompt names the complete finding set`() {
    val finding = skillbill.ports.validation.model.ValidationGateFinding("m", "t", "broken", "loc")
    val page = skillbill.application.featuretask.validation.model.ValidationFindingSetProjection(
      findings = listOf(finding),
    )
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
      validationGateFindings = page,
      packBuildCommand = "./gradlew compileKotlin",
    )
    assertContains(prompt, "## Runtime build gate findings")
    assertContains(prompt, "A prior gate run parsed these items")
    assertContains(prompt, "full open set for this repair turn")
    assertContains(prompt, "pack-declared build command")
    assertContains(prompt, "collect_all_full_gate_command")
    assertContains(prompt, "Do not spawn delegated subagents")
    assertContains(prompt, "module=m id=t location=loc message=broken")
    assertContains(prompt, "Gate repair — prose only")
    assertFalse(prompt.contains("Required final output (validated schema gate)"))
    assertFalse(prompt.contains("Required produced_outputs shape: emit a build_receipt"))
  }

  // SKILL-180: FULL validate must carry no-suppression; other phases must not.
  @Test
  fun `full validate prompt carries no-suppression clause absent from non-validate phases`() {
    val validatePrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )
    assertContains(validatePrompt, "Do not suppress findings")
    assertContains(validatePrompt, "Invoke bill-code-check")
    assertContains(validatePrompt, "Loop until green")
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
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor(phaseId))
      assertFalse(
        prompt.contains("Loop until green"),
        "phase $phaseId must not carry the validate loop clause",
      )
      assertFalse(
        prompt.contains("Invoke bill-code-check"),
        "phase $phaseId must not carry the validate gate-invocation clause",
      )
    }
  }

  @Test
  fun `review prompt preserves every durable execution mode unchanged`() {
    CodeReviewExecutionMode.entries.forEach { mode ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
        ISSUE_KEY,
        briefingFor("review"),
        codeReviewMode = mode,
      )

      assertFalse(prompt.contains("Run `bill-code-review"))
      assertFalse(prompt.contains("bill-code-review mode:${mode.wireValue}"))
    }
  }

  @Test
  fun `review prompt lists the durable baseline-untracked inventory without CLI exclude flags`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("review"),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      reviewPassNumber = 1,
      baselineUntrackedPaths = listOf("z-before.tmp", "a-before.tmp"),
    )

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
      trackedDelta = "committed staged and unstaged delta",
      ownedUntrackedPatches = "run-owned untracked delta",
    )

    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("review"),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      reviewPassNumber = 1,
      goalSubtaskReviewInput = input,
    )

    assertContains(prompt, input.trackedDelta)
    assertContains(prompt, input.ownedUntrackedPatches)
    assertContains(prompt, "durable base `${input.reviewBaseSha}` to current HEAD `${input.currentHeadSha}`")
  }

  @Test
  fun `composes header and briefing for every runtime phase without JSON output contract`() {
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.forEach { phaseId ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor(phaseId))

      assertContains(prompt, ISSUE_KEY, false, "issue key for $phaseId")
      assertContains(prompt, "Phase: $phaseId", false, "phase header for $phaseId")
      assertContains(prompt, "# Feature-task-runtime phase briefing", false, "briefing body for $phaseId")
      assertContains(prompt, "feature_size: MEDIUM", false, "feature size for $phaseId")
      assertContains(prompt, "Scaling changes scope and verbosity only", false, "gate integrity for $phaseId")
      assertContains(prompt, SPEC_REFERENCE, false, "spec reference for $phaseId")
      assertFalse(prompt.contains("Required final output"), "JSON output contract for $phaseId")
      assertFalse(prompt.contains("\"contract_version\""), "contract_version for $phaseId")
      assertFalse(prompt.contains("produced_outputs"), "produced_outputs for $phaseId")
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
    assertContains(preplanPrompt, "Return prose covering affected boundaries")
    assertContains(planPrompt, "Do not modify repository files during this phase.")
    assertContains(planPrompt, "upstream preplan digest")
    assertContains(implementPrompt, "Reconcile the repository to the intended state")
    assertTrue(
      !implementPrompt.contains("Do not modify repository files during this phase."),
      "implement must not carry the plan directive",
    )
    assertContains(implementPrompt, "Mutating-phase idempotency contract")
    assertContains(implementPrompt, "Report a bounded summary in prose")
    assertTrue(
      !planPrompt.contains("Mutating-phase idempotency contract"),
      "non-mutating plan phase must not carry the idempotency directive",
    )
    assertTrue(
      !historyPrompt.contains("Mutating-phase idempotency contract"),
      "non-mutating write_history phase must not carry the idempotency directive",
    )
    assertContains(historyPrompt, "bill-boundary-history")
    assertContains(historyPrompt, "whether history was written or skipped")
    assertContains(commitPrompt, "<<<COMMIT_SUBJECT>>>")
    assertContains(commitPrompt, "terminal success signal")
    assertContains(prPrompt, "bill-pr-description")
    assertContains(prPrompt, "create or reuse the open")
    assertContains(prPrompt, "whether a new PR was created")
  }

  @Test
  fun `test-value discipline renders for plan implement and implement_fix with six element anchors`() {
    val presentPhases = listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
    )
    presentPhases.forEach { phaseId ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor(phaseId))
      assertContains(prompt, TEST_VALUE_DISCIPLINE_TITLE, false, "title for $phaseId")
      assertContains(prompt, "name the realistic bug", false, "nameable-bug element for $phaseId")
      assertContains(prompt, "critical paths", false, "critical-path element for $phaseId")
      assertContains(
        prompt,
        "observable behavior at boundaries",
        false,
        "boundaries / no structure-coupling element for $phaseId",
      )
      assertContains(prompt, "One strong test per rule", false, "one-test-per-rule element for $phaseId")
      assertContains(
        prompt,
        "empty test_obligations list is a valid",
        false,
        "empty test_obligations guidance for $phaseId",
      )
      assertContains(
        prompt,
        "parity tests or validator-backed rules",
        false,
        "regression / governed carve-out for $phaseId",
      )
    }
  }

  @Test
  fun `test-value discipline is absent from evaluator and non-producer phases`() {
    val absentPhases = listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
    )
    absentPhases.forEach { phaseId ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor(phaseId))
      assertFalse(
        prompt.contains(TEST_VALUE_DISCIPLINE_TITLE),
        "phase $phaseId must not carry the test-value discipline section",
      )
    }
  }

  @Test
  fun `test-value discipline sits immediately after minimalism on mutating phases`() {
    listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
    ).forEach { phaseId ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor(phaseId))
      val minimalismIdx = prompt.indexOf("## Minimalism discipline")
      val testValueIdx = prompt.indexOf(TEST_VALUE_DISCIPLINE_TITLE)
      assertTrue(minimalismIdx >= 0, "minimalism present for $phaseId")
      assertTrue(testValueIdx > minimalismIdx, "test-value follows minimalism for $phaseId")
      val between = prompt.substring(minimalismIdx, testValueIdx)
      assertFalse(
        between.indexOf("\n## ", startIndex = 1) >= 0,
        "no other titled section between minimalism and test-value for $phaseId",
      )
    }

    val planPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
    )
    assertContains(planPrompt, TEST_VALUE_DISCIPLINE_TITLE)
    assertFalse(
      planPrompt.contains("## Minimalism discipline"),
      "plan must not render minimalism; test-value uses its own phase predicate",
    )

    // Neighboring titles around the insertion point keep prior relative order on non-target phases.
    val preplanPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN),
    )
    val ceremonyIdx = preplanPrompt.indexOf("## Runtime ceremony scaling")
    val briefingIdx = preplanPrompt.indexOf("# Feature-task-runtime phase briefing")
    assertTrue(ceremonyIdx >= 0 && briefingIdx > ceremonyIdx)
    assertFalse(preplanPrompt.contains(TEST_VALUE_DISCIPLINE_TITLE))
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
    // implement receives the bounded executable-plan projection, not plan's complete envelope.
    assertContains(prompt, "Fixture task.")
    assertTrue(!prompt.contains("Phase produced a validated output."))
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
  fun `commit_push prompt carries the feature-spec exclusion directive`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("commit_push"))

    assertContains(prompt, "Feature-spec commit exclusion")
    assertContains(prompt, ".feature-specs/$ISSUE_KEY-")
    assertContains(prompt, "decomposition-manifest.yaml")
    assertContains(prompt, "Never list any `.feature-specs/`")
    assertContains(prompt, "Never amend, reset, or restage a commit this runtime does not own")
    assertTrue(
      !prompt.contains("do not add, amend,"),
      "the blanket amend prohibition is replaced by a scope bound to runtime-owned commits",
    )
    assertTrue(!prompt.contains("The committed tree must contain no feature spec"))
  }

  @Test
  fun `feature-spec exclusion directive is absent on non-commit phases`() {
    val implementPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("implement"),
    )

    assertTrue(!implementPrompt.contains("Feature-spec commit exclusion"))
  }

  @Test
  fun `a blank issue key loud-fails`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimePhasePromptComposer.compose(" ", briefingFor("plan"))
    }
  }

  @Test
  fun `verifying phases name prose verdict tokens in requestedAction`() {
    val reviewAction = phaseRequestedAction(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
    val auditAction = phaseRequestedAction(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)

    assertContains(reviewAction, "approved", false, "review names approved")
    assertContains(reviewAction, "changes_requested", false, "review names changes_requested")
    assertContains(auditAction, "satisfied", false, "audit names satisfied")
    assertContains(auditAction, "gaps_found", false, "audit names gaps_found")
    assertFalse(reviewAction.contains("produced_outputs"))
    assertFalse(auditAction.contains("produced_outputs"))
  }

  @Test
  fun `audit prompt scopes gaps to production and routes tests to validation`() {
    val auditPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("audit"))

    assertContains(auditPrompt, "TEST EXCLUSION", false, "audit makes the test-only exclusion explicit")
    assertContains(auditPrompt, "NEVER unmet criteria", false, "audit rejects test-only findings")
    assertAuditPromptNamesSignal(auditPrompt, "Validation owns test execution", "the test routing")
    assertAuditPromptNamesSignal(
      auditPrompt,
      "production behavior or production",
      "the production scope of an unmet criterion",
    )
  }

  @Test
  fun `audit prompt requires a complete blast-radius-aware fix plan in each gap note`() {
    val auditPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("audit"))

    assertContains(auditPrompt, "fix plan", false, "each gap note should guide the repair")
    assertContains(auditPrompt, "blast radius", false, "audit should consider blast radius before naming a gap")
    assertContains(auditPrompt, "free-form note prose", false, "plan quality is guidance, not a wire template")
    assertContains(auditPrompt, "does not block on note length", false, "audit schema is recommendation only")
    assertContains(
      FeatureTaskRuntimePhasePromptComposer.compose(
        ISSUE_KEY,
        briefingFor("implement", unmetCriterionRefs = listOf("AC-003: missing DI binding")),
      ),
      "Follow that plan completely",
      false,
      "implement remediation should prefer the audit's plan",
    )
  }

  private fun assertAuditPromptNamesSignal(auditPrompt: String, fragment: String, what: String) {
    assertContains(auditPrompt, fragment, false, "audit names $what")
  }

  @Test
  fun `review prompt delegates imported review facts to the runtime`() {
    val reviewPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("review"))

    assertContains(reviewPrompt, "runtime imports review findings")
    assertFalse(reviewPrompt.contains("review_run_id"))
    assertFalse(reviewPrompt.contains("commit_focused_accounting"))
    assertFalse(reviewPrompt.contains("echo imported review facts"))
    assertFalse(
      FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("audit"))
        .contains("commit_focused_accounting"),
      "only the review phase produces the accounting record",
    )
  }

  @Test
  fun `carried disposition observation enumeration failure names the closed token set`() {
    val retry = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("audit", unmetCriterionRefs = listOf("AC-001")),
      priorSettlementFailure =
      "produced_outputs.carried_gap_dispositions[0].evidence.observation: does not have a value in the " +
        "enumeration [\"resolution_verified\", \"recurrence_verified\"]",
    )
    assertContains(retry, "closed token", false, "the correction must say observation is not prose")
    assertContains(retry, "resolution_verified or recurrence_verified", false, "the closed set must be named")
    assertContains(retry, "Put the paragraph in summary only", false, "prose is redirected off observation")
  }

  @Test
  fun `audit prompt separates blocking gaps from non blocking findings`() {
    val auditPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("audit"))

    assertContains(auditPrompt, "non_blocking_findings", false, "minor and nit findings have their own sink")
    assertContains(auditPrompt, "non_blocking_findings", false, "audit preserves minor and nit findings")
    assertContains(auditPrompt, "NEVER trigger gaps_found", false, "non-blocking findings cannot reopen implementation")
    assertContains(
      auditPrompt,
      "\"acceptance_criterion_ref\":\"AC-004\"",
      false,
      "audit spells out the non-blocking finding shape instead of leaving it to the gap example",
    )
  }

  @Test
  fun `non-verifying phases carry no verifying-signal addendum`() {
    listOf("preplan", "plan", "implement", "validate", "write_history", "commit_push", "pr").forEach { phaseId ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor(phaseId))
      assertTrue(!prompt.contains("VERIFYING phase"), "$phaseId must not carry the verifying-signal addendum")
    }
  }

  @Test
  fun `a prior settlement failure is surfaced as a corrective directive on retry`() {
    // F-003: the retry directive is phase-independent, so cover both verifying phases to guard against a
    // phase-conditional regression in its placement relative to the verifying-signal addendum.
    val reason = "Audit phase reported 'completed' without a verification signal"

    listOf("review", "audit").forEach { phaseId ->
      val firstAttempt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor(phaseId))
      val retry = FeatureTaskRuntimePhasePromptComposer.compose(
        ISSUE_KEY,
        briefingFor(phaseId),
        priorSettlementFailure = reason,
      )

      assertTrue(!firstAttempt.contains("could not settle"), "$phaseId first attempt: no correction")
      assertContains(retry, "Previous attempt could not settle", false, "$phaseId retry: rejection")
      assertContains(retry, reason, false, "$phaseId retry carries the validator's reason verbatim")
    }
  }

  @Test
  fun `a retryable terminal envelope is prompted to retry, not told it was rejected`() {
    // AC-004: the envelope validated. Rendering it through the schema-correction directive told its
    // author the output was rejected and had to be re-emitted, describing an event that never happened.
    val reason = "Implement phase reported blocked: the target module does not compile on this branch."

    val retry = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("implement"),
      priorTerminalFailure = reason,
    )

    assertContains(retry, "reported a retryable block", false, "terminal retry names its own kind")
    assertContains(retry, reason, false, "terminal retry carries the reported reason verbatim")
    assertTrue(
      !retry.contains("could not settle"),
      "a schema-valid terminal envelope must never receive the schema-correction directive",
    )
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
  fun `audit remediation names the criteria it must implement in this invocation`() {
    val briefing = briefingFor("implement").copy(
      unresolvedAuditGapIds = listOf("AC-004", "AC-005"),
    )

    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefing)

    assertContains(prompt, "AUDIT-GAP REMEDIATION")
    assertContains(prompt, "AC-004")
    assertContains(prompt, "AC-005")
    assertContains(prompt, "ordinary implementation receipt")
    assertTrue(
      !prompt.contains("repair_item_results"),
      "the remediation round owes no per-item results",
    )
  }

  @Test
  fun `audit_gap implement re-entry renders sticky-priority directive but forward implement does not`() {
    val memory = FeatureTaskRuntimePriorGapMemory(
      round = 2,
      priorUnmetCriteria = listOf("AC-002: $AUDIT_GAP_MESSAGE"),
      lastImplementClaims = listOf("AC-001"),
      stickyIds = listOf("AC-002"),
    )
    val remediation = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("implement", priorGapMemory = memory),
    )
    assertContains(remediation, "Prior-gap memory — sticky criteria take priority")
    assertContains(remediation, "AC-002")
    assertContains(remediation, "Never narrow scope to only the")
    assertContains(remediation, "closing every entry in the audit_gaps list")

    val forward = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("implement"),
    )
    assertTrue(!forward.contains("Prior-gap memory — sticky criteria take priority"))
    assertTrue(!forward.contains("prior_gap_memory"))
  }

  @Test
  fun `audit after remediation requires sticky re-justification while first audit keeps blank-slate wording`() {
    val memory = FeatureTaskRuntimePriorGapMemory(
      round = 2,
      priorUnmetCriteria = listOf("AC-002: $AUDIT_GAP_MESSAGE"),
      lastImplementClaims = listOf("AC-001"),
      stickyIds = listOf("AC-002"),
    )
    val remediation = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("audit", priorGapMemory = memory),
    )
    assertContains(remediation, "explicit re-justification")
    assertContains(remediation, "Sticky ids")
    assertContains(remediation, "AC-002")
    assertTrue(!remediation.contains("nothing to carry forward"), "blank-slate wording must be subordinated")
    assertTrue(!remediation.contains("never need to account for what an earlier audit said"))

    val firstAudit = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("audit"))
    assertContains(firstAudit, "nothing to carry forward")
    assertContains(firstAudit, "never need to account for what an earlier audit said")
  }

  @Test
  fun `verifying-phase requestedAction stays prose-only and omits runtime-owned review facts`() {
    val reviewAction = phaseRequestedAction(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
    val auditAction = phaseRequestedAction(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)

    assertContains(reviewAction, "approved", false, "review names approved")
    assertFalse(reviewAction.contains("produced_outputs"))
    assertContains(auditAction, "gaps_found", false, "audit names gaps_found")
    assertFalse(auditAction.contains("produced_outputs"))
  }

  @Test
  fun `preplan plan and implement prompts omit produced_outputs JSON examples`() {
    projectionExampleCases().forEach { (phaseId, _, briefing) ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefing)
      val action = phaseRequestedAction(phaseId)
      assertFalse(prompt.contains("Required final output"), "compose prompt for $phaseId")
      assertFalse(prompt.contains("```json"), "JSON example fence for $phaseId")
      assertFalse(action.contains("produced_outputs"), "requestedAction for $phaseId")
      assertFalse(action.contains("contract_version"), "requestedAction for $phaseId")
    }
  }

  @Test
  fun `every collection field a variant declares is populated in its prompt example`() {
    projectionExampleCases().forEach { (phaseId, _, briefing) ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefing)
      assertFalse(prompt.contains("```json"), "prose-only prompts must not embed JSON examples for $phaseId")
    }
  }

  // SKILL-150 subtask 1: a semantically incomplete receipt and a schema-invalid one are different
  // failures and must reach the agent as different directives.
  @Test
  fun `an incomplete-work retry carries the continuation directive and not the schema-correction directive`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("implement"),
      implementationContinuation = FeatureTaskRuntimeImplementationContinuation(
        phaseId = "implement",
        segmentNumber = 2,
        completedTaskIds = listOf("task-1"),
        openObligationIds = listOf("task-2"),
        obligationNoun = "plan task",
        changedPaths = listOf("src/Foo.kt"),
        deviations = emptyList(),
        unresolvedItems = emptyList(),
        reconciliationEvidence = null,
        repositoryCheckpoint = null,
        failureDisposition = null,
      ),
    )

    assertContains(prompt, "segment 2")
    assertContains(prompt, "task-2")
    assertTrue(
      !prompt.contains("could not settle"),
      "an honest partial receipt is not a schema failure",
    )
  }
}

private const val ISSUE_KEY = "SKILL-66"
private const val TEST_VALUE_DISCIPLINE_TITLE = "## Test-value discipline"
private const val SPEC_REFERENCE = ".feature-specs/SKILL-66/spec.md"

// preplan, plan, and implement feed bounded planning projections, so their seeded outputs are full
// envelopes carrying the declared projection body rather than bare produced_outputs fragments.
private val PREPLAN_OUTPUT = projectionEnvelope("preplan", PlanningProjectionFixtures.PREPLAN_DIGEST)
private val PLAN_OUTPUT = projectionEnvelope("plan", PlanningProjectionFixtures.EXECUTABLE_PLAN)

private fun projectionEnvelope(phaseId: String, producedOutputs: String): String =
  """{"contract_version":"0.4","phase_id":"$phaseId","status":"completed",""" +
    """"summary":"Phase produced a validated output.","produced_outputs":$producedOutputs}"""

private fun projectionExampleCases() = listOf(
  Triple(preplanPhase, FeatureTaskRuntimeProjectionKind.PREPLANNING_DIGEST, briefingFor(preplanPhase)),
  Triple(planPhase, FeatureTaskRuntimeProjectionKind.EXECUTABLE_PLAN, briefingFor(planPhase)),
  Triple(implementPhase, receiptKind, briefingFor(implementPhase)),
  Triple(
    implementPhase,
    receiptKind,
    briefingFor(implementPhase).copy(unresolvedAuditGapIds = listOf("AC-004")),
  ),
)

private val preplanPhase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
private val planPhase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
private val implementPhase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
private val receiptKind = FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT

private fun briefingFor(
  phaseId: String,
  featureSize: FeatureTaskRuntimeFeatureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
  unmetCriterionRefs: List<String> = emptyList(),
  validationDepth: ValidationDepth = ValidationDepth.DEFAULT,
  priorGapMemory: FeatureTaskRuntimePriorGapMemory? = null,
): FeatureTaskRuntimePhaseLaunchBriefing {
  val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "fixture-checkpoint-1")
  return FeatureTaskRuntimePhaseBriefingAssembler.assemble(
    FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = phaseDeclaration(phaseId, featureSize),
      runInvariants = FeatureTaskRuntimeRunInvariants(
        specReference = SPEC_REFERENCE,
        featureSize = featureSize,
        acceptanceCriteria = listOf("AC-1"),
        mandatesAndOverrides = emptyList(),
      ),
      recordedOutputs = listOf(
        FeatureTaskRuntimePhaseOutput("preplan", 1, PREPLAN_OUTPUT),
        FeatureTaskRuntimePhaseOutput("plan", 1, PLAN_OUTPUT),
        FeatureTaskRuntimePhaseOutput("implement", 1, IMPLEMENT_OUTPUT),
        FeatureTaskRuntimePhaseOutput("audit", 1, validJsonOutput("audit")),
        FeatureTaskRuntimePhaseOutput("review", 1, validJsonOutput("review")),
        verifyFindingsPhaseOutput(),
        FeatureTaskRuntimePhaseOutput("validate", 1, validJsonOutput("validate")),
        FeatureTaskRuntimePhaseOutput("write_history", 1, validJsonOutput("write_history")),
        FeatureTaskRuntimePhaseOutput("commit_push", 1, FINALISED_COMMIT_PUSH_OUTPUT),
      ),
      reentryGapCriteria = unmetCriterionRefs,
      // audit's implementation-receipt edge refreshes from a resolved checkpoint (AC-012).
      repositoryCheckpoint = checkpoint,
      expectedRepositoryCheckpoint = checkpoint,
      validationDepth = validationDepth,
      priorGapMemory = priorGapMemory,
    ),
  )
}
