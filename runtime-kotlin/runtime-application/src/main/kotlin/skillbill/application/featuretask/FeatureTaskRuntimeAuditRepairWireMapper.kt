package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGapLedger

internal fun auditRepairPlanFromWire(value: Any?, source: String): FeatureTaskRuntimeAuditRepairPlan =
  auditRepairPlanMapping(source) {
    val map = value.requiredMap(source)
    FeatureTaskRuntimeAuditRepairPlan(
      contractVersion = map.requiredString("contract_version", source),
      gaps = map.requiredList("gaps", source).mapIndexed { index, gap ->
        val gapSource = "$source.gaps[$index]"
        val gapMap = gap.requiredMap(gapSource)
        FeatureTaskRuntimeAuditGap(
          gapId = gapMap.requiredString("gap_id", gapSource),
          acceptanceCriterionRef = gapMap.requiredString("acceptance_criterion_ref", gapSource),
          acceptanceCriterionText = gapMap.requiredString("acceptance_criterion_text", gapSource),
          failureEvidence = gapMap.requiredString("failure_evidence", gapSource),
          diagnosis = gapMap.requiredString("diagnosis", gapSource),
          affectedBoundary = gapMap.requiredString("affected_boundary", gapSource),
          repairItems = gapMap.requiredList("repair_items", gapSource).mapIndexed { itemIndex, item ->
            val itemSource = "$gapSource.repair_items[$itemIndex]"
            val itemMap = item.requiredMap(itemSource)
            FeatureTaskRuntimeRepairItem(
              repairItemId = itemMap.requiredString("repair_item_id", itemSource),
              intendedOutcome = itemMap.requiredString("intended_outcome", itemSource),
              implementationActions = itemMap.stringList("implementation_actions", itemSource, required = true),
              affectedPathsOrSymbols = itemMap.stringList("affected_paths_or_symbols", itemSource),
              requiredVerification = itemMap.stringList("required_verification", itemSource, required = true),
              dependsOn = itemMap.stringList("depends_on", itemSource),
            )
          },
        )
      },
    )
  }

internal fun auditRepairStateFromWire(value: Any?, source: String): FeatureTaskRuntimeAuditRepairState =
  wireMapping(source) {
    val map = value.requiredMap(source)
    val contractVersion = map.requiredString("contract_version", source)
    if (contractVersion != AUDIT_REPAIR_CONTRACT_VERSION) {
      invalidWire("$source.contract_version", "must be $AUDIT_REPAIR_CONTRACT_VERSION")
    }
    val acceptedPlans = map.requiredList("accepted_plans", source).mapIndexed { index, plan ->
      auditRepairPlanFromWire(plan, "$source.accepted_plans[$index]")
    }
    val latestPlan = auditRepairPlanFromWire(map["latest_plan"], "$source.latest_plan")
    if (latestPlan != acceptedPlans.last()) {
      invalidWire("$source.latest_plan", "must equal the last accepted plan")
    }
    val results = flattenWireEntries(map["execution_history"]).mapIndexed { index, result ->
      repairItemResultFromWire(result, "$source.execution_history[$index]")
    }
    val dispositions = map.optionalList("prior_gap_dispositions", source).mapIndexed { index, disposition ->
      priorGapDispositionFromWire(disposition, "$source.prior_gap_dispositions[$index]")
    }
    val ledgerMap = map["unresolved_gap_ledger"].requiredMap("$source.unresolved_gap_ledger")
    val ledgerContractVersion = ledgerMap.requiredString("contract_version", "$source.unresolved_gap_ledger")
    if (ledgerContractVersion != AUDIT_REPAIR_CONTRACT_VERSION) {
      invalidWire(
        "$source.unresolved_gap_ledger.contract_version",
        "must be $AUDIT_REPAIR_CONTRACT_VERSION",
      )
    }
    val unresolved = ledgerMap.optionalList("gaps", "$source.unresolved_gap_ledger").mapIndexed { index, gap ->
      val gapSource = "$source.unresolved_gap_ledger.gaps[$index]"
      val gapMap = gap.requiredMap(gapSource)
      val gapId = gapMap.requiredString("gap_id", gapSource)
      val generation = gapMap.int("generation")
        ?: invalidWire("$gapSource.generation", "must be an integer")
      FeatureTaskRuntimeUnresolvedGap(
        gapId = gapId,
        acceptanceCriterionRef = gapMap.requiredString("acceptance_criterion_ref", gapSource),
        generation = generation,
      ).also {
        if (gapId.substringAfterLast('-').toIntOrNull() != generation) {
          invalidWire(gapSource, "gap_id generation must match generation")
        }
      }
    }
    val progressMap = map["progress"]?.requiredMap("$source.progress")
    val recurringCount = dispositions.count { it.status == FeatureTaskRuntimePriorGapDisposition.Status.RECURRING }
    FeatureTaskRuntimeAuditRepairState(
      acceptedPlans = acceptedPlans,
      repairItemResults = results,
      priorGapDispositions = dispositions,
      unresolvedGapLedger = FeatureTaskRuntimeUnresolvedGapLedger(unresolved),
      repositoryFingerprint = map["repository_fingerprint"] as? String,
      progress = FeatureTaskRuntimeAuditRepairProgress(
        firstPassConvergence = progressMap?.get("first_pass_convergence") as? Boolean ?: false,
        recurringGapCount = progressMap?.int("recurring_gap_count") ?: recurringCount,
        newGapCount = progressMap?.int("new_gap_count") ?: acceptedPlans.last().gaps.size,
        attemptedRepairItemCount = progressMap?.int("attempted_repair_item_count") ?: results.size,
        resolvedRepairItemCount = progressMap?.int("resolved_repair_item_count") ?: results.size,
        auditGapIterationCount = progressMap?.int("audit_gap_iteration_count") ?: 0,
      ),
    )
  }

internal fun auditRepairPlanToWire(plan: FeatureTaskRuntimeAuditRepairPlan): Map<String, Any?> = mapOf(
  "contract_version" to plan.contractVersion,
  "gaps" to plan.gaps.map { gap ->
    mapOf(
      "gap_id" to gap.gapId,
      "acceptance_criterion_ref" to gap.acceptanceCriterionRef,
      "acceptance_criterion_text" to gap.acceptanceCriterionText,
      "failure_evidence" to gap.failureEvidence,
      "diagnosis" to gap.diagnosis,
      "affected_boundary" to gap.affectedBoundary,
      "repair_items" to gap.repairItems.map { item ->
        mapOf(
          "repair_item_id" to item.repairItemId,
          "intended_outcome" to item.intendedOutcome,
          "implementation_actions" to item.implementationActions,
          "affected_paths_or_symbols" to item.affectedPathsOrSymbols,
          "required_verification" to item.requiredVerification,
          "depends_on" to item.dependsOn,
          "status" to "pending",
        )
      },
    )
  },
)

internal fun auditRepairStateToWire(state: FeatureTaskRuntimeAuditRepairState): Map<String, Any?> = mapOf(
  "contract_version" to AUDIT_REPAIR_CONTRACT_VERSION,
  "accepted_plans" to state.acceptedPlans.map(::auditRepairPlanToWire),
  "latest_plan" to auditRepairPlanToWire(state.acceptedPlans.last()),
  "execution_history" to state.repairItemResults.map(::repairItemResultToWire),
  "prior_gap_dispositions" to state.priorGapDispositions.map { disposition ->
    mapOf(
      "gap_id" to disposition.gapId,
      "status" to disposition.status.name.lowercase(),
      "evidence" to disposition.evidence,
    )
  },
  "unresolved_gap_ledger" to mapOf(
    "contract_version" to AUDIT_REPAIR_CONTRACT_VERSION,
    "gaps" to state.unresolvedGapLedger.unresolvedGaps.map { gap ->
      mapOf(
        "gap_id" to gap.gapId,
        "acceptance_criterion_ref" to gap.acceptanceCriterionRef,
        "generation" to gap.generation,
      )
    },
  ),
  "repository_fingerprint" to state.repositoryFingerprint,
  "progress" to mapOf(
    "first_pass_convergence" to state.progress.firstPassConvergence,
    "recurring_gap_count" to state.progress.recurringGapCount,
    "new_gap_count" to state.progress.newGapCount,
    "attempted_repair_item_count" to state.progress.attemptedRepairItemCount,
    "resolved_repair_item_count" to state.progress.resolvedRepairItemCount,
    "audit_gap_iteration_count" to state.progress.auditGapIterationCount,
  ),
)

internal fun repairItemResultFromWire(value: Any?, source: String): FeatureTaskRuntimeRepairItemResult =
  wireMapping(source) {
    val map = value.requiredMap(source)
    FeatureTaskRuntimeRepairItemResult(
      repairItemId = map.requiredString("repair_item_id", source),
      outcome = when (map.requiredString("outcome", source)) {
        "fixed" -> FeatureTaskRuntimeRepairItemOutcome.FIXED
        "already_satisfied" -> FeatureTaskRuntimeRepairItemOutcome.ALREADY_SATISFIED
        else -> invalidWire(source, "outcome must be fixed or already_satisfied")
      },
      changedPathsOrSymbols = map.stringList("changed_paths_or_symbols", source, required = true),
      executedVerification = map.stringList("executed_verification", source, required = true),
      resultEvidence = map.requiredString("result_evidence", source),
    )
  }

internal fun priorGapDispositionFromWire(value: Any?, source: String): FeatureTaskRuntimePriorGapDisposition {
  val map = value.requiredMap(source)
  return FeatureTaskRuntimePriorGapDisposition(
    gapId = map.requiredString("gap_id", source),
    status = when (map.requiredString("status", source)) {
      "resolved" -> FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED
      "recurring" -> FeatureTaskRuntimePriorGapDisposition.Status.RECURRING
      else -> invalidWire(source, "status must be resolved or recurring")
    },
    evidence = map.requiredString("evidence", source),
  )
}

private fun repairItemResultToWire(result: FeatureTaskRuntimeRepairItemResult): Map<String, Any?> = mapOf(
  "repair_item_id" to result.repairItemId,
  "outcome" to result.outcome.name.lowercase(),
  "changed_paths_or_symbols" to result.changedPathsOrSymbols,
  "executed_verification" to result.executedVerification,
  "result_evidence" to result.resultEvidence,
)

private fun flattenWireEntries(value: Any?): List<Any?> = (value as? List<*>).orEmpty().flatMap { entry ->
  if (entry is List<*>) entry else listOf(entry)
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
