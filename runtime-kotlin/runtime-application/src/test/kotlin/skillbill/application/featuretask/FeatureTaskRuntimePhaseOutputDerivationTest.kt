package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDerivationReaskState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDerivationResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDerivedSettlement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseOutputDerivationTest {
  @Test
  fun `indecisive settlement never resolves to completed`() {
    val context = FeatureTaskRuntimeDerivationContext(
      phaseId = "implement",
      outputText = """{"status":"maybe","summary":"done"}""",
      outputMap = mapOf("status" to "maybe", "summary" to "done"),
    )
    assertIs<FeatureTaskRuntimeDerivationResult.Indecisive>(
      FeatureTaskRuntimePhaseOutputDerivation.deriveSettlement(context),
    )
  }

  @Test
  fun `blocked settlement derives failure disposition on the settlement seam`() {
    val context = FeatureTaskRuntimeDerivationContext(
      phaseId = "implement",
      outputText = """{"status":"blocked","failure_disposition":"needs_user_action","summary":"stuck"}""",
      outputMap = mapOf(
        "status" to "blocked",
        "failure_disposition" to "needs_user_action",
        "summary" to "stuck",
      ),
    )
    val derived = FeatureTaskRuntimePhaseOutputDerivation.deriveSettlement(context)
    val settlement = assertIs<FeatureTaskRuntimeDerivationResult.Decided<FeatureTaskRuntimeDerivedSettlement>>(
      derived,
    ).value
    assertEquals("blocked", settlement.status)
    assertEquals(FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION, settlement.failureDisposition)
  }

  @Test
  fun `prose-only gaps_found routes audit_gap verdict`() {
    val context = FeatureTaskRuntimeDerivationContext(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      outputText = """{"status":"completed","summary":"gaps_found on AC-001"}""",
      outputMap = mapOf(
        "status" to "completed",
        "summary" to "gaps_found on AC-001",
        "produced_outputs" to mapOf("evidence" to "x"),
      ),
      acceptanceCriterionRefs = listOf("AC-001"),
    )
    val derived = FeatureTaskRuntimePhaseOutputDerivation.deriveRoutingVerdict(context)
    assertEquals(
      FeatureTaskRuntimeVerdict.GAPS_FOUND,
      assertIs<FeatureTaskRuntimeDerivationResult.Decided<*>>(derived).value,
    )
  }

  @Test
  fun `prose outputText drives audit verdict when envelope map is empty`() {
    assertEquals(
      FeatureTaskRuntimeVerdict.GAPS_FOUND,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        "status: completed. gaps_found on AC-007.",
        emptyMap(),
      ),
    )
  }

  @Test
  fun `prose-only gaps_found recovers unmet criterion refs without acceptanceCriterionRefs`() {
    val envelope = mapOf(
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      "status" to "completed",
      "summary" to "gaps_found on AC-003",
      "produced_outputs" to mapOf("evidence" to "x"),
    )
    assertEquals(
      FeatureTaskRuntimeVerdict.GAPS_FOUND,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        envelope,
      ),
    )
    assertEquals(listOf("ac-003"), FeatureTaskRuntimeOutputVerification.canonicalAuditCriterionRefs(envelope))
    assertEquals(listOf("AC-003"), FeatureTaskRuntimeOutputVerification.unmetAuditCriteria(envelope))
  }

  @Test
  fun `prose gaps_found keeps ref unmet when only negated satisfied claim appears near ref`() {
    val envelope = mapOf(
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      "status" to "completed",
      "summary" to "gaps_found: AC-003 not satisfied",
      "produced_outputs" to mapOf("evidence" to "x"),
    )
    assertEquals(
      FeatureTaskRuntimeVerdict.GAPS_FOUND,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        envelope,
      ),
    )
    assertEquals(listOf("ac-003"), FeatureTaskRuntimeOutputVerification.canonicalAuditCriterionRefs(envelope))
    assertEquals(listOf("AC-003"), FeatureTaskRuntimeOutputVerification.unmetAuditCriteria(envelope))
  }

  @Test
  fun `prose gaps_found does not clear unmet ref when satisfied appears only near another ref`() {
    val envelope = mapOf(
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      "status" to "completed",
      "summary" to "AC-001 satisfied; gaps_found AC-003",
      "produced_outputs" to mapOf("evidence" to "x"),
    )
    assertEquals(
      FeatureTaskRuntimeVerdict.GAPS_FOUND,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        envelope,
      ),
    )
    assertEquals(listOf("ac-003"), FeatureTaskRuntimeOutputVerification.canonicalAuditCriterionRefs(envelope))
    assertEquals(listOf("AC-003"), FeatureTaskRuntimeOutputVerification.unmetAuditCriteria(envelope))
  }

  @Test
  fun `prose-only gaps_found intersects extracted refs with acceptanceCriterionRefs when present`() {
    val context = FeatureTaskRuntimeDerivationContext(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      outputText = """{"status":"completed","summary":"gaps_found on AC-001 and AC-999"}""",
      outputMap = mapOf(
        "status" to "completed",
        "summary" to "gaps_found on AC-001 and AC-999",
        "produced_outputs" to mapOf("evidence" to "x"),
      ),
      acceptanceCriterionRefs = listOf("AC-001", "AC-002"),
    )
    assertEquals(
      listOf("ac-001"),
      FeatureTaskRuntimePhaseOutputDerivation.canonicalAuditCriterionRefs(context),
    )
  }

  @Test
  fun `prose-only findings_verified routes review_fix verdict`() {
    val context = FeatureTaskRuntimeDerivationContext(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      outputText = """{"status":"completed","summary":"findings_verified for F-001"}""",
      outputMap = mapOf(
        "status" to "completed",
        "summary" to "findings_verified for F-001",
        "produced_outputs" to mapOf("note" to "x"),
      ),
      carriedFindingIds = setOf("F-001"),
    )
    val derived = FeatureTaskRuntimePhaseOutputDerivation.deriveRoutingVerdict(context)
    assertEquals(
      FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
      assertIs<FeatureTaskRuntimeDerivationResult.Decided<*>>(derived).value,
    )
  }

  @Test
  fun `prose-only changes_requested reopens remediation`() {
    val context = FeatureTaskRuntimeDerivationContext(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      outputText = """{"status":"completed","summary":"changes_requested"}""",
      outputMap = mapOf(
        "status" to "completed",
        "summary" to "changes_requested",
        "produced_outputs" to mapOf("findings" to emptyList<Any>()),
      ),
      reviewFindingIds = setOf("F-001"),
    )
    val derived = FeatureTaskRuntimePhaseOutputDerivation.deriveRoutingVerdict(context)
    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      assertIs<FeatureTaskRuntimeDerivationResult.Decided<*>>(derived).value,
    )
  }

  @Test
  fun `conflicting structured verdict and prose resolves to structured`() {
    val context = FeatureTaskRuntimeDerivationContext(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      outputText = """{"verdict":"changes_requested","summary":"approved"}""",
      outputMap = mapOf(
        "verdict" to "approved",
        "summary" to "approved",
        "produced_outputs" to mapOf("findings" to emptyList<Any>()),
      ),
    )
    val derived = FeatureTaskRuntimePhaseOutputDerivation.deriveRoutingVerdict(context)
    assertEquals(
      FeatureTaskRuntimeVerdict.APPROVED,
      assertIs<FeatureTaskRuntimeDerivationResult.Decided<*>>(derived).value,
    )
  }

  @Test
  fun `missing review verdict is indecisive rather than approved`() {
    val context = FeatureTaskRuntimeDerivationContext(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      outputText = """{"status":"completed","summary":"review finished"}""",
      outputMap = mapOf(
        "status" to "completed",
        "summary" to "review finished",
        "produced_outputs" to mapOf("findings" to emptyList<Any>()),
      ),
    )
    assertIs<FeatureTaskRuntimeDerivationResult.Indecisive>(
      FeatureTaskRuntimePhaseOutputDerivation.deriveRoutingVerdict(context),
    )
    assertEquals(null, FeatureTaskRuntimePhaseOutputDerivation.verdictFor(context))
  }

  @Test
  fun `blocked prose failure disposition is derived on settlement seam`() {
    val context = FeatureTaskRuntimeDerivationContext(
      phaseId = "implement",
      outputText = """{"status":"blocked","summary":"needs_user_action"}""",
      outputMap = mapOf("status" to "blocked", "summary" to "needs_user_action"),
    )
    val derived = FeatureTaskRuntimePhaseOutputDerivation.deriveSettlement(context)
    val settlement = assertIs<FeatureTaskRuntimeDerivationResult.Decided<FeatureTaskRuntimeDerivedSettlement>>(
      derived,
    ).value
    assertEquals("blocked", settlement.status)
    assertEquals(FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION, settlement.failureDisposition)
  }

  @Test
  fun `derivation reask state round trips with authoritative second attempt`() {
    val state = FeatureTaskRuntimeDerivationReaskState(
      phaseId = "audit",
      reaskCount = 2,
      firstOutputArtifact = """{"phase_id":"audit","status":"completed","summary":"ambiguous"}""",
      secondOutputArtifact = """{"phase_id":"audit","status":"completed","summary":"still ambiguous"}""",
      authoritativeAttempt = 2,
    )
    val decoded = FeatureTaskRuntimeDerivationReaskState.fromArtifactMap(state.toArtifactMap())
    assertEquals(state, decoded)
  }

  @Test
  fun `obligation membership closes plan tasks named in returned text`() {
    val text = """{"completed_task_ids":[],"summary":"Closed task-1 and task-3"}"""
    val open = featureTaskRuntimeOpenObligations(
      obligations = FeatureTaskRuntimeImplementationObligations(
        plannedTaskIds = listOf("task-1", "task-2", "task-3"),
        carriedRepairItemIds = emptyList(),
        loopId = null,
      ),
      claim = FeatureTaskRuntimeImplementationClaim(
        completedTaskIds = emptyList(),
        changedPaths = emptyList(),
        unresolvedItems = emptyList(),
        deviations = emptyList(),
        reconciliationEvidence = null,
        repositoryCheckpoint = null,
      ),
      returnedText = text,
    )
    assertEquals(listOf("task-2"), open)
  }

  @Test
  fun `completion gate names absent plan task id`() {
    val reason = featureTaskRuntimeImplementationCompletionReason(
      phaseId = "implement",
      obligations = FeatureTaskRuntimeImplementationObligations(
        plannedTaskIds = listOf("task-1", "task-2"),
        carriedRepairItemIds = emptyList(),
        loopId = null,
      ),
      claim = FeatureTaskRuntimeImplementationClaim(
        completedTaskIds = listOf("task-1"),
        changedPaths = emptyList(),
        unresolvedItems = emptyList(),
        deviations = emptyList(),
        reconciliationEvidence = null,
        repositoryCheckpoint = null,
      ),
      returnedText = """{"completed_task_ids":["task-1"],"summary":"task-1 only"}""",
    )
    assertTrue(reason?.contains("task-2") == true)
  }

  @Test
  fun `prose not approved does not resolve to approved verdict`() {
    val context = FeatureTaskRuntimeDerivationContext(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      outputText = """{"status":"completed","summary":"not approved"}""",
      outputMap = mapOf(
        "status" to "completed",
        "summary" to "not approved",
        "produced_outputs" to mapOf("findings" to emptyList<Any>()),
      ),
    )
    assertIs<FeatureTaskRuntimeDerivationResult.Indecisive>(
      FeatureTaskRuntimePhaseOutputDerivation.deriveRoutingVerdict(context),
    )
  }

  @Test
  fun `prose not satisfied does not resolve to satisfied verdict`() {
    val context = FeatureTaskRuntimeDerivationContext(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      outputText = """{"status":"completed","summary":"not satisfied"}""",
      outputMap = mapOf(
        "status" to "completed",
        "summary" to "not satisfied",
        "produced_outputs" to mapOf("evidence" to "x"),
      ),
    )
    assertIs<FeatureTaskRuntimeDerivationResult.Indecisive>(
      FeatureTaskRuntimePhaseOutputDerivation.deriveRoutingVerdict(context),
    )
  }

  @Test
  fun `audit without verdict stays indecisive instead of advancing`() {
    val envelope = mapOf(
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      "status" to "completed",
      "summary" to "audit finished",
      "produced_outputs" to mapOf("gaps" to emptyList<Any>()),
    )
    assertEquals(
      null,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        envelope,
      ),
    )
    assertIs<FeatureTaskRuntimeDerivationResult.Indecisive>(
      FeatureTaskRuntimePhaseOutputDerivation.deriveRoutingVerdict(
        FeatureTaskRuntimeDerivationContext(
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          outputText = """{"status":"completed","summary":"audit finished"}""",
          outputMap = envelope,
        ),
      ),
    )
  }

  @Test
  fun `verify_findings without finding dispositions stays indecisive instead of advancing`() {
    val envelope = mapOf(
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      "status" to "completed",
      "summary" to "verification finished",
      "produced_outputs" to emptyMap<String, Any?>(),
    )
    assertEquals(
      null,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
        envelope,
      ),
    )
    assertIs<FeatureTaskRuntimeDerivationResult.Indecisive>(
      FeatureTaskRuntimePhaseOutputDerivation.deriveRoutingVerdict(
        FeatureTaskRuntimeDerivationContext(
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
          outputText = """{"status":"completed","summary":"verification finished"}""",
          outputMap = envelope,
          reviewFindingIds = setOf("F-001"),
        ),
      ),
    )
  }

  @Test
  fun `prose mentioning task-10 does not close task-1 obligation`() {
    val text = """{"completed_task_ids":[],"summary":"Closed task-10 only"}"""
    val open = featureTaskRuntimeOpenObligations(
      obligations = FeatureTaskRuntimeImplementationObligations(
        plannedTaskIds = listOf("task-1", "task-10"),
        carriedRepairItemIds = emptyList(),
        loopId = null,
      ),
      claim = FeatureTaskRuntimeImplementationClaim(
        completedTaskIds = emptyList(),
        changedPaths = emptyList(),
        unresolvedItems = emptyList(),
        deviations = emptyList(),
        reconciliationEvidence = null,
        repositoryCheckpoint = null,
      ),
      returnedText = text,
    )
    assertEquals(listOf("task-1"), open)
  }
}
