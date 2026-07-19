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
    gaps.forEach { gap ->
      require(GAP_ID.matches(gap.gapId)) {
        "gap_id '${gap.gapId}' must be the stable criterion-generation identifier " +
          "'<criterion-ref>-gap-<generation>'."
      }
      require(gap.gapId.startsWith("${gap.acceptanceCriterionRef.lowercase()}-gap-")) {
        "gap_id '${gap.gapId}' must belong to acceptance criterion '${gap.acceptanceCriterionRef}'."
      }
      gap.repairItems.forEachIndexed { index, item ->
        val expectedId = "${gap.gapId}-item-${index + 1}"
        require(item.repairItemId == expectedId) {
          "repair_item_id '${item.repairItemId}' must be the stable ordered child '$expectedId'."
        }
      }
    }
    val itemOrder = items.mapIndexed { index, item -> item.repairItemId to index }.toMap()
    items.forEach { item ->
      require(
        item.dependsOn.all { dependency -> itemOrder.getValue(dependency) < itemOrder.getValue(item.repairItemId) },
      ) {
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
    val resultOrder = results.mapIndexed { index, result -> result.repairItemId to index }.toMap()
    gaps.flatMap { it.repairItems }.forEach { item ->
      require(
        item.dependsOn.all { dependency -> resultOrder.getValue(dependency) < resultOrder.getValue(item.repairItemId) },
      ) {
        "Repair item result '${item.repairItemId}' must appear after all dependency results."
      }
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
    require(ACCEPTANCE_CRITERION_REF.matches(acceptanceCriterionRef)) {
      "acceptance_criterion_ref '$acceptanceCriterionRef' must use canonical format 'AC-NNN'."
    }
    requireNonBlank(acceptanceCriterionText, "acceptance_criterion_text")
    requireNonBlank(failureEvidence, "failure_evidence")
    requireNonBlank(diagnosis, "diagnosis")
    requireNonBlank(affectedBoundary, "affected_boundary")
    require(repairItems.isNotEmpty()) { "Gap '$gapId' must contain a repair item." }
  }
}

private val ACCEPTANCE_CRITERION_REF = Regex("AC-[0-9]{3}")

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
    requireNonBlankList(changedPathsOrSymbols, "changed_paths_or_symbols")
    requireNonBlankList(executedVerification, "executed_verification")
    requireNonBlank(resultEvidence, "result_evidence")
  }
}

data class FeatureTaskRuntimeUnresolvedGap(
  val gapId: String,
  val acceptanceCriterionRef: String,
  val generation: Int,
) {
  init {
    requireNonBlank(gapId, "gap_id")
    requireNonBlank(acceptanceCriterionRef, "acceptance_criterion_ref")
    require(ACCEPTANCE_CRITERION_REF.matches(acceptanceCriterionRef)) {
      "acceptance_criterion_ref '$acceptanceCriterionRef' must use canonical format 'AC-NNN'."
    }
    require(generation > 0) { "gap generation must be positive." }
    val expectedGapId = "${acceptanceCriterionRef.lowercase()}-gap-$generation"
    require(gapId == expectedGapId) {
      "gap_id '$gapId' must equal the stable criterion-generation identifier '$expectedGapId'."
    }
  }
}

data class FeatureTaskRuntimeUnresolvedGapLedger(
  val unresolvedGaps: List<FeatureTaskRuntimeUnresolvedGap>,
) {
  init {
    requireUnique(unresolvedGaps.map { it.gapId }, "unresolved gap_id")
  }

  fun allocateGapId(criterionRef: String): String {
    requireNonBlank(criterionRef, "acceptance_criterion_ref")
    unresolvedGaps.firstOrNull { it.acceptanceCriterionRef == criterionRef }?.let { return it.gapId }
    val generation = unresolvedGaps.filter { it.acceptanceCriterionRef == criterionRef }
      .maxOfOrNull { it.generation }?.plus(1) ?: 1
    return "${criterionRef.lowercase()}-gap-$generation"
  }
}

data class FeatureTaskRuntimeUnresolvableRepairBlock(
  val gapId: String,
  val repairItemId: String,
  val evidence: String,
) {
  init {
    requireNonBlank(gapId, "gap_id")
    requireNonBlank(repairItemId, "repair_item_id")
    requireNonBlank(evidence, "evidence")
  }
}

data class FeatureTaskRuntimeAuditRepairState(
  val acceptedPlans: List<FeatureTaskRuntimeAuditRepairPlan>,
  val repairItemResults: List<FeatureTaskRuntimeRepairItemResult>,
  val priorGapDispositions: List<FeatureTaskRuntimePriorGapDisposition>,
  val unresolvedGapLedger: FeatureTaskRuntimeUnresolvedGapLedger,
  val repositoryFingerprint: String?,
  val progress: FeatureTaskRuntimeAuditRepairProgress,
) {
  init {
    require(acceptedPlans.isNotEmpty()) { "Audit repair state must retain at least one accepted plan." }
    require(repositoryFingerprint == null || repositoryFingerprint.isNotBlank()) {
      "repository fingerprint must be nonblank when present."
    }
    val unresolvedIds = unresolvedGapLedger.unresolvedGaps.mapTo(linkedSetOf()) { it.gapId }
    val acceptedGapIdentities = acceptedPlans.flatMap { plan ->
      plan.gaps.map { gap ->
        Triple(
          gap.gapId,
          gap.acceptanceCriterionRef,
          gap.gapId.substringAfterLast("-gap-").toInt(),
        )
      }
    }.toSet()
    require(
      unresolvedGapLedger.unresolvedGaps.all { gap ->
        Triple(gap.gapId, gap.acceptanceCriterionRef, gap.generation) in acceptedGapIdentities
      },
    ) { "Every unresolved ledger gap must match an accepted plan gap identity, criterion, and generation." }
    requireUnique(priorGapDispositions.map { it.gapId }, "prior gap disposition gap_id")
    require(
      priorGapDispositions.all { disposition ->
        (disposition.status == FeatureTaskRuntimePriorGapDisposition.Status.RECURRING) ==
          (disposition.gapId in unresolvedIds)
      },
    ) { "Recurring dispositions and the unresolved-gap ledger must agree." }
    require(progress.attemptedRepairItemCount >= repairItemResults.size) {
      "Attempted repair-item count cannot be smaller than durable terminal results."
    }
    require(progress.resolvedRepairItemCount <= progress.attemptedRepairItemCount) {
      "Resolved repair-item count cannot exceed attempted repair-item count."
    }
    require(
      progress.recurringGapCount == priorGapDispositions.count {
        it.status == FeatureTaskRuntimePriorGapDisposition.Status.RECURRING
      },
    ) { "Recurring-gap count must match durable dispositions." }
  }
}

data class FeatureTaskRuntimeAuditRepairProgressDecision(
  val blocked: Boolean,
  val reason: String?,
)

fun detectAuditRepairNonProgress(
  previousGapIds: Set<String>,
  currentGapIds: Set<String>,
  previousRepositoryFingerprint: String,
  currentRepositoryFingerprint: String,
  newlyResolvedRepairItemCount: Int,
): FeatureTaskRuntimeAuditRepairProgressDecision {
  val equivalentGaps = previousGapIds == currentGapIds
  val repositoryUnchanged = previousRepositoryFingerprint == currentRepositoryFingerprint
  val blocked = equivalentGaps && (repositoryUnchanged || newlyResolvedRepairItemCount == 0)
  return FeatureTaskRuntimeAuditRepairProgressDecision(
    blocked = blocked,
    reason = if (blocked) {
      "Audit repair made no progress: unresolved gap identities are unchanged and " +
        if (repositoryUnchanged) "the repository fingerprint is unchanged." else "no repair item was newly resolved."
    } else {
      null
    },
  )
}

data class FeatureTaskRuntimePriorGapDisposition(val gapId: String, val status: Status, val evidence: String) {
  enum class Status { RESOLVED, RECURRING }
  init {
    requireNonBlank(gapId, "gap_id")
    requireNonBlank(evidence, "evidence")
  }
}

data class FeatureTaskRuntimeAuditRepairProgress(
  val firstPassConvergence: Boolean,
  val recurringGapCount: Int,
  val newGapCount: Int,
  val attemptedRepairItemCount: Int,
  val resolvedRepairItemCount: Int,
  val auditGapIterationCount: Int,
) {
  init {
    require(
      listOf(
        recurringGapCount,
        newGapCount,
        attemptedRepairItemCount,
        resolvedRepairItemCount,
        auditGapIterationCount,
      ).all { it >= 0 },
    ) { "Audit-repair progress counters must be non-negative." }
    require(!firstPassConvergence || auditGapIterationCount == 0) {
      "First-pass convergence cannot include an audit-gap iteration."
    }
  }
}

const val AUDIT_REPAIR_CONTRACT_VERSION: String = "0.1"
const val FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY: String = "feature_task_runtime_audit_repair_state"

private val GAP_ID = Regex("ac-[0-9]{3,}-gap-[1-9][0-9]*")

private fun requireNonBlank(value: String, field: String) = require(value.isNotBlank()) { "$field must be nonblank." }
private fun requireNonBlankList(values: List<String>, field: String) {
  require(values.isNotEmpty() && values.all(String::isNotBlank)) { "$field must contain nonblank entries." }
}
private fun requireUnique(values: List<String>, field: String) =
  require(values.size == values.toSet().size) { "$field must be unique." }
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
