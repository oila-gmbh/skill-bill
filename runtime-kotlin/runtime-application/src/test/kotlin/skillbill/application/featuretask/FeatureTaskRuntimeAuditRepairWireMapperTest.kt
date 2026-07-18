package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGapLedger
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeAuditRepairWireMapperTest {
  @Test
  fun `semantic plan failures use the audit repair typed error`() {
    val malformed = auditRepairPlanToWire(plan()).toMutableMap().apply { put("contract_version", "9.9") }

    assertFailsWith<InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError> {
      auditRepairPlanFromWire(malformed, "audit_repair_plan")
    }
  }

  @Test
  fun `durable state rejects an incompatible contract version`() {
    val malformed = auditRepairStateToWire(state()).toMutableMap().apply { put("contract_version", "9.9") }

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(malformed, "audit_repair_state")
    }
  }

  @Test
  fun `durable ledger rejects a generation that disagrees with its stable identifier`() {
    val malformed = auditRepairStateToWire(state()).toMutableMap()
    val ledger = (malformed.getValue("unresolved_gap_ledger") as Map<*, *>).toMutableMap()
    val gap = ((ledger.getValue("gaps") as List<*>).single() as Map<*, *>).toMutableMap()
    gap["generation"] = 2
    ledger["gaps"] = listOf(gap)
    malformed["unresolved_gap_ledger"] = ledger

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(malformed, "audit_repair_state")
    }
  }

  @Test
  fun `durable state rejects a latest plan that differs from accepted history`() {
    val malformed = auditRepairStateToWire(state()).toMutableMap()
    val latest = (malformed.getValue("latest_plan") as Map<*, *>).toMutableMap()
    val gap = ((latest.getValue("gaps") as List<*>).single() as Map<*, *>).toMutableMap()
    gap["failure_evidence"] = "Different evidence"
    latest["gaps"] = listOf(gap)
    malformed["latest_plan"] = latest

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(malformed, "audit_repair_state")
    }
  }

  private fun state() = FeatureTaskRuntimeAuditRepairState(
    acceptedPlans = listOf(plan()),
    repairItemResults = emptyList(),
    priorGapDispositions = emptyList(),
    unresolvedGapLedger = FeatureTaskRuntimeUnresolvedGapLedger(
      listOf(FeatureTaskRuntimeUnresolvedGap("ac-001-gap-1", "AC-001", 1)),
    ),
    repositoryFingerprint = "fingerprint",
    progress = FeatureTaskRuntimeAuditRepairProgress(false, 0, 1, 0, 0, 1),
  )

  private fun plan() = FeatureTaskRuntimeAuditRepairPlan(
    contractVersion = AUDIT_REPAIR_CONTRACT_VERSION,
    gaps = listOf(
      FeatureTaskRuntimeAuditGap(
        gapId = "ac-001-gap-1",
        acceptanceCriterionRef = "AC-001",
        acceptanceCriterionText = "Criterion",
        failureEvidence = "Evidence",
        diagnosis = "Diagnosis",
        affectedBoundary = "runtime",
        repairItems = listOf(
          FeatureTaskRuntimeRepairItem(
            repairItemId = "ac-001-gap-1-item-1",
            intendedOutcome = "Outcome",
            implementationActions = listOf("Implement"),
            affectedPathsOrSymbols = listOf("src/Foo.kt"),
            requiredVerification = listOf("Test"),
            dependsOn = emptyList(),
          ),
        ),
      ),
    ),
  )
}
