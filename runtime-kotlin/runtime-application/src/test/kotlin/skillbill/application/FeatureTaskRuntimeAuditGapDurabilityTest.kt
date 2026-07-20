package skillbill.application

import skillbill.application.featuretask.auditRepairStateToWire
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditGapDurabilityTest {
  // (g) AC4: the re-entered implement is idempotent — a re-implement that omits the reconciliation
  // report blocks loudly on the reconciliation gate rather than silently double-applying.
  @Test
  fun `m2 re-implement without a reconciliation report blocks on the idempotency gate`() {
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> facts(auditGapsOutput())
          "implement" -> {
            implementLaunches += 1
            // The first implement reconciles; the audit-gap re-implement omits reconciled_state.
            if (implementLaunches == 1) {
              facts(validJsonOutput(phaseId))
            } else {
              facts(
                """{"contract_version":"0.2","phase_id":"implement","status":"completed",""" +
                  """"summary":"re-impl","produced_outputs":{"changed_files":["src/Foo.kt"]}}""",
              )
            }
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "reconcil")
  }

  @Test
  fun `audit remediation reports the exact item and missing evidence field`() {
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> facts(auditGapsOutput())
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 1) {
              facts(validJsonOutput(phaseId))
            } else {
              facts(
                validJsonOutput(phaseId).replace(
                  "\"executed_verification\":[\"Focused test passed.\"]",
                  "\"executed_verification\":[]",
                ),
              )
            }
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertContains(blocked.blockedReason, "ac-002-gap-1-item-1")
    assertContains(blocked.blockedReason, "executed_verification")
  }

  @Test
  fun `audit remediation preserves the decoder reason for malformed result evidence`() {
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> facts(auditGapsOutput())
          "implement" -> {
            implementLaunches += 1
            val output = validJsonOutput(phaseId)
            facts(
              if (implementLaunches == 1) {
                output
              } else {
                output.replace(
                  "\"observation\":\"fix_verified\"",
                  "\"observation\":\"fixed\"",
                )
              },
            )
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertContains(blocked.blockedReason, "ac-002-gap-1-item-1")
    assertContains(blocked.blockedReason, "result_evidence.observation")
    assertContains(blocked.blockedReason, "unauthorized evidence observation 'fixed'")
  }

  // (h) AC4: a crash mid-loopback (the audit-gap re-implement spawn-fails) resumes the unfinished
  // reentry span without reusing the audit verdict that caused it, then converges.
  @Test
  fun `m2 crash during the re-implement resumes with the loop context preserved and converges`() {
    var implementLaunches = 0
    var auditLaunches = 0
    var crashOnReImplement = true
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput())
          }
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 2 && crashOnReImplement) spawnFailedFacts() else facts(validJsonOutput(phaseId))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    // Run 1: the gaps_found audit fires the edge (iteration 1) -> implementation remediation crashes.
    val firstReport = harness.runner.run(harness.request())
    val firstBlocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertEquals("implement", firstBlocked.lastIncompletePhase)
    // The original plan stays untouched; the implement destination and durable ledger carry iteration 1.
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals(null, planRecord.loopId)
    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("audit_gap", implementRecord.loopId)
    assertEquals(1, implementRecord.edgeIteration)
    val initialRepairState = requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(listOf("ac-002-gap-1"), initialRepairState.unresolvedGapLedger.unresolvedGaps.map { it.gapId })
    assertEquals(1, initialRepairState.progress.newGapCount)
    assertEquals(0, initialRepairState.progress.recurringGapCount)

    // Run 2 (resume): the crash heals; review and audit from before edge 1 are stale and must rerun.
    // The satisfied re-audit completes edge 1 without minting edge 2.
    crashOnReImplement = false
    val resumeReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumeReport)
    val edgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
      .mapNotNull { it.edgeIteration }
    assertEquals(
      listOf(1),
      edgeIterations,
      "resume finishes the in-flight edge instead of reusing its stale driving verdict",
    )
    assertEquals(2, auditLaunches, "the original audit and the resumed re-audit both ran")
    assertEquals(2, harness.launchedPromptPhaseOrder().count { it == "review" })
  }

  @Test
  fun `a crash with one repair item still in flight resumes on the exact persisted plan`() {
    val repositoryRoot = createTempDirectory("skill-bill-mid-item-repository-")
    val repositoryEffects = repositoryRoot.resolve("repair-effects.txt")
    fun landedRepairItems(): List<String> =
      if (Files.exists(repositoryEffects)) repositoryEffects.readLines() else emptyList()
    fun applyRepairItem(itemId: String) {
      check(itemId !in landedRepairItems()) { "$itemId produced a duplicate repository effect" }
      Files.writeString(
        repositoryEffects,
        "$itemId\n",
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND,
      )
    }
    fun requireLandedItemOne() {
      check("ac-002-gap-1-item-1" in landedRepairItems()) {
        "already_satisfied is valid only when the restarted launcher observes item 1 in repository state"
      }
    }
    assertFailsWith<IllegalStateException> { requireLandedItemOne() }
    val workflowRepository = InMemoryRuntimeWorkflowRepository()
    val crashedHarness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        val phaseId = phaseIdFromPrompt(prompt)
        when (phaseId) {
          "audit" -> facts(auditTwoItemGapOutput())
          "implement" -> {
            if (prompt.contains("audit_remediation_execution_rules:")) {
              applyRepairItem("ac-002-gap-1-item-1")
              spawnFailedFacts()
            } else {
              facts(validJsonOutput(phaseId))
            }
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
      repository = workflowRepository,
    )

    val firstBlocked =
      assertIs<FeatureTaskRuntimeRunReport.Blocked>(crashedHarness.runner.run(crashedHarness.request()))
    assertEquals("implement", firstBlocked.lastIncompletePhase)
    val planBeforeResume = requireNotNull(crashedHarness.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(
      listOf("ac-002-gap-1-item-1", "ac-002-gap-1-item-2"),
      planBeforeResume.acceptedPlans.last().gaps.flatMap { gap -> gap.repairItems.map { it.repairItemId } },
      "both carried identifiers are durable before the crash",
    )
    assertEquals(listOf("ac-002-gap-1-item-1"), landedRepairItems())
    assertEquals(listOf("ac-002-gap-1"), planBeforeResume.unresolvedGapLedger.unresolvedGaps.map { it.gapId })
    assertTrue(crashedHarness.launchedPromptPhaseOrder().none { it == "validate" })

    val resumedLauncher = RuntimeRecordingLauncher { request ->
      val prompt = requireNotNull(request.skillRunRequest.promptOverride)
      when (val phaseId = phaseIdFromPrompt(prompt)) {
        "implement" -> {
          requireLandedItemOne()
          assertContains(prompt, "ac-002-gap-1-item-1")
          assertContains(prompt, "ac-002-gap-1-item-2")
          applyRepairItem("ac-002-gap-1-item-2")
          facts(twoItemRemediationOutput())
        }
        "audit" -> facts(auditSatisfiedOutput())
        else -> facts(validJsonOutput(phaseId))
      }
    }
    Files.delete(repositoryEffects)
    val missingEffectHarness = runnerHarness(
      launcher = resumedLauncher,
      repository = workflowRepository,
    )
    assertFailsWith<IllegalStateException> {
      missingEffectHarness.runner.run(missingEffectHarness.request())
    }
    assertTrue(missingEffectHarness.launchedPromptPhaseOrder().none { it == "validate" })
    applyRepairItem("ac-002-gap-1-item-1")

    val resumedHarness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        when (val phaseId = phaseIdFromPrompt(prompt)) {
          "implement" -> {
            requireLandedItemOne()
            applyRepairItem("ac-002-gap-1-item-2")
            facts(twoItemRemediationOutput())
          }
          "audit" -> facts(auditSatisfiedOutput())
          else -> facts(validJsonOutput(phaseId))
        }
      },
      repository = workflowRepository,
    )
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumedHarness.runner.run(resumedHarness.request()))

    val planAfterResume = requireNotNull(resumedHarness.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(
      planBeforeResume.acceptedPlans,
      planAfterResume.acceptedPlans,
      "resume reuses the persisted plan byte for byte instead of re-accepting a regenerated one",
    )
    assertEquals(
      listOf("ac-002-gap-1-item-1", "ac-002-gap-1-item-2"),
      landedRepairItems(),
      "the repository effect survives restart and each item is applied exactly once",
    )
    assertEquals(0, resumedHarness.launchedPromptPhaseOrder().count { it == "plan" }, "planning is never regenerated")
    val edgeIterations = resumedHarness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
      .mapNotNull { it.edgeIteration }
    assertEquals(listOf(1), edgeIterations, "the in-flight edge finishes instead of minting a second one")
    assertEquals(emptyList(), planAfterResume.unresolvedGapLedger.unresolvedGaps)
  }

  @Test
  fun `audit gap resume rejects a valid durable plan that differs from the phase record`() {
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> facts(auditGapsOutput())
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 2) spawnFailedFacts() else facts(validJsonOutput(phaseId))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    val durable = requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID))
    val changedPlan = durable.acceptedPlans.last().let { plan ->
      plan.copy(
        gaps = plan.gaps.map { gap -> gap.copy(failureEvidence = gap.failureEvidence.copy(checkRef = "AC-999")) },
      )
    }
    val contradictoryState = durable.copy(
      acceptedPlans = durable.acceptedPlans.dropLast(1) + changedPlan,
    )
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap().apply {
      put(FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY, auditRepairStateToWire(contradictoryState))
    }
    harness.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
    val launchesBeforeResume = harness.launchedPromptPhaseOrder().size

    val resumed = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertContains(resumed.blockedReason, "identical to the latest durable accepted plan")
    assertEquals(launchesBeforeResume, harness.launchedPromptPhaseOrder().size)
  }

  // A blocked audit attempt replaces the phase record with an empty one, so a resume that reads the
  // plan only from that record finds nothing and blocks — permanently, because every further resume
  // re-reads the same empty record. The durable repair state is the authority and still holds the
  // accepted plan, so recovery uses it.
  @Test
  fun `audit gap resume recovers the accepted plan after a blocked audit erased the phase record`() {
    var auditLaunches = 0
    var implementLaunches = 0
    var crashOnReImplement = true
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput())
          }
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 2 && crashOnReImplement) spawnFailedFacts() else facts(validJsonOutput(phaseId))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    // Run 1: the gaps_found audit persists the durable repair state and fires the edge; the
    // remediation implement then crashes, leaving the audit-gap re-entry in flight.
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    val durablePlan = requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID)).acceptedPlans.last()

    // The audit attempts that blocked afterwards replaced the phase record with an empty one, so its
    // copy of the accepted plan is gone while the durable state still holds it.
    harness.seedPhase("audit", "blocked", 4, INVOKED_AGENT, null)
    crashOnReImplement = false

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(
      durablePlan,
      requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID)).acceptedPlans.last(),
      "recovery reuses the durable accepted plan rather than minting a new one",
    )
  }

  @Test
  fun `ledger-only audit gap without durable plan blocks before relaunch`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "audit") auditSatisfiedOutput() else validJsonOutput(phaseId))
      },
    )
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 1, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedPhase("audit", "completed", 1, INVOKED_AGENT, auditGapsOutput())
    harness.seedLoopEdge("implement", "audit_gap", 1)

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertContains(report.blockedReason, "exact durably persisted audit repair plan")
    assertTrue(harness.launchedPromptPhaseOrder().isEmpty())
    assertEquals(
      listOf(1),
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
        .mapNotNull { it.edgeIteration },
    )
  }

  @Test
  fun `completed audit gap implement without durable plan blocks before review`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "audit") auditSatisfiedOutput() else validJsonOutput(phaseId))
      },
    )
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 1, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedPhase("audit", "completed", 1, INVOKED_AGENT, auditGapsOutput())
    harness.seedLoopEdge("implement", "audit_gap", 1)
    harness.seedReentryPhase(
      "implement",
      "completed",
      2,
      INVOKED_AGENT,
      validJsonOutput("implement"),
      "audit_gap",
      1,
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertContains(report.blockedReason, "exact durably persisted audit repair plan")
    assertTrue(harness.launchedPromptPhaseOrder().isEmpty())
  }

  @Test
  fun `m2 audit completion without durable repair state blocks before resume`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 99))
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 1, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedReentryPhase("audit", "completed", 2, INVOKED_AGENT, auditGapsOutput(), "audit_gap", 2)

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "durably readable and identical")
    assertTrue(harness.launchedPromptPhaseOrder().isEmpty())
  }

  @Test
  fun `legacy audit-to-plan record blocks instead of reusing overwritten planning context`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedReentryPhase("plan", "completed", 2, INVOKED_AGENT, validJsonOutput("plan"), "audit_gap", 1)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 1, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedReentryPhase("audit", "completed", 2, INVOKED_AGENT, auditGapsOutput(), "audit_gap", 1)

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "durably readable and identical")
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "preplan" || it == "plan" })
  }

  @Test
  fun `m2 formerly blocked audit resumes reconciliation and preserves the branch`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 99))
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 3, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 2, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedReentryPhase("audit", "blocked", 3, INVOKED_AGENT, auditGapsOutput(), "audit_gap", 2)
    harness.recorder.recordResolvedBranch(WORKFLOW_ID, FeatureTaskRuntimeResolvedBranch("feat/persisted-branch"))

    val report = assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals("feat/persisted-branch", report.resolvedBranch)
  }

  // (j) AC1: an audit that reports completed without ANY verification signal (no verdict, no
  // unmet_criteria array) blocks via the audit verification-signal gate rather than silently advancing.
  @Test
  fun `m2 audit without a verification signal blocks on the gate`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit") {
          facts(
            """{"contract_version":"0.2","phase_id":"audit","status":"completed",""" +
              """"summary":"Looks complete to me.","produced_outputs":{"notes":"all good"}}""",
          )
        } else {
          facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "verification signal")
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "validate" })
  }

  @Test
  fun `gaps_found rejects absent empty and malformed unmet criteria before remediation`() {
    val invalidAuditOutputs = listOf(
      invalidGapsFoundOutput("{}"),
      invalidGapsFoundOutput("{\"unmet_criteria\":[]}"),
      invalidGapsFoundOutput("{\"unmet_criteria\":[{\"message\":\"AC4\"},{}]}"),
    )
    invalidAuditOutputs.forEach { auditOutput ->
      val harness = runnerHarness(
        launcher = RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          facts(if (phaseId == "audit") auditOutput else validJsonOutput(phaseId))
        },
      )

      val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(
        harness.runner.run(harness.request()),
      )

      assertEquals("audit", blocked.lastIncompletePhase)
      assertTrue(harness.launchedPromptPhaseOrder().count { it == "implement" } == 1)
      assertTrue(
        harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
          .none { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" },
      )
    }
  }

  @Test
  fun `inferred gaps_found rejects malformed unmet criteria before remediation`() {
    val malformedInferredOutput =
      """{"contract_version":"0.2","phase_id":"audit","status":"completed","summary":"gap",""" +
        """"produced_outputs":{"unmet_criteria":[{"message":"AC4"},{}]}}"""
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "audit") malformedInferredOutput else validJsonOutput(phaseId))
      },
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit", blocked.lastIncompletePhase)
    assertTrue(
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .none { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" },
    )
  }

  @Test
  fun `audit-gap recovery rejects incompatible persisted planning output`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 1, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedReentryPhase("audit", "completed", 2, INVOKED_AGENT, auditGapsOutput(), "audit_gap", 1)

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "durably readable and identical")
    assertTrue(harness.launchedPromptPhaseOrder().isEmpty())
  }

  private fun invalidGapsFoundOutput(producedOutputs: String): String =
    """{"contract_version":"0.2","phase_id":"audit","status":"completed","verdict":"gaps_found",""" +
      """"summary":"gap","produced_outputs":$producedOutputs}"""
}

// A gaps_found audit whose single gap carries two dependency-ordered repair items, so a remediation can
// crash with the first one settled and the second still in flight.
private fun auditTwoItemGapOutput(): String = """
  {
    "contract_version": "0.2",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "unmet_criteria": [{"acceptance_criterion_ref":"AC-002","message": "$AUDIT_GAP_MESSAGE"}],
      "audit_repair_plan": {
        "contract_version":"0.2",
        "gaps":[{
          "gap_id":"ac-002-gap-1",
          "acceptance_criterion_ref":"AC-002",
          "acceptance_criterion_text":"The audit gap is repaired.",
          "failure_evidence":{"observation":"required_behavior_absent","artifact_ref":"runtime-kotlin","check_ref":"AC-002"},
          "diagnosis":"Implement and verify the missing behavior in two ordered steps.",
          "affected_boundary":"runtime application",
          "repair_items":[{
            "repair_item_id":"ac-002-gap-1-item-1",
            "intended_outcome":"The behavior is implemented.",
            "implementation_actions":["Reconcile the implementation."],
            "affected_paths_or_symbols":["src/Foo.kt"],
            "required_verification":["Run the focused test."],
            "depends_on":[],
            "status":"pending"
          },{
            "repair_item_id":"ac-002-gap-1-item-2",
            "intended_outcome":"The behavior is covered by a durable test.",
            "implementation_actions":["Add the focused regression."],
            "affected_paths_or_symbols":["src/FooTest.kt"],
            "required_verification":["Run the focused test."],
            "depends_on":["ac-002-gap-1-item-1"],
            "status":"pending"
          }]
        }]
      }
    }
  }
""".trimIndent()

// The resumed remediation: the item that landed before the crash reports already_satisfied with distinct
// repository and verification evidence, and the in-flight item reports its fix.
private fun twoItemRemediationOutput(): String = """
  {
    "contract_version": "0.2",
    "phase_id": "implement",
    "status": "completed",
    "summary": "Resumed remediation reconciled every carried repair item exactly once.",
    "produced_outputs": {
      "changed_files":["src/FooTest.kt"],
      "reconciled_state":{"reconciled":true},
      "repair_item_results":[{
        "repair_item_id":"ac-002-gap-1-item-1",
        "outcome":"already_satisfied",
        "changed_paths_or_symbols":["src/Foo.kt"],
        "executed_verification":["Focused test passed before this attempt."],
        "result_evidence":{"observation":"already_satisfied_verified","artifact_ref":"src/Foo.kt","check_ref":"AC-002"}
      },{
        "repair_item_id":"ac-002-gap-1-item-2",
        "outcome":"fixed",
        "changed_paths_or_symbols":["src/FooTest.kt"],
        "executed_verification":["Focused test passed."],
        "result_evidence":{"observation":"fix_verified","artifact_ref":"src/FooTest.kt","check_ref":"AC-002"}
      }]
    }
  }
""".trimIndent()
