package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGapLedger
import skillbill.workflow.taskruntime.model.canonicalAuditIdentifier

internal fun auditRepairPlanFromWire(value: Any?, source: String): FeatureTaskRuntimeAuditRepairPlan =
  auditRepairPlanMapping(source) {
    val map = value.requiredMap(source)
    requireExactWireKeys(map, source, AUDIT_REPAIR_PLAN_KEYS)
    FeatureTaskRuntimeAuditRepairPlan(
      contractVersion = map.requiredString("contract_version", source),
      gaps = map.requiredList("gaps", source).mapIndexed { index, gap ->
        val gapSource = "$source.gaps[$index]"
        val gapMap = gap.requiredMap(gapSource)
        requireExactWireKeys(gapMap, gapSource, AUDIT_REPAIR_GAP_KEYS)
        FeatureTaskRuntimeAuditGap(
          gapId = canonicalAuditIdentifier(gapMap.requiredString("gap_id", gapSource)),
          acceptanceCriterionRef = gapMap.requiredString("acceptance_criterion_ref", gapSource),
          acceptanceCriterionText = gapMap.requiredString("acceptance_criterion_text", gapSource),
          failureEvidence = AuditEvidenceWire.fromWire(gapMap["failure_evidence"], "$gapSource.failure_evidence"),
          diagnosis = gapMap.requiredString("diagnosis", gapSource),
          affectedBoundary = gapMap.requiredString("affected_boundary", gapSource),
          repairItems = gapMap.requiredList("repair_items", gapSource).mapIndexed { itemIndex, item ->
            val itemSource = "$gapSource.repair_items[$itemIndex]"
            val itemMap = item.requiredMap(itemSource)
            requireExactWireKeys(itemMap, itemSource, AUDIT_REPAIR_ITEM_KEYS)
            FeatureTaskRuntimeRepairItem(
              repairItemId = canonicalAuditIdentifier(itemMap.requiredString("repair_item_id", itemSource)),
              intendedOutcome = itemMap.requiredString("intended_outcome", itemSource),
              implementationActions = itemMap.stringList("implementation_actions", itemSource, required = true),
              affectedPathsOrSymbols = itemMap.stringList("affected_paths_or_symbols", itemSource),
              requiredVerification = itemMap.stringList("required_verification", itemSource, required = true),
              dependsOn = itemMap.stringList("depends_on", itemSource).map(::canonicalAuditIdentifier),
              status = when (itemMap.requiredString("status", itemSource)) {
                "pending" -> FeatureTaskRuntimeRepairItemStatus.PENDING
                else -> invalidWire("$itemSource.status", "must be pending")
              },
            )
          },
        )
      },
    )
  }

internal fun auditRepairStateFromWire(value: Any?, source: String): FeatureTaskRuntimeAuditRepairState =
  wireMapping(source) {
    val map = value.requiredMap(source)
    requireExactWireKeys(map, source, AUDIT_REPAIR_STATE_KEYS)
    val acceptedPlans = map.requiredList("accepted_plans", source).mapIndexed { index, plan ->
      durableAuditRepairPlanFromWire(plan, "$source.accepted_plans[$index]")
    }
    val latestPlan = durableAuditRepairPlanFromWire(map["latest_plan"], "$source.latest_plan")
    if (latestPlan != acceptedPlans.last()) {
      invalidWire("$source.latest_plan", "must equal the last accepted plan")
    }
    val results = map.requiredArray("execution_history", source).mapIndexed { index, result ->
      repairItemResultFromWire(result, "$source.execution_history[$index]")
    }
    val dispositions = map.requiredArray("prior_gap_dispositions", source).mapIndexed { index, disposition ->
      priorGapDispositionFromWire(disposition, "$source.prior_gap_dispositions[$index]")
    }
    val ledgerMap = map["unresolved_gap_ledger"].requiredMap("$source.unresolved_gap_ledger")
    requireExactWireKeys(ledgerMap, "$source.unresolved_gap_ledger", AUDIT_REPAIR_LEDGER_KEYS)
    val marksSource = "$source.unresolved_gap_ledger.closed_generation_high_water_marks"
    val marks = ledgerMap["closed_generation_high_water_marks"].requiredMap(marksSource).mapValues { (key, value) ->
      (value as? Number)?.toInt() ?: invalidWire("$marksSource.$key", "must be an integer")
    }
    val unresolved = ledgerMap.requiredArray("gaps", "$source.unresolved_gap_ledger").mapIndexed { index, gap ->
      val gapSource = "$source.unresolved_gap_ledger.gaps[$index]"
      val gapMap = gap.requiredMap(gapSource)
      requireExactWireKeys(gapMap, gapSource, AUDIT_REPAIR_UNRESOLVED_GAP_KEYS)
      val gapId = canonicalAuditIdentifier(gapMap.requiredString("gap_id", gapSource))
      val generation = gapMap.requiredInt("generation", gapSource)
      FeatureTaskRuntimeUnresolvedGap(
        gapId = gapId,
        acceptanceCriterionRef = gapMap.requiredString("acceptance_criterion_ref", gapSource),
        generation = generation,
      )
    }
    val progressMap = map["progress"].requiredMap("$source.progress")
    requireExactWireKeys(progressMap, "$source.progress", AUDIT_REPAIR_PROGRESS_KEYS)
    FeatureTaskRuntimeAuditRepairState(
      acceptedPlans = acceptedPlans,
      repairItemResults = results,
      priorGapDispositions = dispositions,
      unresolvedGapLedger = FeatureTaskRuntimeUnresolvedGapLedger(unresolved, marks),
      repositoryFingerprint = map.optionalString("repository_fingerprint", source),
      progress = FeatureTaskRuntimeAuditRepairProgress(
        firstPassConvergence = progressMap.requiredBoolean("first_pass_convergence", "$source.progress"),
        recurringGapCount = progressMap.requiredInt("recurring_gap_count", "$source.progress"),
        newGapCount = progressMap.requiredInt("new_gap_count", "$source.progress"),
        attemptedRepairItemCount = progressMap.requiredInt("attempted_repair_item_count", "$source.progress"),
        resolvedRepairItemCount = progressMap.requiredInt("resolved_repair_item_count", "$source.progress"),
        auditGapIterationCount = progressMap.requiredInt("audit_gap_iteration_count", "$source.progress"),
      ),
    ).also { it.requireDurableCoherence() }
  }

internal fun repairItemResultFromWire(value: Any?, source: String): FeatureTaskRuntimeRepairItemResult =
  wireMapping(source) {
    val map = value.requiredMap(source)
    requireExactWireKeys(map, source, AUDIT_REPAIR_RESULT_KEYS)
    FeatureTaskRuntimeRepairItemResult(
      repairItemId = canonicalAuditIdentifier(map.requiredString("repair_item_id", source)),
      outcome = when (map.requiredString("outcome", source)) {
        "fixed" -> FeatureTaskRuntimeRepairItemOutcome.FIXED
        "already_satisfied" -> FeatureTaskRuntimeRepairItemOutcome.ALREADY_SATISFIED
        else -> invalidWire(source, "outcome must be fixed or already_satisfied")
      },
      changedPathsOrSymbols = map.stringList("changed_paths_or_symbols", source, required = true),
      executedVerification = map.stringList("executed_verification", source, required = true),
      resultEvidence = AuditEvidenceWire.fromWire(map["result_evidence"], "$source.result_evidence"),
    )
  }

internal fun priorGapDispositionFromWire(value: Any?, source: String): FeatureTaskRuntimePriorGapDisposition {
  val map = value.requiredMap(source)
  requireExactWireKeys(map, source, AUDIT_REPAIR_DISPOSITION_KEYS)
  return FeatureTaskRuntimePriorGapDisposition(
    gapId = canonicalAuditIdentifier(map.requiredString("gap_id", source)),
    status = when (map.requiredString("status", source)) {
      "resolved" -> FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED
      "recurring" -> FeatureTaskRuntimePriorGapDisposition.Status.RECURRING
      else -> invalidWire(source, "status must be resolved or recurring")
    },
    evidence = AuditEvidenceWire.fromWire(map["evidence"], "$source.evidence"),
  )
}

internal fun auditEvidenceFromWire(value: Any?, source: String): FeatureTaskRuntimeEvidence =
  wireMapping(source) { AuditEvidenceWire.fromWire(value, source) }

internal object AuditEvidenceWire {
  private val keys = setOf("observation", "artifact_ref", "check_ref")

  fun fromWire(value: Any?, source: String): FeatureTaskRuntimeEvidence {
    val map = value.requiredMap(source)
    requireExactWireKeys(map, source, keys)
    val observation = when (val wireValue = map.requiredString("observation", source)) {
      "required_behavior_absent" -> FeatureTaskRuntimeEvidence.Observation.REQUIRED_BEHAVIOR_ABSENT
      "verification_failed" -> FeatureTaskRuntimeEvidence.Observation.VERIFICATION_FAILED
      "contract_rejected" -> FeatureTaskRuntimeEvidence.Observation.CONTRACT_REJECTED
      "state_mismatch" -> FeatureTaskRuntimeEvidence.Observation.STATE_MISMATCH
      "fix_verified" -> FeatureTaskRuntimeEvidence.Observation.FIX_VERIFIED
      "already_satisfied_verified" -> FeatureTaskRuntimeEvidence.Observation.ALREADY_SATISFIED_VERIFIED
      "resolution_verified" -> FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED
      "recurrence_verified" -> FeatureTaskRuntimeEvidence.Observation.RECURRENCE_VERIFIED
      else -> invalidWire("$source.observation", "unauthorized evidence observation '$wireValue'")
    }
    return FeatureTaskRuntimeEvidence(
      observation = observation,
      artifactRef = map.requiredString("artifact_ref", source),
      checkRef = map.requiredString("check_ref", source),
    )
  }

  fun toWire(evidence: FeatureTaskRuntimeEvidence): Map<String, String> = mapOf(
    "observation" to evidence.observation.name.lowercase(),
    "artifact_ref" to evidence.artifactRef,
    "check_ref" to evidence.checkRef,
  )
}

private inline fun <T> auditRepairPlanMapping(source: String, block: () -> T): T = try {
  block()
} catch (error: InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError) {
  throw error
} catch (error: InvalidWorkflowStateSchemaError) {
  throw InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError(source, error.message.orEmpty(), error)
} catch (error: IllegalArgumentException) {
  throw InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError(source, error.message.orEmpty(), error)
}

private fun durableAuditRepairPlanFromWire(value: Any?, source: String): FeatureTaskRuntimeAuditRepairPlan = try {
  auditRepairPlanFromWire(value, source)
} catch (error: InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError) {
  throw InvalidWorkflowStateSchemaError("$source: ${error.message}", error)
}

internal fun FeatureTaskRuntimeAuditRepairState.requireDurableCoherence() {
  val acceptedRepairItemIds = acceptedPlans
    .flatMap { plan -> plan.gaps.flatMap(FeatureTaskRuntimeAuditGap::repairItems) }
    .mapTo(linkedSetOf(), FeatureTaskRuntimeRepairItem::repairItemId)
  require(repairItemResults.all { it.repairItemId in acceptedRepairItemIds }) {
    "Durable terminal results must belong to an accepted repair plan."
  }
  require(progress.attemptedRepairItemCount >= repairItemResults.size) {
    "Attempted repair-item count cannot be smaller than compact durable terminal results."
  }
  require(progress.resolvedRepairItemCount >= repairItemResults.size) {
    "Resolved repair-item count cannot be smaller than compact durable terminal results."
  }
  require(progress.recurringGapCount + progress.newGapCount <= unresolvedGapLedger.unresolvedGaps.size) {
    "Recurring and new gap counts cannot exceed the unresolved-gap ledger."
  }
}
