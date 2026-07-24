package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The audit-gap context-reuse loop exercised over the production transition topology
// (audit --gaps_found--> implement -> review -> audit), with a
// fake launcher. Mirrors the M1 review_fix matrix in FeatureTaskRuntimeRunnerTest, reusing its shared
// package-internal harness/launcher/output helpers.
class FeatureTaskRuntimeAuditGapLoopTest {
  // (a) AC1/AC2: a satisfied audit advances straight to validate; the audit_gap edge never fires.
  @Test
  fun `m2 satisfied audit advances to validate without firing the loop`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 1))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(ALL_PHASES, launched, "a satisfied audit launches the forward pipeline, never re-planning")
    assertEquals(1, launched.count { it == "audit" })
    assertEquals(1, launched.count { it == "plan" })
    assertTrue(
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .none { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" },
    )
    val status = FeatureTaskRuntimeStatusService(
      harness.recorder,
      harness.runInvariantsStore,
      harness.decomposeTerminalRecorder,
    ).status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID))
    assertEquals(true, status?.auditRepair?.firstPassConvergence)
  }

  // (b)+(e) AC2/AC3: one gaps_found iteration re-enters plan -> implement -> review -> audit then
  // advances on satisfied; the re-entered plan and implement briefings carry the failing criteria and
  // the driving gaps_found verdict.
  @Test
  fun `m2 one gaps_found iteration loops implement audit then reviews once and advances`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(2, launched.count { it == "audit" }, "initial audit + one re-audit")
    assertEquals(1, launched.count { it == "plan" }, "the original plan remains immutable")
    assertEquals(2, launched.count { it == "implement" }, "the re-implement re-enters implement once")
    // The reopened [implement, audit] span excludes review, so an audit gap never re-runs review;
    // review launches exactly once, on the tree the audit finally declared complete.
    assertEquals(1, launched.count { it == "review" }, "an audit gap never reopens review")
    val firstAudit = launched.indexOf("audit")
    val reImplement = launched.withIndex().first { (index, phase) -> phase == "implement" && index > firstAudit }.index
    assertTrue(reImplement > firstAudit, "implementation remediation runs directly after the audit gap")
    assertTrue(
      launched.indexOf("review") > launched.indexOfLast { it == "audit" },
      "review runs only after the final satisfied audit",
    )
    // (e) the re-entered implement briefing carries immutable planning context and the latest gaps.
    val briefings = harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()
    val planBriefing = requireNotNull(briefings["plan"]).briefingText
    val implementBriefing = requireNotNull(briefings["implement"]).briefingText
    assertTrue(!planBriefing.contains(AUDIT_GAP_MESSAGE))
    assertContains(implementBriefing, AUDIT_GAP_MESSAGE)
    assertContains(implementBriefing, "### from: preplan")
    assertContains(implementBriefing, "### from: plan")
    val planningRecords = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
    assertEquals(1, requireNotNull(planningRecords["preplan"]).attemptCount)
    assertEquals(1, requireNotNull(planningRecords["plan"]).attemptCount)
    assertEquals(null, planningRecords.getValue("preplan").loopId)
    assertEquals(null, planningRecords.getValue("plan").loopId)
    // (AC7) the audit_gap loop edge is recorded once with iteration 1.
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    assertEquals(listOf(1), loopEdges.mapNotNull { it.edgeIteration })
    val status = FeatureTaskRuntimeStatusService(
      harness.recorder,
      harness.runInvariantsStore,
      harness.decomposeTerminalRecorder,
    ).status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID))
    assertEquals(1, status?.auditRepair?.auditGapIterationCount)
    assertEquals(1, status?.auditRepair?.attemptedRepairItemCount)
    assertEquals(1, status?.auditRepair?.resolvedRepairItemCount)
    val repairState = requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(emptyList(), repairState.unresolvedGapLedger.unresolvedGaps)
    assertEquals(listOf("ac-002-gap-1"), repairState.priorGapDispositions.map { it.gapId })
    assertTrue(repairState.priorGapDispositions.all { it.status.name == "RESOLVED" })
  }

  @Test
  fun `audit gap validates persisted planning outputs against their phase identities`() {
    val identityCheckingValidator = object : FeatureTaskRuntimePhaseOutputValidator {
      override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
        val phaseId = requireNotNull(Regex("\\\"phase_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(phaseOutputText))
          .groupValues[1]
        require(sourceLabel == phaseId)
      }
    }
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = 2),
      validator = identityCheckingValidator,
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
  }

  @Test
  fun `m2 audit re-entry does not consume a review pass so review keeps the requested mode`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.DELEGATED),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val reviewPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { it.contains("Phase: review") }
    // Under the audit-first order an audit gap reopens only [implement, audit]. Review is never
    // re-entered, so it never burns a remediation pass and still runs in the run-selected mode.
    assertEquals(1, reviewPrompts.size)
    assertContains(reviewPrompts[0], "bill-code-review mode:delegated")
  }

  // (c) AC2: convergence on the last allowed (2nd) iteration still advances.
  @Test
  fun `m2 converges on the last allowed iteration and advances`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 3))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(3, launched.count { it == "audit" }, "initial audit + two re-audits")
    assertEquals(1, launched.count { it == "plan" }, "planning is not regenerated")
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    assertEquals(listOf(1, 2), loopEdges.mapNotNull { it.edgeIteration })
  }

  @Test
  fun `m2 audit gaps continue until a later audit satisfies the criteria`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 99))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "plan" })
    assertEquals(99, launched.count { it == "audit" })
    assertTrue(launched.any { it == "validate" })
    val auditRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["audit"])
    assertEquals("completed", auditRecord.status)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    assertEquals(98, loopEdges.maxOf { requireNotNull(it.edgeIteration) })
  }

  @Test
  fun `equivalent recurring gaps with an unchanged repository block as non progress`() {
    val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" }
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = 3),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertContains(report.blockedReason, "Audit repair made no progress")
    assertContains(report.blockedReason, "repository fingerprint is unchanged")
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "validate" })
  }

  @Test
  fun `repository changes between audits allow recurring gaps to continue`() {
    // Each audit iteration reads the fingerprint twice: once to refresh the receipt projection's
    // repository checkpoint at launch (AC-012), then once for audit-gap progress detection. Both reads
    // in an iteration observe the same repository, so the values are paired.
    val git = RecordingWorkflowGitOperations().apply {
      repositoryFingerprintSequence.addAll(
        listOf("before-repair", "before-repair", "after-repair", "after-repair", "after-repair", "after-repair"),
      )
    }
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = 3),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(6, git.repositoryFingerprintCalls)
    assertEquals(3, harness.launchedPromptPhaseOrder().count { it == "audit" })
    assertTrue(harness.launchedPromptPhaseOrder().any { it == "validate" })
  }

  @Test
  fun `cosmetic recurring repair plan mutation is non progress with an unchanged repository`() {
    var auditLaunches = 0
    val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" }
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> {
            auditLaunches += 1
            facts(
              when (auditLaunches) {
                1 -> auditGapsOutput()
                2 -> auditGapsOutput(followUp = true).replace(
                  "The missing behavior is implemented.",
                  "The missing behavior and its durable boundary are implemented.",
                )
                else -> auditSatisfiedOutput()
              },
            )
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertContains(report.blockedReason, "Audit repair made no progress")
    assertEquals(2, auditLaunches)
  }

  // AC4: a re-audit cannot close a carried gap by staying silent about it. Omitting the disposition and
  // declaring satisfied is the exact shape that would implicitly erase accepted repair work.
  @Test
  fun `a re-audit cannot implicitly satisfy a carried gap by omitting its disposition`() {
    var auditLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput(followUp = false))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertContains(blocked.blockedReason, "must disposition every durable unresolved gap exactly once")
    assertContains(blocked.blockedReason, "ac-002-gap-1")
    val repairState = requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(
      listOf("ac-002-gap-1"),
      repairState.unresolvedGapLedger.unresolvedGaps.map { it.gapId },
      "the silent audit must not have closed the carried gap",
    )
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "validate" })
  }

  // AC4: naming a later phase as the place the carried work will happen is a deferral, not a repair.
  @Test
  fun `a remediation deferring carried work to a later phase is rejected`() {
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> facts(auditGapsOutput())
          "implement" -> {
            implementLaunches += 1
            facts(
              if (implementLaunches == 1) {
                validJsonOutput(phaseId)
              } else {
                validJsonOutput(phaseId).replace(
                  "\"summary\": \"Phase produced a validated output.\"",
                  "\"summary\": \"Deferred the remaining repair to the validation phase.\"",
                )
              },
            )
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "later phase")
    val repairState = requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(listOf("ac-002-gap-1"), repairState.unresolvedGapLedger.unresolvedGaps.map { it.gapId })
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "validate" })
  }

  @Test
  fun `recurring audit keeps the cumulative ledger identity and counters`() {
    var auditLaunches = 0
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> {
            auditLaunches += 1
            facts(auditGapsOutput(followUp = auditLaunches > 1))
          }
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 3) spawnFailedFacts() else facts(validJsonOutput(phaseId))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    val repairState = requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(listOf("ac-002-gap-1"), repairState.unresolvedGapLedger.unresolvedGaps.map { it.gapId })
    assertEquals(listOf("ac-002-gap-1"), repairState.priorGapDispositions.map { it.gapId })
    assertTrue(repairState.priorGapDispositions.all { it.status.name == "RECURRING" })
    assertEquals(1, repairState.progress.recurringGapCount)
    assertEquals(0, repairState.progress.newGapCount)
    // AC5: a classification without its evidence is an unsupported claim, so the evidence the audit
    // supplied has to survive on the durable disposition, not just the status.
    val evidence = repairState.priorGapDispositions.single().evidence
    assertEquals(FeatureTaskRuntimeEvidence.Observation.RECURRENCE_VERIFIED, evidence.observation)
    assertEquals("runtime-kotlin", evidence.artifactRef)
    assertEquals("AC-002", evidence.checkRef)
  }

  @Test
  fun `a later audit cannot report a gap against a criterion the first audit durably closed`() {
    var auditLaunches = 0
    var implementLaunches = 0
    val harness = runnerHarness(
      runtimeConfig = RuntimeHarnessConfig(acceptanceCriteria = listOf("AC-1", "AC-2", "AC-3")),
      launcher = RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches > 1) auditTwoGapsOutput() else auditGapsOutput())
          }
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 3) spawnFailedFacts() else facts(validJsonOutput(phaseId))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    val repairState = requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(listOf("ac-002-gap-1"), repairState.unresolvedGapLedger.unresolvedGaps.map { it.gapId })
    assertEquals(listOf("AC-001", "AC-003"), repairState.satisfiedCriterionRefs)
    assertEquals(0, repairState.progress.recurringGapCount)
    assertEquals(1, repairState.progress.newGapCount)
  }

  @Test
  fun `an audit cannot close declared criteria by reporting an undeclared criterion`() {
    val harness = runnerHarness(
      runtimeConfig = RuntimeHarnessConfig(acceptanceCriteria = List(6) { "criterion ${it + 1}" }),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        val output = if (phaseId == "audit") {
          auditGapsOutput().replace("AC-002", "AC-999").replace("ac-002", "ac-999")
        } else {
          validJsonOutput(phaseId)
        }
        facts(output)
      },
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertContains(report.blockedReason, "not declared by this run: [AC-999]")
    assertEquals(null, harness.recorder.loadAuditRepairState(WORKFLOW_ID))
  }

  @Test
  fun `resume rejects durable closure outside the run declared criteria`() {
    var implementLaunches = 0
    val harness = runnerHarness(
      runtimeConfig = RuntimeHarnessConfig(acceptanceCriteria = List(6) { "criterion ${it + 1}" }),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when {
          phaseId == "audit" -> facts(auditGapsOutput())
          phaseId == "implement" && ++implementLaunches == 2 -> spawnFailedFacts()
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    val repairState = (artifacts["feature_task_runtime_audit_repair_state"] as Map<*, *>)
      .mapKeys { (key, _) -> key as String }
      .toMutableMap()
      .apply { this["satisfied_criterion_refs"] = listOf("AC-999") }
    artifacts["feature_task_runtime_audit_repair_state"] = repairState
    harness.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      harness.runner.run(harness.request())
    }

    assertContains(error.message.orEmpty(), "not declared by this run: [AC-999]")
  }

  @Test
  fun `a recurring gap cannot be replaced by reopening a durably closed criterion`() {
    var auditLaunches = 0
    val harness = runnerHarness(
      runtimeConfig = RuntimeHarnessConfig(acceptanceCriteria = listOf("AC-1", "AC-2", "AC-3")),
      launcher = RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches > 1) auditDroppedRecurringGapOutput() else auditGapsOutput())
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertContains(report.blockedReason, "durably closed acceptance criteria [AC-003]")
    val repairState = requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(
      listOf("ac-002-gap-1"),
      repairState.unresolvedGapLedger.unresolvedGaps.map { it.gapId },
      "the rejected audit must not have closed the still-unresolved gap in durable state",
    )
  }

  // Gap identity is derived by the runtime from durable state, never accepted from the agent, so a
  // supplied generation that skips ahead is rejected rather than silently forking the criterion's identity.
  @Test
  fun `an agent supplied gap identifier that is not the derived one is rejected`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit") facts(auditNonDerivedGapIdOutput()) else facts(validJsonOutput(phaseId))
      },
    )

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> { harness.runner.run(harness.request()) }

    assertContains(error.message.orEmpty(), "gap_id 'ac-002-gap-2' for 'AC-002'")
    assertContains(error.message.orEmpty(), "expected 'ac-002-gap-1'")
  }

  @Test
  fun `a criterion dispositioned resolved cannot be re-reported by the same handoff`() {
    val harness = secondAuditHarness(auditResolvedThenReopenedGapOutput())

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> { harness.runner.run(harness.request()) }

    assertContains(error.message.orEmpty(), "re-reported by the same handoff")
    assertContains(error.message.orEmpty(), "AC-002")
    assertEquals(
      listOf("ac-002-gap-1"),
      requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID)).unresolvedGapLedger
        .unresolvedGaps.map { it.gapId },
      "the rejected write must leave the durable gap identity intact",
    )
  }

  @Test
  fun `a first iteration recurring disposition against an empty ledger is rejected and stays resumable`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit") {
          facts(auditFirstIterationRecurringDispositionOutput())
        } else {
          facts(
            validJsonOutput(phaseId),
          )
        }
      },
    )

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> { harness.runner.run(harness.request()) }

    assertContains(error.message.orEmpty(), "never carried")
    assertEquals(
      null,
      harness.recorder.loadAuditRepairState(WORKFLOW_ID),
      "an incoherent first write must persist nothing, leaving the workflow readable and resumable",
    )
  }

  private fun secondAuditHarness(secondAuditOutput: String): RunnerHarness {
    var auditLaunches = 0
    return runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit") {
          auditLaunches += 1
          facts(if (auditLaunches > 1) secondAuditOutput else auditGapsOutput())
        } else {
          facts(validJsonOutput(phaseId))
        }
      },
    )
  }

  // (f) AC5: M1 and M2 compose with independent counters. The re-run after an audit gap passes through
  // review, while the shared pass budget prevents another review after review_fix consumed pass two.
  @Test
  fun `m2 composes with m1 keeping independent loop counters`() {
    var auditLaunches = 0
    var reviewLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          // The initial review demands one fix, then pass two approves.
          "review" -> {
            reviewLaunches += 1
            facts(reviewFindingsOutput(changesRequested = reviewLaunches % 2 == 1))
          }
          // The first audit reports a gap; the second is satisfied (one audit-gap iteration).
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput())
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    val reviewFixIterations = loopEdges.filter { it.loopId == "review_fix" }.mapNotNull { it.edgeIteration }
    val auditGapIterations = loopEdges.filter { it.loopId == "audit_gap" }.mapNotNull { it.edgeIteration }
    // The audit-gap counter is independent and reached 1; review-fix consumed the only later review.
    assertEquals(listOf(1), auditGapIterations)
    assertTrue(reviewFixIterations.all { it == 1 })
    assertEquals(1, reviewFixIterations.size, "the shared two-pass budget prevents a third review")
    assertEquals(2, harness.launchedPromptPhaseOrder().count { it == "review" }, "review-fix consumed pass two")
  }

  @Test
  fun `m2 finished telemetry reflects the audit-gap iteration count`() {
    val looped = telemetryRunnerHarness(launcher = auditGapLauncher(convergeOnAudit = 3))
    looped.runner.run(looped.request)
    val loopedFinished = looped.lifecycle.finishedRecords.single()
    assertEquals(2, loopedFinished.auditGapIterationCount, "two audit-gap iterations are reflected in telemetry")
    assertEquals(false, loopedFinished.auditFirstPassConvergence)
    assertEquals(0, loopedFinished.auditRecurringGapCount)
    assertEquals(2, loopedFinished.auditAttemptedRepairItemCount)
    assertEquals(2, loopedFinished.auditResolvedRepairItemCount)

    val clean = telemetryRunnerHarness(launcher = auditGapLauncher(convergeOnAudit = 1))
    clean.runner.run(clean.request)
    val cleanFinished = clean.lifecycle.finishedRecords.single()
    assertEquals(0, cleanFinished.auditGapIterationCount)
    assertEquals(true, cleanFinished.auditFirstPassConvergence)
  }

  // (l) AC17 under the audit-first order: the audit_gap loop runs entirely BEFORE review is reachable,
  // so it can never mint, reset, or replenish a review_fix edge. Once the audit finally satisfies,
  // review gets its full single-re-review allowance exactly once, and a crash inside the audit-gap
  // re-implement does not change that.
  @Test
  fun `m2 audit-gap reentry never touches the review_fix budget across a crash`() {
    var auditLaunches = 0
    var implementLaunches = 0
    var reviewLaunches = 0
    var crashOnReImplement = true
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          // Review is reachable only after the audit satisfies; its first pass demands one fix.
          "review" -> {
            reviewLaunches += 1
            facts(reviewFindingsOutput(changesRequested = reviewLaunches == 1))
          }
          // The first audit reports a gap; the second is satisfied.
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput())
          }
          // The audit-gap re-implement (the second implement launch) crashes once; it heals on resume.
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 2 && crashOnReImplement) spawnFailedFacts() else facts(validJsonOutput(phaseId))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    // Run 1: the audit fires gaps_found (audit_gap iteration 1), then the re-implement crashes. Review
    // has not been reachable at any point, so no review_fix edge can exist yet.
    val firstReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertEquals(0, reviewLaunches, "review is unreachable until the audit satisfies")
    val preGapReviewFix = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "review_fix" }
      .mapNotNull { it.edgeIteration }
    assertEquals(emptyList(), preGapReviewFix, "an audit gap cannot mint a review_fix edge")
    // Run 2 (resume): the crash heals, the audit satisfies, and review takes its single allowance.
    crashOnReImplement = false
    val resumeReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumeReport)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    val auditGapSeq = loopEdges.first { it.loopId == "audit_gap" }.sequenceNumber
    val reviewFixEdges = loopEdges.filter { it.loopId == "review_fix" }
    assertTrue(
      reviewFixEdges.all { it.sequenceNumber > auditGapSeq },
      "every review_fix edge is minted after the audit_gap loop has already closed",
    )
    assertEquals(
      2,
      harness.launcher.requests.count { it.skillRunRequest.promptOverride.orEmpty().contains("Phase: review") },
      "the durable budget prevents a third review launch",
    )
    assertTrue(
      reviewFixEdges.mapNotNull { it.edgeIteration }.all { it <= 1 },
      "review_fix never exceeds the single re-review allowance",
    )
  }
}

// The unique unmet-criterion message a gaps_found audit carries, so the implementation-remediation briefing
// and a cap-exhaustion block can be asserted to contain it.
internal const val AUDIT_GAP_MESSAGE = "AC-2 acceptance criterion is not yet implemented"

// A schema-valid audit output whose unmet_criteria drive the verdict: a non-empty array => gaps_found
// (the runtime classifies from the criteria, no top-level verdict needed), an empty array => satisfied.
internal fun auditGapsOutput(followUp: Boolean = false): String = """
  {
    "contract_version": "0.2",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "unmet_criteria": [{"acceptance_criterion_ref":"AC-002","message": "$AUDIT_GAP_MESSAGE"}],
      ${if (followUp) "\"prior_gap_dispositions\":[{\"gap_id\":\"ac-002-gap-1\",\"status\":\"recurring\",\"evidence\":{\"observation\":\"recurrence_verified\",\"artifact_ref\":\"runtime-kotlin\",\"check_ref\":\"AC-002\"}}]," else ""}
      "audit_repair_plan": {
        "contract_version":"0.2",
        "gaps":[{
          "gap_id":"ac-002-gap-1",
          "acceptance_criterion_ref":"AC-002",
          "acceptance_criterion_text":"The audit gap is repaired.",
          "failure_evidence":{"observation":"required_behavior_absent","artifact_ref":"runtime-kotlin","check_ref":"AC-001"},
          "diagnosis":"Implement and verify the missing behavior.",
          "affected_boundary":"runtime application",
          "repair_items":[{
            "repair_item_id":"ac-002-gap-1-item-1",
            "intended_outcome":"The missing behavior is implemented.",
            "implementation_actions":["Reconcile the implementation."],
            "affected_paths_or_symbols":["src/Foo.kt"],
            "required_verification":["Run the focused test."],
            "depends_on":[],
            "status":"pending"
          }]
        }]
      }
    }
  }
""".trimIndent()

internal fun auditTwoGapsOutput(): String = """
  {
    "contract_version": "0.2",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "unmet_criteria": [
        {"acceptance_criterion_ref":"AC-003","message": "$AUDIT_GAP_MESSAGE"},
        {"acceptance_criterion_ref":"AC-002","message": "$AUDIT_GAP_MESSAGE"}
      ],
      "prior_gap_dispositions":[{"gap_id":"ac-002-gap-1","status":"recurring","evidence":{"observation":"recurrence_verified","artifact_ref":"runtime-kotlin","check_ref":"AC-002"}}],
      "audit_repair_plan": {
        "contract_version":"0.2",
        "gaps":[{
          "gap_id":"ac-003-gap-1",
          "acceptance_criterion_ref":"AC-003",
          "acceptance_criterion_text":"The newly observed criterion is met.",
          "failure_evidence":{"observation":"required_behavior_absent","artifact_ref":"runtime-kotlin","check_ref":"AC-003"},
          "diagnosis":"Implement and verify the newly observed behavior.",
          "affected_boundary":"runtime application",
          "repair_items":[{
            "repair_item_id":"ac-003-gap-1-item-1",
            "intended_outcome":"The newly observed behavior is implemented.",
            "implementation_actions":["Reconcile the newly observed behavior."],
            "affected_paths_or_symbols":["src/Bar.kt"],
            "required_verification":["Run the focused test."],
            "depends_on":[],
            "status":"pending"
          }]
        },{
          "gap_id":"ac-002-gap-1",
          "acceptance_criterion_ref":"AC-002",
          "acceptance_criterion_text":"The audit gap is repaired.",
          "failure_evidence":{"observation":"required_behavior_absent","artifact_ref":"runtime-kotlin","check_ref":"AC-001"},
          "diagnosis":"Implement and verify the missing behavior.",
          "affected_boundary":"runtime application",
          "repair_items":[{
            "repair_item_id":"ac-002-gap-1-item-1",
            "intended_outcome":"The missing behavior is implemented.",
            "implementation_actions":["Reconcile the implementation."],
            "affected_paths_or_symbols":["src/Foo.kt"],
            "required_verification":["Run the focused test."],
            "depends_on":[],
            "status":"pending"
          }]
        }]
      }
    }
  }
""".trimIndent()

// A second audit that keeps ac-002-gap-1 recurring but drops it from the plan, replacing it with a new
// gap: the shape that would silently close a still-unresolved criterion if the ledger tolerated it.
internal fun auditDroppedRecurringGapOutput(): String = """
  {
    "contract_version": "0.2",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "unmet_criteria": [{"acceptance_criterion_ref":"AC-003","message": "$AUDIT_GAP_MESSAGE"}],
      "prior_gap_dispositions":[{"gap_id":"ac-002-gap-1","status":"recurring","evidence":{"observation":"recurrence_verified","artifact_ref":"runtime-kotlin","check_ref":"AC-002"}}],
      "audit_repair_plan": {
        "contract_version":"0.2",
        "gaps":[{
          "gap_id":"ac-003-gap-1",
          "acceptance_criterion_ref":"AC-003",
          "acceptance_criterion_text":"The newly observed criterion is met.",
          "failure_evidence":{"observation":"required_behavior_absent","artifact_ref":"runtime-kotlin","check_ref":"AC-003"},
          "diagnosis":"Implement and verify the newly observed behavior.",
          "affected_boundary":"runtime application",
          "repair_items":[{
            "repair_item_id":"ac-003-gap-1-item-1",
            "intended_outcome":"The newly observed behavior is implemented.",
            "implementation_actions":["Reconcile the newly observed behavior."],
            "affected_paths_or_symbols":["src/Bar.kt"],
            "required_verification":["Run the focused test."],
            "depends_on":[],
            "status":"pending"
          }]
        }]
      }
    }
  }
""".trimIndent()

// An audit supplying generation 2 for AC-002 when durable state derives generation 1 for it.
internal fun auditNonDerivedGapIdOutput(): String = auditCriterionRegenerationOutput(disposition = "")

// The resolve-then-reopen shape: the same handoff calls AC-002 resolved and immediately re-reports it
// under the next generation, which churns the identifier the non-progress detector keys on.
internal fun auditResolvedThenReopenedGapOutput(): String = auditCriterionRegenerationOutput(
  disposition = "\"prior_gap_dispositions\":[{\"gap_id\":\"ac-002-gap-1\",\"status\":\"resolved\"," +
    "\"evidence\":{\"observation\":\"resolution_verified\",\"artifact_ref\":\"runtime-kotlin\"," +
    "\"check_ref\":\"AC-002\"}}],",
)

// A first audit claiming a gap recurs when no durable ledger exists to have carried it.
internal fun auditFirstIterationRecurringDispositionOutput(): String = auditGapsOutput().replace(
  "\"audit_repair_plan\"",
  "\"prior_gap_dispositions\":[{\"gap_id\":\"ac-002-gap-1\",\"status\":\"recurring\"," +
    "\"evidence\":{\"observation\":\"recurrence_verified\",\"artifact_ref\":\"runtime-kotlin\"," +
    "\"check_ref\":\"AC-002\"}}],\"audit_repair_plan\"",
)

private fun auditCriterionRegenerationOutput(disposition: String): String = """
  {
    "contract_version": "0.2",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "unmet_criteria": [{"acceptance_criterion_ref":"AC-002","message": "$AUDIT_GAP_MESSAGE"}],
      $disposition
      "audit_repair_plan": {
        "contract_version":"0.2",
        "gaps":[{
          "gap_id":"ac-002-gap-2",
          "acceptance_criterion_ref":"AC-002",
          "acceptance_criterion_text":"The audit gap is repaired.",
          "failure_evidence":{"observation":"required_behavior_absent","artifact_ref":"runtime-kotlin","check_ref":"AC-001"},
          "diagnosis":"Implement and verify the missing behavior.",
          "affected_boundary":"runtime application",
          "repair_items":[{
            "repair_item_id":"ac-002-gap-2-item-1",
            "intended_outcome":"The missing behavior is implemented.",
            "implementation_actions":["Reconcile the implementation."],
            "affected_paths_or_symbols":["src/Foo.kt"],
            "required_verification":["Run the focused test."],
            "depends_on":[],
            "status":"pending"
          }]
        }]
      }
    }
  }
""".trimIndent()

internal fun auditSatisfiedOutput(followUp: Boolean = true): String = """
  {
    "contract_version": "0.2",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Every acceptance criterion is met.",
    "verdict": "satisfied",
    "produced_outputs": {
      "unmet_criteria": []${if (followUp) ",\"prior_gap_dispositions\":[{\"gap_id\":\"ac-002-gap-1\",\"status\":\"resolved\",\"evidence\":{\"observation\":\"resolution_verified\",\"artifact_ref\":\"runtime-kotlin\",\"check_ref\":\"AC-002\"}}]" else ""}
    }
  }
""".trimIndent()

// The real M2 audit_gap launcher: audit returns gaps_found until [convergeOnAudit] (1-based audit
// launch index at which it first reports satisfied). Every other
// phase returns its schema-valid reconciled output (review carries an empty findings array so its own
// gate passes and the re-run advances through review without a review_fix detour).
internal fun auditGapLauncher(convergeOnAudit: Int): RuntimeRecordingLauncher {
  var auditLaunches = 0
  return RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    if (phaseId == "audit") {
      auditLaunches += 1
      facts(
        if (auditLaunches < convergeOnAudit) {
          auditGapsOutput(followUp = auditLaunches > 1)
        } else {
          auditSatisfiedOutput(followUp = auditLaunches > 1)
        },
      )
    } else {
      facts(validJsonOutput(phaseId))
    }
  }
}
