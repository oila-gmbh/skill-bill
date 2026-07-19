package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGapLedger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeAuditRepairWireMapperTest {
  @Test
  fun `recurring repair identifiers retain the newest terminal result`() {
    val prior = result("old evidence")
    val current = result("new evidence")

    assertEquals(
      listOf(current),
      reconcileLatestRepairResults(listOf(prior), listOf(current), setOf(current.repairItemId)),
    )
    assertEquals(
      emptyList(),
      reconcileLatestRepairResults(listOf(prior), listOf(current), setOf("ac-999-gap-1-item-1")),
    )
  }

  @Test
  fun `durable gap evidence rejects blank and payload representations`() {
    val invalidSummaries = listOf(
      "",
      "   ",
      "User: reproduce the entire prompt",
      "@@ -1,1 +1,1 @@\n-old\n+new",
      "fun leakedSource() { return Unit }",
      "{\"tool\":\"result\",\"output\":\"raw\"}",
    )
    invalidSummaries.forEach { invalid ->
      val malformed = auditRepairStateToWire(state()).toMutableMap()
      val changedPlan = (malformed.getValue("latest_plan") as Map<*, *>).toStringKeyMap().toMutableMap()
      val gap = ((changedPlan.getValue("gaps") as List<*>).single() as Map<*, *>).toStringKeyMap().toMutableMap()
      gap["failure_evidence"] = invalid
      changedPlan["gaps"] = listOf(gap)
      malformed["latest_plan"] = changedPlan
      malformed["accepted_plans"] = listOf(changedPlan)

      assertFailsWith<InvalidWorkflowStateSchemaError> {
        auditRepairStateFromWire(malformed, "audit_repair_state")
      }
    }
  }

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
  fun `durable ledger rejects gaps absent from accepted plan history`() {
    val malformed = auditRepairStateToWire(state()).toMutableMap()
    malformed["unresolved_gap_ledger"] = mapOf(
      "contract_version" to AUDIT_REPAIR_CONTRACT_VERSION,
      "gaps" to listOf(
        mapOf("gap_id" to "ac-002-gap-1", "acceptance_criterion_ref" to "AC-002", "generation" to 1),
      ),
    )

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(malformed, "audit_repair_state")
    }
    assertContains(error.message.orEmpty(), "accepted plan gap identity")
  }

  @Test
  fun `durable ledger rejects criterion identity that differs from accepted plan history`() {
    val malformed = auditRepairStateToWire(state()).toMutableMap()
    malformed["unresolved_gap_ledger"] = mapOf(
      "contract_version" to AUDIT_REPAIR_CONTRACT_VERSION,
      "gaps" to listOf(
        mapOf("gap_id" to "ac-002-gap-1", "acceptance_criterion_ref" to "AC-002", "generation" to 1),
      ),
    )
    val accepted = ((malformed.getValue("accepted_plans") as List<*>).single() as Map<*, *>).toStringKeyMap()
    val acceptedGap = ((accepted.getValue("gaps") as List<*>).single() as Map<*, *>).toMutableMap().apply {
      put("gap_id", "ac-002-gap-1")
    }
    malformed["accepted_plans"] = listOf(accepted.toMutableMap().apply { put("gaps", listOf(acceptedGap)) })
    malformed["latest_plan"] = accepted.toMutableMap().apply { put("gaps", listOf(acceptedGap)) }

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(malformed, "audit_repair_state")
    }
    assertContains(error.message.orEmpty(), "must belong to acceptance criterion")
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

  @Test
  fun `durable state rejects oversized allowed fields and embedded raw payloads`() {
    val oversized = auditRepairStateToWire(state()).toMutableMap()
    oversized["latest_plan"] = itemMutation(oversized.getValue("latest_plan") as Map<*, *>) {
      put("intended_outcome", "x".repeat(2049))
    }
    oversized["accepted_plans"] = listOf(oversized.getValue("latest_plan"))

    val rawPayload = auditRepairStateToWire(state()).toMutableMap()
    rawPayload["latest_plan"] = itemMutation(rawPayload.getValue("latest_plan") as Map<*, *>) {
      put("implementation_actions", listOf("diff --git a/source.kt b/source.kt\n+secret source body"))
    }
    rawPayload["accepted_plans"] = listOf(rawPayload.getValue("latest_plan"))

    listOf(oversized, rawPayload).forEach { malformed ->
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        auditRepairStateFromWire(malformed, "audit_repair_state")
      }
    }
  }

  @Test
  fun `durable state bounds complete accepted plan retention to the exact latest plan`() {
    val malformed = auditRepairStateToWire(state()).toMutableMap()
    val plan = malformed.getValue("latest_plan")
    malformed["accepted_plans"] = listOf(plan, plan)

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(malformed, "audit_repair_state")
    }
  }

  @Test
  fun `durable state requires every collection and rejects forbidden payload fields`() {
    listOf("execution_history", "prior_gap_dispositions").forEach { field ->
      val malformed = auditRepairStateToWire(state()).toMutableMap().apply { remove(field) }
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        auditRepairStateFromWire(malformed, "audit_repair_state")
      }
    }

    val missingLedgerGaps = auditRepairStateToWire(state()).toMutableMap()
    missingLedgerGaps["unresolved_gap_ledger"] =
      (missingLedgerGaps.getValue("unresolved_gap_ledger") as Map<*, *>).toMutableMap().apply { remove("gaps") }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      auditRepairStateFromWire(missingLedgerGaps, "audit_repair_state")
    }

    listOf("prompt", "raw_tool_output").forEach { field ->
      val malformed = auditRepairStateToWire(state()).toMutableMap().apply { put(field, "forbidden") }
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        auditRepairStateFromWire(malformed, "audit_repair_state")
      }
    }
  }

  @Test
  fun `durable state rejects unknown fields in every nested state object`() {
    val mutations = listOf<(MutableMap<String, Any?>) -> Unit>(
      { wire ->
        wire["progress"] = (wire.getValue("progress") as Map<*, *>).toStringKeyMap().apply { put("extra", 1) }
      },
      { wire ->
        val ledger = (wire.getValue("unresolved_gap_ledger") as Map<*, *>).toStringKeyMap()
        ledger["extra"] = true
        wire["unresolved_gap_ledger"] = ledger
      },
      { wire ->
        wire["execution_history"] = listOf(
          (repairItemResultWire() as Map<*, *>).toStringKeyMap().apply { put("raw_tool_output", "forbidden") },
        )
        wire["progress"] = progressWire(attempted = 1, resolved = 1)
      },
      { wire ->
        wire["prior_gap_dispositions"] = listOf(
          dispositionWire().toMutableMap().apply { put("prompt", "forbidden") },
        )
      },
      { wire ->
        val ledger = (wire.getValue("unresolved_gap_ledger") as Map<*, *>).toStringKeyMap()
        ledger["gaps"] = listOf(
          ((ledger.getValue("gaps") as List<*>).single() as Map<*, *>).toStringKeyMap().apply {
            put("source_body", "forbidden")
          },
        )
        wire["unresolved_gap_ledger"] = ledger
      },
    )
    mutations.forEach { mutation ->
      val malformed = auditRepairStateToWire(state()).toMutableMap().also(mutation)
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        auditRepairStateFromWire(malformed, "audit_repair_state")
      }
    }
  }

  @Test
  fun `durable state rejects omitted history ledger entries and contradictory counters`() {
    val omittedLedgerGap = auditRepairStateToWire(state()).toMutableMap()
    val ledger = (omittedLedgerGap.getValue("unresolved_gap_ledger") as Map<*, *>).toStringKeyMap()
    ledger["gaps"] = emptyList<Any?>()
    omittedLedgerGap["unresolved_gap_ledger"] = ledger

    val contradictoryCounter = auditRepairStateToWire(state()).toMutableMap()
    contradictoryCounter["progress"] =
      (contradictoryCounter.getValue("progress") as Map<*, *>).toStringKeyMap().apply {
        put("attempted_repair_item_count", 1)
      }

    val impossibleGapCounters = auditRepairStateToWire(state()).toMutableMap()
    impossibleGapCounters["progress"] =
      (impossibleGapCounters.getValue("progress") as Map<*, *>).toStringKeyMap().apply {
        put("new_gap_count", 2)
      }

    val unknownTerminalHistory = auditRepairStateToWire(state()).toMutableMap().apply {
      put("execution_history", listOf(repairItemResultWire("ac-999-gap-1-item-1")))
      put("progress", progressWire(attempted = 1, resolved = 1))
    }

    val malformedStates =
      listOf(
        omittedLedgerGap,
        contradictoryCounter,
        impossibleGapCounters,
        unknownTerminalHistory,
      )
    malformedStates.forEach { malformed ->
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        auditRepairStateFromWire(malformed, "audit_repair_state")
      }
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

  private fun result(evidence: String) = FeatureTaskRuntimeRepairItemResult(
    "ac-001-gap-1-item-1",
    FeatureTaskRuntimeRepairItemOutcome.FIXED,
    listOf("src/Foo.kt"),
    listOf("Focused test passed"),
    evidence,
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

  private fun Map<*, *>.toStringKeyMap(): MutableMap<String, Any?> =
    entries.associate { (key, value) -> key.toString() to value }.toMutableMap()

  private fun repairItemResultWire(repairItemId: String = "ac-001-gap-1-item-1"): Map<String, Any?> = mapOf(
    "repair_item_id" to repairItemId,
    "outcome" to "fixed",
    "changed_paths_or_symbols" to listOf("Example.kt"),
    "executed_verification" to listOf("focused test passed"),
    "result_evidence" to "The intended behavior is present.",
  )

  private fun dispositionWire(): Map<String, Any?> = mapOf(
    "gap_id" to "ac-001-gap-1",
    "status" to "recurring",
    "evidence" to "The gap remains unresolved.",
  )

  private fun progressWire(attempted: Int, resolved: Int): Map<String, Any?> = mapOf(
    "first_pass_convergence" to false,
    "recurring_gap_count" to 0,
    "new_gap_count" to 1,
    "attempted_repair_item_count" to attempted,
    "resolved_repair_item_count" to resolved,
    "audit_gap_iteration_count" to 1,
  )
}
