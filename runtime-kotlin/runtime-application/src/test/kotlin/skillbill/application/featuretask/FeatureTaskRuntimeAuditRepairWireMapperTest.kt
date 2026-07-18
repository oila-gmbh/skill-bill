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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeAuditRepairWireMapperTest {
  @Test
  fun `standalone plan rejects missing arrays invalid status and unknown fields`() {
    val valid = auditRepairPlanToWire(plan())
    val malformedItems = listOf(
      itemMutation(valid) { remove("affected_paths_or_symbols") },
      itemMutation(valid) { remove("depends_on") },
      itemMutation(valid) { put("status", "fixed") },
      itemMutation(valid) { put("unexpected", true) },
    )
    val malformedGap = valid.toMutableMap().apply {
      val gap = ((getValue("gaps") as List<*>).single() as Map<*, *>).toMutableMap()
      gap["unexpected"] = true
      put("gaps", listOf(gap))
    }
    val malformedPlans = malformedItems + malformedGap + valid.toMutableMap().apply { put("unexpected", true) }

    malformedPlans.forEach { malformed ->
      assertFailsWith<InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError> {
        auditRepairPlanFromWire(malformed, "audit_repair_plan")
      }
    }
  }

  @Test
  fun `durable plan rejects strict schema violations through workflow state error`() {
    val malformed = auditRepairStateToWire(state()).toMutableMap()
    malformed["latest_plan"] = itemMutation(malformed.getValue("latest_plan") as Map<*, *>) {
      remove("depends_on")
    }

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(malformed, "audit_repair_state")
    }
  }

  @Test
  fun `durable ledger rejects noncanonical criterion references`() {
    val malformed = auditRepairStateToWire(state()).toMutableMap()
    val ledger = (malformed.getValue("unresolved_gap_ledger") as Map<*, *>).toMutableMap()
    val gap = ((ledger.getValue("gaps") as List<*>).single() as Map<*, *>).toMutableMap()
    gap["gap_id"] = "foo-gap-1"
    gap["acceptance_criterion_ref"] = "FOO"
    ledger["gaps"] = listOf(gap)
    malformed["unresolved_gap_ledger"] = ledger

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(malformed, "audit_repair_state")
    }
  }

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
  fun `durable ledger generation rejects fractional overflow missing and incorrect types`() {
    listOf<Any?>(1.5, Long.MAX_VALUE, "1", null).forEach { invalid ->
      val malformed = auditRepairStateToWire(state()).toMutableMap()
      val ledger = (malformed.getValue("unresolved_gap_ledger") as Map<*, *>).toMutableMap()
      val gap = ((ledger.getValue("gaps") as List<*>).single() as Map<*, *>).toMutableMap()
      if (invalid == null) gap.remove("generation") else gap["generation"] = invalid
      ledger["gaps"] = listOf(gap)
      malformed["unresolved_gap_ledger"] = ledger
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        auditRepairStateFromWire(malformed, "audit_repair_state")
      }
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

  @Test
  fun `durable state translates nested plan failures to workflow state errors`() {
    val malformed = auditRepairStateToWire(state()).toMutableMap()
    val plans = (malformed.getValue("accepted_plans") as List<*>).toMutableList()
    plans[0] = (plans[0] as Map<*, *>).toMutableMap().apply { put("contract_version", "9.9") }
    malformed["accepted_plans"] = plans

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(malformed, "audit_repair_state")
    }
  }

  @Test
  fun `durable numeric fields reject fractional overflow missing and incorrect types`() {
    listOf<Any?>(1.5, Long.MAX_VALUE, "1", null).forEach { invalid ->
      val malformed = auditRepairStateToWire(state()).toMutableMap()
      val progress = (malformed.getValue("progress") as Map<*, *>).toMutableMap()
      if (invalid == null) progress.remove("new_gap_count") else progress["new_gap_count"] = invalid
      malformed["progress"] = progress
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        auditRepairStateFromWire(malformed, "audit_repair_state")
      }
    }
  }

  @Test
  fun `durable scalars are required and exactly typed`() {
    val missingProgress = auditRepairStateToWire(state()).toMutableMap().apply { remove("progress") }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(missingProgress, "audit_repair_state")
    }

    val malformedBoolean = auditRepairStateToWire(state()).toMutableMap()
    val progress = (malformedBoolean.getValue("progress") as Map<*, *>).toMutableMap()
    progress["first_pass_convergence"] = 0
    malformedBoolean["progress"] = progress
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(malformedBoolean, "audit_repair_state")
    }

    val malformedFingerprint = auditRepairStateToWire(state()).toMutableMap().apply {
      put("repository_fingerprint", 7)
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(malformedFingerprint, "audit_repair_state")
    }
  }

  @Test
  fun `durable repair state round trips exactly`() {
    val expected = state()
    assertEquals(expected, auditRepairStateFromWire(auditRepairStateToWire(expected), "audit_repair_state"))
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

  private fun itemMutation(plan: Map<*, *>, mutation: MutableMap<Any?, Any?>.() -> Unit): Map<String, Any?> {
    val copy = plan.entries.associate { (key, value) -> key.toString() to value }.toMutableMap()
    val gaps = (copy.getValue("gaps") as List<*>).toMutableList()
    val gap = (gaps.single() as Map<*, *>).toMutableMap()
    val items = (gap.getValue("repair_items") as List<*>).toMutableList()
    val item = (items.single() as Map<*, *>).toMutableMap().apply(mutation)
    items[0] = item
    gap["repair_items"] = items
    gaps[0] = gap
    copy["gaps"] = gaps
    return copy
  }
}
