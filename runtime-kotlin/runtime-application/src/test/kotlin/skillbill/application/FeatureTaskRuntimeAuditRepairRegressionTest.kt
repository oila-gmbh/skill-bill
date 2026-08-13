package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Regressions for the audit-repair gates: a blocked remediation reporting a strict subset of its
// carried items, rejected-output diagnosability, repair-item identifier canonicalization, and the
// non-progress and counter-durability behavior of the audit_gap loop.
class FeatureTaskRuntimeAuditRepairRegressionTest {
  @Test
  fun `SKILL-128 broad gaps persist one complete plan and reject production-only repair`() {
    var auditLaunches = 0
    var implementLaunches = 0
    val harness = skill128Harness(
      nextAuditLaunch = { ++auditLaunches },
      nextImplementLaunch = { ++implementLaunches },
      allowExhaustiveRepair = { implementLaunches >= 5 },
    )

    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())

    val implementPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "implement" }
    assertTrue(
      implementPrompts.any { it.contains("audit-repair-result") },
      "production-only repair must be named on the implement retry prompt",
    )

    val repairState = requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(1, repairState.acceptedPlans.size)
    val plan = repairState.acceptedPlans.single()
    assertEquals(listOf("AC-001", "AC-002", "AC-003"), plan.gaps.map { it.acceptanceCriterionRef })
    assertEquals(
      listOf("ac-001-gap-1-item-1", "ac-002-gap-1-item-1", "ac-003-gap-1-item-1"),
      plan.gaps.flatMap { gap -> gap.repairItems.map { it.repairItemId } },
    )
    assertEquals(5, implementLaunches, "three partial attempts precede one exhaustive repair")
    assertEquals(2, auditLaunches, "completion requires a following audit after exhaustive remediation")
    assertTrue(
      harness.launchedPromptPhaseOrder().indexOf("validate") >
        harness.launchedPromptPhaseOrder().lastIndexOf("audit"),
    )
  }

  // A blocked remediation is allowed to report fewer results than the carried plan, so the dependency
  // check must tolerate an id it never saw. Reading the absent id out of the actual-order map threw an
  // uncaught NoSuchElementException that killed the run with no durable blocked record.
  @Test
  fun `blocked remediation with no repair item results blocks durably instead of throwing`() {
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> facts(auditRepairPlanOutput(criterionRef = "AC-002", itemCount = 2, dependsChain = true))
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 1) {
              facts(validJsonOutput(phaseId))
            } else {
              facts(
                blockedRemediationOutput(
                  gapId = "ac-002-gap-1",
                  repairItemId = "ac-002-gap-1-item-1",
                  deferredItemIds = listOf("ac-002-gap-1-item-1", "ac-002-gap-1-item-2"),
                ),
              )
            }
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("implement", blocked.lastIncompletePhase)
    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("blocked", implementRecord.status)
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "validate" })
  }

  @Test
  fun `a completed remediation carrying structured deferred work is rejected`() {
    var auditLaunches = 0
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> facts(if (++auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput())
          "implement" -> {
            implementLaunches += 1
            facts(
              if (implementLaunches == 2) {
                validJsonOutput(phaseId).replace(
                  "\"deferred_repair_item_ids\":[]",
                  "\"deferred_repair_item_ids\":[\"ac-002-gap-1-item-1\"]",
                )
              } else {
                validJsonOutput(phaseId)
              },
            )
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    val implementPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "implement" }
    assertTrue(
      implementPrompts.any { it.contains("audit-repair-result") },
      "structured deferred work on a completed remediation must be named on the retry prompt",
    )
    val diagnostic = harness.io.database.rejectedDiagnostics().last().metadata
    assertEquals("audit_repair.completed.deferred_work", diagnostic.rule)
  }

  @Test
  fun `a completed remediation cannot also name an unresolvable repair`() {
    var auditLaunches = 0
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> facts(if (++auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput())
          "implement" -> {
            implementLaunches += 1
            val output = validJsonOutput(phaseId)
            facts(
              if (implementLaunches == 2) {
                output.replace(
                  "\"deferred_repair_item_ids\":[]",
                  """
                    "unresolvable_repair":{
                      "gap_id":"ac-002-gap-1",
                      "repair_item_id":"ac-002-gap-1-item-1",
                      "evidence":{
                        "observation":"verification_failed",
                        "artifact_ref":"src/Foo.kt",
                        "check_ref":"AC-002"
                      }
                    },
                    "deferred_repair_item_ids":[]
                  """.trimIndent(),
                )
              } else {
                output
              },
            )
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    val diagnostic = harness.io.database.rejectedDiagnostics().last().metadata
    assertEquals("audit_repair.completed.unresolvable_repair", diagnostic.rule)
    assertEquals("/produced_outputs/unresolvable_repair", diagnostic.path)
  }

  @Test
  fun `blocked repair consistency failures retain stable rules and exact paths`() {
    invalidBlockedRepairCases().forEach { invalid ->
      var auditLaunches = 0
      var implementLaunches = 0
      val harness = runnerHarness(
        launcher = RuntimeRecordingLauncher { request ->
          when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
            "audit" -> facts(if (++auditLaunches < 2) auditRepairPlanOutput(criterionRef = "AC-002", itemCount = 1) else auditSatisfiedOutput())
            "implement" -> facts(
              if (++implementLaunches == 2) {
                blockedRemediationOutput(
                  gapId = "ac-002-gap-1",
                  repairItemId = "ac-002-gap-1-item-1",
                  unresolvableRepair = invalid.value,
                )
              } else {
                validJsonOutput(phaseId)
              },
            )
            else -> facts(validJsonOutput(phaseId))
          }
        },
      )

      assertIs<FeatureTaskRuntimeRunReport.Completed>(
        harness.runner.run(harness.request()),
        invalid.name,
      )
      val diagnostic = harness.io.database.rejectedDiagnostics().last().metadata
      assertEquals(invalid.rule, diagnostic.rule, invalid.name)
      assertEquals(invalid.path, diagnostic.path, invalid.name)
    }
  }

  // A contract rejection whose fix-loop budget runs out used to persist a null output artifact, leaving
  // no evidence of what the agent actually emitted.
  @Test
  fun `rejected phase output is persisted bounded on the durable blocked record`() {
    val marker = "REGRESSION-MARKER-REJECTED-PAYLOAD"
    val padding = "p".repeat(30_000)
    var auditLaunches = 0
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> facts(if (++auditLaunches < 2) auditRepairPlanOutput(criterionRef = "AC-002", itemCount = 1) else auditSatisfiedOutput())
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 2) {
              facts(
                remediationResultsOutput(
                  repairItemIds = listOf("ac-002-gap-1-item-9"),
                  summary = "Remediation echoed an unknown identifier. $marker",
                  extraProducedOutputs = ""","notes":"$padding"""",
                ),
              )
            } else {
              facts(validJsonOutput(phaseId))
            }
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals(null, implementRecord.rejectedOutput)
    assertEquals(false, implementRecord.toArtifactMap().toString().contains(marker))
    assertTrue(harness.io.database.rejectedDiagnostics().isNotEmpty())
  }

  // The audit-repair-plan schema permits an uppercase AC- prefix and plan ingest lowercases it, so a
  // remediation echoing the identifier as the criterion spells it must still satisfy set equality.
  @Test
  fun `remediation echoing an uppercase repair item identifier is accepted`() {
    var auditLaunches = 0
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput())
          }
          "implement" -> {
            implementLaunches += 1
            facts(
              if (implementLaunches == 1) {
                validJsonOutput(phaseId)
              } else {
                validJsonOutput(phaseId).replace("ac-002-gap-1-item-1", "AC-002-GAP-1-ITEM-1")
              },
            )
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(2, implementLaunches, "the uppercase remediation was accepted on its first attempt")
  }

  @Test
  fun `future phase vocabulary in remediation free text is opaque`() {
    var auditLaunches = 0
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> facts(if (++auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput())
          "implement" -> {
            implementLaunches += 1
            facts(
              if (implementLaunches == 1) {
                validJsonOutput(phaseId)
              } else {
                remediationResultsOutput(
                  repairItemIds = listOf("ac-002-gap-1-item-1"),
                  summary = "Full validation will verify integration; review can discover new defects; " +
                    "a later audit confirms the repaired state.",
                )
              },
            )
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
  }

  // Non-progress used to be waived whenever the following audit's plan introduced any repair item id
  // the previous plan did not carry, so an audit could escape the gate by re-emitting the same gap with
  // one more item.
  @Test
  fun `re-emitting the same gap with an extra repair item still blocks as non progress`() {
    val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" }
    var auditLaunches = 0
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> {
            auditLaunches += 1
            facts(
              when (auditLaunches) {
                1 -> auditRepairPlanOutput(criterionRef = "AC-005", itemCount = 2)
                2 -> auditRepairPlanOutput(
                  criterionRef = "AC-005",
                  itemCount = 3,
                  dispositions = recurringDisposition("ac-005-gap-1"),
                )
                else -> auditSatisfiedOutput(followUp = false)
              },
            )
          }
          "implement" -> {
            implementLaunches += 1
            facts(
              if (implementLaunches == 1) {
                validJsonOutput(phaseId)
              } else {
                remediationResultsOutput(
                  repairItemIds = (1..2).map { "ac-005-gap-1-item-$it" },
                  summary = "Remediation reported every carried item.",
                )
              },
            )
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        acceptanceCriteria = (1..5).map { "AC-$it" },
      ),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "Audit repair made no progress")
    assertEquals(2, auditLaunches, "the third audit never runs because the second one blocks")
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "validate" })
  }

  // Only an audit observes a fresh gap set. The remediation write recomputed the gap counters against
  // the audit's own already-persisted ledger, which clobbered new_gap_count to zero.
  @Test
  fun `the remediation write preserves the gap counters the audit observed`() {
    // Audit-first puts review after audit, so a never-satisfied audit never reaches review. The run
    // stops on the non-progress gate instead, which needs a stable fingerprint: the default fake git
    // mints a fresh one per call, which reads as perpetual repository change and never converges.
    val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" }
    var auditLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> {
            auditLaunches += 1
            facts(auditGapsOutput(followUp = auditLaunches > 1))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    val repairState = requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(listOf("ac-002-gap-1"), repairState.unresolvedGapLedger.unresolvedGaps.map { it.gapId })
    assertEquals(1, repairState.progress.newGapCount, "the audit observed one new gap and the remediation kept it")
    assertEquals(0, repairState.progress.recurringGapCount)
    assertTrue(
      repairState.progress.recurringGapCount + repairState.progress.newGapCount <=
        repairState.unresolvedGapLedger.unresolvedGaps.size,
      "the gap counters must stay coherent with the durable unresolved-gap ledger",
    )
  }

  // AC6 at the runner, not just the validator: a gaps_found audit wrapped as bare JSON, as a fenced
  // block, and as Markdown commentary + fence + trailing prose must normalize to one object that
  // drives validation, verdict selection, persistence, transition selection, and the downstream
  // handoff identically. Anything reading the raw text instead of the normalized envelope diverges here.
  @Test
  fun `every canonical wrapper form drives the same audit gap edge and handoff`() {
    val observed = CANONICAL_WRAPPER_FORMS.mapValues { (_, wrap) -> runAuditGapLoop(wrap) }
    val bare = observed.getValue("bare")

    observed.forEach { (form, actual) ->
      assertEquals(bare, actual, "wrapper form '$form' must produce the identical normalized run")
    }
    assertEquals(2, bare.phaseOrder.count { it == "audit" }, "the gaps_found audit fired the backward edge")
    assertEquals(2, bare.phaseOrder.count { it == "implement" })
    assertEquals(1, bare.phaseOrder.count { it == "plan" }, "the backward edge never regenerates planning")
    assertEquals(listOf(1), bare.auditGapEdgeIterations, "the audit_gap edge was selected, not the validate edge")
    assertContains(bare.implementBriefing, AUDIT_GAP_MESSAGE)
    assertTrue(bare.firstAuditArtifact.startsWith("{"))
    assertContains(bare.firstAuditArtifact, "\"verdict\":\"gaps_found\"")
    assertTrue(!bare.firstAuditArtifact.contains("```"))
    assertTrue(!bare.firstAuditArtifact.contains("structured result above"))
    bare.persistedOutputs.forEach { (phaseId, artifact) ->
      assertTrue(
        artifact.startsWith("{") && !artifact.contains("```"),
        "the persisted output for '$phaseId' must be the normalized envelope, not the wrapped raw text",
      )
    }
  }
}

private data class InvalidBlockedRepair(
  val name: String,
  val value: String?,
  val rule: String,
  val path: String,
)

private fun invalidBlockedRepairCases(): List<InvalidBlockedRepair> = listOf(
  InvalidBlockedRepair(
    "missing block",
    null,
    "audit_repair.blocked.unresolvable_repair.required",
    "/produced_outputs/unresolvable_repair",
  ),
  InvalidBlockedRepair(
    "malformed block",
    "[]",
    "audit_repair.blocked.unresolvable_repair.required",
    "/produced_outputs/unresolvable_repair",
  ),
  InvalidBlockedRepair(
    "missing gap",
    """{"repair_item_id":"ac-002-gap-1-item-1","evidence":$VALID_BLOCK_EVIDENCE}""",
    "audit_repair.blocked.gap_id",
    "/produced_outputs/unresolvable_repair/gap_id",
  ),
  InvalidBlockedRepair(
    "unknown gap",
    """{"gap_id":"ac-999-gap-1","repair_item_id":"ac-002-gap-1-item-1","evidence":$VALID_BLOCK_EVIDENCE}""",
    "audit_repair.blocked.gap_id",
    "/produced_outputs/unresolvable_repair/gap_id",
  ),
  InvalidBlockedRepair(
    "missing item",
    """{"gap_id":"ac-002-gap-1","evidence":$VALID_BLOCK_EVIDENCE}""",
    "audit_repair.blocked.repair_item_id",
    "/produced_outputs/unresolvable_repair/repair_item_id",
  ),
  InvalidBlockedRepair(
    "cross-gap item",
    """{"gap_id":"ac-002-gap-1","repair_item_id":"ac-999-gap-1-item-1","evidence":$VALID_BLOCK_EVIDENCE}""",
    "audit_repair.blocked.repair_item_id",
    "/produced_outputs/unresolvable_repair/repair_item_id",
  ),
  InvalidBlockedRepair(
    "malformed evidence",
    """{"gap_id":"ac-002-gap-1","repair_item_id":"ac-002-gap-1-item-1","evidence":{}}""",
    "audit_repair.blocked.evidence",
    "/produced_outputs/unresolvable_repair/evidence",
  ),
)

private fun skill128Harness(
  nextAuditLaunch: () -> Int,
  nextImplementLaunch: () -> Int,
  allowExhaustiveRepair: () -> Boolean,
): RunnerHarness = runnerHarness(
  launcher = RuntimeRecordingLauncher { request ->
    when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "audit" -> {
        val auditLaunch = nextAuditLaunch()
        facts(if (auditLaunch == 1) skill128BroadGapsOutput() else skill128SatisfiedOutput())
      }
      "implement" -> facts(
        when (nextImplementLaunch()) {
          1 -> validJsonOutput(phaseId)
          else -> if (!allowExhaustiveRepair()) {
            remediationResultsOutput(
              repairItemIds = listOf("ac-001-gap-1-item-1"),
              summary = "Only the production-code item is repaired.",
            )
          } else {
            remediationResultsOutput(
              repairItemIds = listOf(
                "ac-001-gap-1-item-1",
                "ac-002-gap-1-item-1",
                "ac-003-gap-1-item-1",
              ),
              summary = "Production, integration, and test repairs are all verified.",
            )
          }
        },
      )
      else -> facts(validJsonOutput(phaseId))
    }
  },
  runtimeConfig = RuntimeHarnessConfig(acceptanceCriteria = (1..3).map { "AC-$it" }),
)

private fun skill128BroadGapsOutput(): String = """
  {
    "contract_version":"0.2",
    "phase_id":"audit",
    "status":"completed",
    "summary":"SKILL-128 regression found broad production, integration, and test gaps.",
    "verdict":"gaps_found",
    "produced_outputs":{
      "unmet_criteria":[
        {"acceptance_criterion_ref":"AC-001","message":"Production behavior is incomplete."},
        {"acceptance_criterion_ref":"AC-002","message":"Integration behavior is incomplete."},
        {"acceptance_criterion_ref":"AC-003","message":"Regression coverage is incomplete."}
      ],
      "audit_repair_plan":{
        "contract_version":"0.2",
        "gaps":[
          ${skill128Gap("AC-001", "production", "src/Production.kt")},
          ${skill128Gap("AC-002", "integration", "src/Integration.kt")},
          ${skill128Gap("AC-003", "test", "test/RegressionTest.kt")}
        ]
      }
    }
  }
""".trimIndent()

private fun skill128SatisfiedOutput(): String = """
  {
    "contract_version":"0.2",
    "phase_id":"audit",
    "status":"completed",
    "summary":"Every SKILL-128-derived gap is now verified.",
    "verdict":"satisfied",
    "produced_outputs":{
      "acceptance_audit":"All broad criteria are satisfied.",
      "unmet_criteria":[],
      "prior_gap_dispositions":[
        {"gap_id":"ac-001-gap-1","status":"resolved","evidence":{"observation":"resolution_verified","artifact_ref":"src/Production.kt","check_ref":"AC-001"}},
        {"gap_id":"ac-002-gap-1","status":"resolved","evidence":{"observation":"resolution_verified","artifact_ref":"src/Integration.kt","check_ref":"AC-002"}},
        {"gap_id":"ac-003-gap-1","status":"resolved","evidence":{"observation":"resolution_verified","artifact_ref":"test/RegressionTest.kt","check_ref":"AC-003"}}
      ],
      "blast_radius_inspection":{
        "inspected_paths":["src/Production.kt","src/Integration.kt"],
        "newly_introduced_gap_ids":[],
        "evidence":{"observation":"resolution_verified","artifact_ref":"src/Production.kt","check_ref":"AC-001"}
      }
    }
  }
""".trimIndent()

private fun skill128Gap(criterionRef: String, boundary: String, path: String): String {
  val gapId = "${criterionRef.lowercase()}-gap-1"
  return """
    {
      "gap_id":"$gapId",
      "acceptance_criterion_ref":"$criterionRef",
      "acceptance_criterion_text":"The $boundary requirement is complete.",
      "failure_evidence":{"observation":"required_behavior_absent","artifact_ref":"$path","check_ref":"$criterionRef"},
      "diagnosis":"The $boundary work was omitted.",
      "affected_boundary":"$boundary",
      "repair_items":[{
        "repair_item_id":"$gapId-item-1",
        "intended_outcome":"Complete the $boundary work.",
        "implementation_actions":["Implement the $boundary work."],
        "affected_paths_or_symbols":["$path"],
        "required_verification":["Verify $criterionRef."],
        "depends_on":[],
        "status":"pending"
      }]
    }
  """.trimIndent()
}

private val CANONICAL_WRAPPER_FORMS: Map<String, (String) -> String> = mapOf(
  "bare" to { text -> text },
  "bare_trailing_prose" to { text -> "$text\nThe structured result above is authoritative." },
  "fenced" to { text -> "```json\n$text\n```" },
  "markdown_prefixed" to { text ->
    "## Phase result\n\nEvidence and reasoning precede the envelope.\n\n```json\n$text\n```\n\n" +
      "That envelope is the phase output; the rest of this message is commentary."
  },
)

private data class NormalizedRunObservation(
  val phaseOrder: List<String>,
  val auditGapEdgeIterations: List<Int>,
  val persistedOutputs: Map<String, String>,
  val firstAuditArtifact: String,
  val implementBriefing: String,
  val unresolvedGapIds: List<String>,
  val priorGapDispositions: List<String>,
)

private fun runAuditGapLoop(wrap: (String) -> String): NormalizedRunObservation {
  var auditLaunches = 0
  var firstAuditArtifact: String? = null
  lateinit var harness: RunnerHarness
  harness = runnerHarness(
    launcher = RuntimeRecordingLauncher { request ->
      val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
      if (phaseId == "implement" && auditLaunches == 1) {
        firstAuditArtifact = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["audit"]?.outputArtifact
      }
      facts(
        wrap(
          when {
            phaseId != "audit" -> validJsonOutput(phaseId)
            else -> {
              auditLaunches += 1
              if (auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput()
            }
          },
        ),
      )
    },
    validator = CanonicalWrapperTestValidator,
  )

  assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

  val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
  val repairState = requireNotNull(harness.recorder.loadAuditRepairState(WORKFLOW_ID))
  return NormalizedRunObservation(
    phaseOrder = harness.launchedPromptPhaseOrder(),
    auditGapEdgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
      .mapNotNull { it.edgeIteration },
    persistedOutputs = records.mapNotNull { (phaseId, record) ->
      record.outputArtifact?.let { phaseId to it }
    }.toMap(),
    firstAuditArtifact = requireNotNull(firstAuditArtifact),
    implementBriefing = requireNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["implement"])
      .briefingText,
    unresolvedGapIds = repairState.unresolvedGapLedger.unresolvedGaps.map { it.gapId },
    priorGapDispositions = repairState.priorGapDispositions.map { "${it.gapId}:${it.status.name}" },
  )
}

private fun recurringDisposition(gapId: String): String =
  """"prior_gap_dispositions":[{"gap_id":"$gapId","status":"recurring",""" +
    """"evidence":{"observation":"recurrence_verified","artifact_ref":"runtime-kotlin","check_ref":"AC-005"}}],"""

// A schema-valid gaps_found audit whose repair plan carries [itemCount] ordered items for one
// criterion, optionally chained through depends_on so the dependency-order gate has work to do.
private fun auditRepairPlanOutput(
  criterionRef: String,
  itemCount: Int,
  dependsChain: Boolean = false,
  dispositions: String = "",
): String {
  val gapId = "${criterionRef.lowercase()}-gap-1"
  val items = (1..itemCount).joinToString(",") { ordinal ->
    val dependsOn = if (dependsChain && ordinal > 1) "\"$gapId-item-${ordinal - 1}\"" else ""
    """
      {
        "repair_item_id":"$gapId-item-$ordinal",
        "intended_outcome":"Repair item $ordinal is implemented.",
        "implementation_actions":["Reconcile repair item $ordinal."],
        "affected_paths_or_symbols":["src/Foo.kt"],
        "required_verification":["Run the focused check for item $ordinal."],
        "depends_on":[$dependsOn],
        "status":"pending"
      }
    """.trimIndent()
  }
  return """
    {
      "contract_version": "0.2",
      "phase_id": "audit",
      "status": "completed",
      "summary": "Audit found unmet acceptance criteria.",
      "verdict": "gaps_found",
      "produced_outputs": {
        "unmet_criteria": [
          {"acceptance_criterion_ref":"$criterionRef","message":"$criterionRef is not yet implemented"}
        ],
        $dispositions
        "audit_repair_plan": {
          "contract_version":"0.2",
          "gaps":[{
            "gap_id":"$gapId",
            "acceptance_criterion_ref":"$criterionRef",
            "acceptance_criterion_text":"The audit gap is repaired.",
            "failure_evidence":{
              "observation":"required_behavior_absent",
              "artifact_ref":"runtime-kotlin",
              "check_ref":"$criterionRef"
            },
            "diagnosis":"Implement and verify the missing behavior.",
            "affected_boundary":"runtime application",
            "repair_items":[$items]
          }]
        }
      }
    }
  """.trimIndent()
}

// A completed remediation output carrying one terminal result per named repair item.
private fun remediationResultsOutput(
  repairItemIds: List<String>,
  summary: String,
  extraProducedOutputs: String = "",
): String {
  val results = repairItemIds.joinToString(",") { repairItemId ->
    """
      {
        "repair_item_id":"$repairItemId",
        "outcome":"fixed",
        "changed_paths_or_symbols":["src/Foo.kt"],
        "executed_verification":["Focused check passed."],
        "result_evidence":{"observation":"fix_verified","artifact_ref":"runtime-kotlin","check_ref":"AC-002"}
      }
    """.trimIndent()
  }
  return """
    {
      "contract_version": "0.2",
      "phase_id": "implement",
      "status": "completed",
      "summary": "$summary",
      "produced_outputs": {
        ${PlanningProjectionFixtures.RECEIPT_FIELDS}
        "changed_files":["src/Foo.kt"],
        "reconciled_state":{"reconciled":true},
        "deferred_repair_item_ids":[],
        "repair_item_results":[$results]$extraProducedOutputs
      }
    }
  """.trimIndent()
}

// A blocked remediation naming one unresolvable carried item and reporting no terminal results, the
// strict-subset shape the result gate must tolerate.
private fun blockedRemediationOutput(
  gapId: String,
  repairItemId: String,
  deferredItemIds: List<String> = listOf(repairItemId),
  unresolvableRepair: String? = """
    {
      "gap_id":"$gapId",
      "repair_item_id":"$repairItemId",
      "evidence":$VALID_BLOCK_EVIDENCE
    }
  """.trimIndent(),
): String = """
  {
    "contract_version": "0.2",
    "phase_id": "implement",
    "status": "blocked",
    "summary": "Remediation cannot make the first repair item work.",
    "produced_outputs": {
      ${PlanningProjectionFixtures.RECEIPT_FIELDS}
      "changed_files":[],
      "reconciled_state":{"reconciled":true},
      "deferred_repair_item_ids":[${deferredItemIds.joinToString(",") { "\"$it\"" }}],
      "repair_item_results":[]${unresolvableRepair?.let { ""","unresolvable_repair":$it""" }.orEmpty()}
    }
  }
""".trimIndent()

private const val VALID_BLOCK_EVIDENCE =
  """{"observation":"verification_failed","artifact_ref":"src/Foo.kt","check_ref":"AC-002"}"""
