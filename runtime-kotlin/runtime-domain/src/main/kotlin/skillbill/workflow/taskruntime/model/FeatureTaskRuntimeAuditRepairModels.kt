package skillbill.workflow.taskruntime.model

data class FeatureTaskRuntimeAuditRepairPlan(
  val contractVersion: String,
  val gaps: List<FeatureTaskRuntimeAuditGap>,
) {
  init {
    require(contractVersion == AUDIT_REPAIR_CONTRACT_VERSION) { "Unsupported audit repair contract version." }
    require(gaps.isNotEmpty()) { "An audit repair plan must contain at least one gap." }
    requireUnique(gaps.map { it.gapId }, "gap_id")
    requireUnique(gaps.map { it.acceptanceCriterionRef }, "acceptance_criterion_ref")
    val items = gaps.flatMap { it.repairItems }
    requireUnique(items.map { it.repairItemId }, "repair_item_id")
    val ids = items.mapTo(linkedSetOf()) { it.repairItemId }
    items.forEach { item ->
      require(item.dependsOn.all(ids::contains)) { "Repair item '${item.repairItemId}' has an unknown dependency." }
      require(item.repairItemId !in item.dependsOn) { "Repair item '${item.repairItemId}' depends on itself." }
    }
    requireAcyclic(items)
    val itemOrder = items.mapIndexed { index, item -> item.repairItemId to index }.toMap()
    items.forEach { item ->
      require(item.dependsOn.all { dependency -> itemOrder.getValue(dependency) < itemOrder.getValue(item.repairItemId) }) {
        "Repair item '${item.repairItemId}' must appear after all of its dependencies."
      }
    }
  }

  fun requireExactCriterionCoverage(reportedCriterionRefs: List<String>) {
    requireUnique(reportedCriterionRefs, "reported acceptance criterion")
    require(reportedCriterionRefs.toSet() == gaps.mapTo(linkedSetOf()) { it.acceptanceCriterionRef }) {
      "Every reported unmet criterion must map exactly once to a declared audit gap."
    }
  }

  fun requireTerminalResults(results: List<FeatureTaskRuntimeRepairItemResult>) {
    requireUnique(results.map { it.repairItemId }, "repair_item_result.repair_item_id")
    val expected = gaps.flatMap { it.repairItems }.mapTo(linkedSetOf()) { it.repairItemId }
    require(results.mapTo(linkedSetOf()) { it.repairItemId } == expected) {
      "Repair item results must have exact identifier set equality with the accepted repair plan."
    }
  }
}

data class FeatureTaskRuntimeAuditGap(
  val gapId: String,
  val acceptanceCriterionRef: String,
  val acceptanceCriterionText: String,
  val failureEvidence: String,
  val diagnosis: String,
  val affectedBoundary: String,
  val repairItems: List<FeatureTaskRuntimeRepairItem>,
) {
  init {
    requireNonBlank(gapId, "gap_id")
    requireNonBlank(acceptanceCriterionRef, "acceptance_criterion_ref")
    requireNonBlank(acceptanceCriterionText, "acceptance_criterion_text")
    requireNonBlank(failureEvidence, "failure_evidence")
    requireNonBlank(diagnosis, "diagnosis")
    requireNonBlank(affectedBoundary, "affected_boundary")
    require(repairItems.isNotEmpty()) { "Gap '$gapId' must contain a repair item." }
  }
}

data class FeatureTaskRuntimeRepairItem(
  val repairItemId: String,
  val intendedOutcome: String,
  val implementationActions: List<String>,
  val affectedPathsOrSymbols: List<String>,
  val requiredVerification: List<String>,
  val dependsOn: List<String>,
  val status: FeatureTaskRuntimeRepairItemStatus = FeatureTaskRuntimeRepairItemStatus.PENDING,
) {
  init {
    requireNonBlank(repairItemId, "repair_item_id")
    requireNonBlank(intendedOutcome, "intended_outcome")
    requireNonBlankList(implementationActions, "implementation_actions")
    require(affectedPathsOrSymbols.all(String::isNotBlank)) { "affected_paths_or_symbols entries must be nonblank." }
    requireNonBlankList(requiredVerification, "required_verification")
    require(dependsOn.all(String::isNotBlank)) { "depends_on entries must be nonblank." }
    require(status == FeatureTaskRuntimeRepairItemStatus.PENDING) { "Accepted repair items must initially be pending." }
  }
}

enum class FeatureTaskRuntimeRepairItemStatus { PENDING }
enum class FeatureTaskRuntimeRepairItemOutcome { FIXED, ALREADY_SATISFIED }

data class FeatureTaskRuntimeRepairItemResult(
  val repairItemId: String,
  val outcome: FeatureTaskRuntimeRepairItemOutcome,
  val changedPathsOrSymbols: List<String>,
  val executedVerification: List<String>,
  val resultEvidence: String,
) {
  init {
    requireNonBlank(repairItemId, "repair_item_id")
    require(changedPathsOrSymbols.all(String::isNotBlank)) { "changed_paths_or_symbols entries must be nonblank." }
    requireNonBlankList(executedVerification, "executed_verification")
    requireNonBlank(resultEvidence, "result_evidence")
  }
}

data class FeatureTaskRuntimePriorGapDisposition(val gapId: String, val status: Status, val evidence: String) {
  enum class Status { RESOLVED, RECURRING }
  init { requireNonBlank(gapId, "gap_id"); requireNonBlank(evidence, "evidence") }
}

data class FeatureTaskRuntimeAuditRepairProgress(
  val firstPassConvergence: Boolean,
  val recurringGapCount: Int,
  val newGapCount: Int,
  val attemptedRepairItemCount: Int,
  val resolvedRepairItemCount: Int,
  val auditGapIterationCount: Int,
)

const val AUDIT_REPAIR_CONTRACT_VERSION: String = "0.1"
const val FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY: String = "feature_task_runtime_audit_repair_state"

private fun requireNonBlank(value: String, field: String) = require(value.isNotBlank()) { "$field must be nonblank." }
private fun requireNonBlankList(values: List<String>, field: String) {
  require(values.isNotEmpty() && values.all(String::isNotBlank)) { "$field must contain nonblank entries." }
}
private fun requireUnique(values: List<String>, field: String) = require(values.size == values.toSet().size) { "$field must be unique." }
private fun requireAcyclic(items: List<FeatureTaskRuntimeRepairItem>) {
  val byId = items.associateBy { it.repairItemId }
  val visiting = mutableSetOf<String>()
  val visited = mutableSetOf<String>()
  fun visit(id: String) {
    if (id in visited) return
    require(visiting.add(id)) { "Repair item dependencies must be acyclic (cycle at '$id')." }
    byId.getValue(id).dependsOn.forEach(::visit)
    visiting.remove(id)
    visited.add(id)
  }
  byId.keys.forEach(::visit)
}
