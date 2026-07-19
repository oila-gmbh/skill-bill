package skillbill.workflow.taskruntime.model

data class FeatureTaskRuntimeAuditRepairPlan(
  val contractVersion: String,
  val gaps: List<FeatureTaskRuntimeAuditGap>,
) {
  init {
    require(contractVersion == AUDIT_REPAIR_CONTRACT_VERSION) {
      "contract_version must be '$AUDIT_REPAIR_CONTRACT_VERSION', was '$contractVersion'."
    }
    require(gaps.isNotEmpty()) { "An audit repair plan must contain at least one gap, gaps was empty." }
    require(gaps.size <= MAX_AUDIT_REPAIR_GAPS) {
      "An audit repair plan allows at most $MAX_AUDIT_REPAIR_GAPS gaps, had ${gaps.size}."
    }
    requireUnique(gaps.map { it.gapId }, "gap_id")
    requireUnique(gaps.map { it.acceptanceCriterionRef }, "acceptance_criterion_ref")
    val items = gaps.flatMap { it.repairItems }
    require(items.size <= MAX_AUDIT_REPAIR_ITEMS) {
      "An audit repair plan allows at most $MAX_AUDIT_REPAIR_ITEMS repair items in total, had ${items.size}."
    }
    requireUnique(items.map { it.repairItemId }, "repair_item_id")
    val ids = items.mapTo(linkedSetOf()) { it.repairItemId }
    items.forEach { item ->
      require(item.dependsOn.all(ids::contains)) {
        "Repair item '${item.repairItemId}' depends on unknown items ${(item.dependsOn - ids).sorted()}; " +
          "declared items are ${ids.sorted()}."
      }
      require(item.repairItemId !in item.dependsOn) { "Repair item '${item.repairItemId}' depends on itself." }
    }
    requireAcyclic(items)
    gaps.forEach { gap ->
      require(GAP_ID.matches(gap.gapId)) {
        "gap_id '${gap.gapId}' must be the stable criterion-generation identifier " +
          "'<criterion-ref>-gap-<generation>' in canonical lowercase, for example " +
          "'${gap.acceptanceCriterionRef.lowercase()}-gap-1'."
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
      item.dependsOn.forEach { dependency ->
        require(itemOrder.getValue(dependency) < itemOrder.getValue(item.repairItemId)) {
          "Repair item '${item.repairItemId}' (index ${itemOrder.getValue(item.repairItemId)}) must appear after " +
            "its dependency '$dependency' (index ${itemOrder.getValue(dependency)})."
        }
      }
    }
  }

  fun requireExactCriterionCoverage(reportedCriterionRefs: List<String>) {
    requireUnique(reportedCriterionRefs, "reported acceptance criterion")
    val expected = gaps.mapTo(linkedSetOf()) { it.acceptanceCriterionRef }
    val reported = reportedCriterionRefs.toSet()
    require(reported == expected) {
      "Every reported unmet criterion must map exactly once to a declared audit gap; " +
        setDifferenceDetail(expected, reported)
    }
  }

  fun requireTerminalResults(results: List<FeatureTaskRuntimeRepairItemResult>) {
    requireUnique(results.map { it.repairItemId }, "repair_item_result.repair_item_id")
    val expected = gaps.flatMap { it.repairItems }.mapTo(linkedSetOf()) { it.repairItemId }
    val actual = results.mapTo(linkedSetOf()) { it.repairItemId }
    require(actual == expected) {
      "repair_item_results must report exactly the accepted plan's repair items; " +
        setDifferenceDetail(expected, actual)
    }
    val resultOrder = results.mapIndexed { index, result -> result.repairItemId to index }.toMap()
    gaps.flatMap { it.repairItems }.forEach { item ->
      item.dependsOn.forEach { dependency ->
        require(resultOrder.getValue(dependency) < resultOrder.getValue(item.repairItemId)) {
          "Repair item result '${item.repairItemId}' (index ${resultOrder.getValue(item.repairItemId)}) must " +
            "appear after its dependency '$dependency' (index ${resultOrder.getValue(dependency)})."
        }
      }
    }
  }

  private fun setDifferenceDetail(expected: Set<String>, actual: Set<String>): String {
    val missing = (expected - actual).sorted()
    val unexpected = (actual - expected).sorted()
    return "missing=$missing unexpected=$unexpected expected=${expected.sorted()}."
  }
}

data class FeatureTaskRuntimeAuditGap(
  val gapId: String,
  val acceptanceCriterionRef: String,
  val acceptanceCriterionText: String,
  val failureEvidence: FeatureTaskRuntimeEvidence,
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
    requireNonBlank(diagnosis, "diagnosis")
    requireNonBlank(affectedBoundary, "affected_boundary")
    require(repairItems.isNotEmpty()) { "Gap '$gapId' must contain at least one repair item, repair_items was empty." }
    require(repairItems.size <= MAX_AUDIT_REPAIR_ITEMS) {
      "Gap '$gapId' allows at most $MAX_AUDIT_REPAIR_ITEMS repair items, had ${repairItems.size}."
    }
  }
}

data class FeatureTaskRuntimeEvidence(
  val observation: Observation,
  val artifactRef: String,
  val checkRef: String,
) {
  enum class Observation {
    REQUIRED_BEHAVIOR_ABSENT,
    VERIFICATION_FAILED,
    CONTRACT_REJECTED,
    STATE_MISMATCH,
    FIX_VERIFIED,
    ALREADY_SATISFIED_VERIFIED,
    RESOLUTION_VERIFIED,
    RECURRENCE_VERIFIED,
  }

  init {
    require(SAFE_ARTIFACT_REF.matches(artifactRef)) {
      "artifact_ref '$artifactRef' must be a bounded path or symbol reference such as " +
        "src/main/Example.kt or src/main/Example.kt:Example."
    }
    require(SAFE_CHECK_REF.matches(checkRef)) {
      "check_ref '$checkRef' must match AC-###, F-###, or a name ending in Test or Check, optionally followed " +
        "by :symbol; examples: AC-005, FeatureTaskRuntimeAuditEntryGateTest, or codeCheck:detekt."
    }
  }
}

private val SAFE_ARTIFACT_REF = Regex("(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.#-]+)?")
private val SAFE_CHECK_REF = Regex(
  "(?:AC-[0-9]{3}|F-[0-9]{3}|[A-Za-z][A-Za-z0-9_.-]*(?:Test|Check)(?::[A-Za-z0-9_.#-]+)?)",
)

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
    requireCompactList(implementationActions, "implementation_actions", MAX_COMPACT_LIST_ITEMS)
    require(affectedPathsOrSymbols.all(String::isNotBlank)) { "affected_paths_or_symbols entries must be nonblank." }
    requireCompactList(affectedPathsOrSymbols, "affected_paths_or_symbols", MAX_PATH_LIST_ITEMS)
    requireNonBlankList(requiredVerification, "required_verification")
    requireCompactList(requiredVerification, "required_verification", MAX_COMPACT_LIST_ITEMS)
    require(dependsOn.size <= MAX_COMPACT_LIST_ITEMS) { "depends_on exceeds the durable item limit." }
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
  val resultEvidence: FeatureTaskRuntimeEvidence,
) {
  init {
    requireNonBlank(repairItemId, "repair_item_id")
    requireNonBlankList(changedPathsOrSymbols, "changed_paths_or_symbols")
    requireNonBlankList(executedVerification, "executed_verification")
    requireCompactList(changedPathsOrSymbols, "changed_paths_or_symbols", MAX_PATH_LIST_ITEMS)
    requireCompactList(executedVerification, "executed_verification", MAX_COMPACT_LIST_ITEMS)
    val expectedObservation = when (outcome) {
      FeatureTaskRuntimeRepairItemOutcome.FIXED -> FeatureTaskRuntimeEvidence.Observation.FIX_VERIFIED
      FeatureTaskRuntimeRepairItemOutcome.ALREADY_SATISFIED ->
        FeatureTaskRuntimeEvidence.Observation.ALREADY_SATISFIED_VERIFIED
    }
    require(resultEvidence.observation == expectedObservation) {
      "result_evidence.observation must be '${expectedObservation.wire()}' when outcome is " +
        "'${outcome.wire()}', was '${resultEvidence.observation.wire()}'; outcome 'fixed' pairs with " +
        "'fix_verified' and 'already_satisfied' pairs with 'already_satisfied_verified'."
    }
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
    require(generation > 0) { "gap generation must be positive, was $generation." }
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
    require(unresolvedGaps.size <= MAX_AUDIT_REPAIR_GAPS) {
      "Unresolved-gap ledger allows at most $MAX_AUDIT_REPAIR_GAPS gaps, had ${unresolvedGaps.size}."
    }
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
  val evidence: FeatureTaskRuntimeEvidence,
) {
  init {
    requireNonBlank(gapId, "gap_id")
    requireNonBlank(repairItemId, "repair_item_id")
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
    require(acceptedPlans.isNotEmpty()) { "Audit repair state must retain at least one accepted plan, had none." }
    require(acceptedPlans.size == 1) {
      "Durable audit repair state retains exactly the latest accepted plan, had ${acceptedPlans.size}; " +
        "historical identity lives in the gap ledger."
    }
    require(repairItemResults.size <= MAX_AUDIT_REPAIR_ITEMS) {
      "Durable terminal-result history allows at most $MAX_AUDIT_REPAIR_ITEMS results, had ${repairItemResults.size}."
    }
    require(priorGapDispositions.size <= MAX_AUDIT_REPAIR_GAPS) {
      "Durable gap dispositions allow at most $MAX_AUDIT_REPAIR_GAPS entries, had ${priorGapDispositions.size}."
    }
    require(repositoryFingerprint == null || repositoryFingerprint.isNotBlank()) {
      "repository_fingerprint must be nonblank when present, was blank."
    }
    require(repositoryFingerprint == null || repositoryFingerprint.length <= MAX_REPOSITORY_FINGERPRINT_LENGTH) {
      "repository_fingerprint allows at most $MAX_REPOSITORY_FINGERPRINT_LENGTH characters, " +
        "had ${repositoryFingerprint?.length}."
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
    val unmatchedLedgerGaps = unresolvedGapLedger.unresolvedGaps.filterNot { gap ->
      Triple(gap.gapId, gap.acceptanceCriterionRef, gap.generation) in acceptedGapIdentities
    }
    require(unmatchedLedgerGaps.isEmpty()) {
      "Every unresolved ledger gap must match an accepted plan gap identity, criterion, and generation; " +
        "unmatched=${unmatchedLedgerGaps.map { it.gapId }.sorted()} " +
        "accepted=${acceptedGapIdentities.map { it.first }.sorted()}."
    }
    requireUnique(priorGapDispositions.map { it.gapId }, "prior gap disposition gap_id")
    val disagreeingDispositions = priorGapDispositions.filter { disposition ->
      (disposition.status == FeatureTaskRuntimePriorGapDisposition.Status.RECURRING) !=
        (disposition.gapId in unresolvedIds)
    }
    require(disagreeingDispositions.isEmpty()) {
      "A disposition is 'recurring' exactly when its gap is in the unresolved-gap ledger; " +
        "disagreeing=${disagreeingDispositions.map { "${it.gapId}:${it.status.wire()}" }.sorted()} " +
        "ledger=${unresolvedIds.sorted()}."
    }
    require(progress.attemptedRepairItemCount >= repairItemResults.size) {
      "progress.attempted_repair_item_count (${progress.attemptedRepairItemCount}) cannot be smaller than the " +
        "${repairItemResults.size} durable terminal results."
    }
    require(progress.resolvedRepairItemCount <= progress.attemptedRepairItemCount) {
      "progress.resolved_repair_item_count (${progress.resolvedRepairItemCount}) cannot exceed " +
        "attempted_repair_item_count (${progress.attemptedRepairItemCount})."
    }
    require(progress.resolvedRepairItemCount == progress.attemptedRepairItemCount) {
      "Durable terminal repair counters must record every attempted item as resolved; " +
        "resolved=${progress.resolvedRepairItemCount} attempted=${progress.attemptedRepairItemCount}."
    }
    val recurringDispositionCount = priorGapDispositions.count {
      it.status == FeatureTaskRuntimePriorGapDisposition.Status.RECURRING
    }
    require(progress.recurringGapCount == recurringDispositionCount) {
      "progress.recurring_gap_count (${progress.recurringGapCount}) must match the $recurringDispositionCount " +
        "durable dispositions with status 'recurring'."
    }
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

data class FeatureTaskRuntimePriorGapDisposition(
  val gapId: String,
  val status: Status,
  val evidence: FeatureTaskRuntimeEvidence,
) {
  enum class Status { RESOLVED, RECURRING }
  init {
    requireNonBlank(gapId, "gap_id")
    val expectedObservation = when (status) {
      Status.RESOLVED -> FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED
      Status.RECURRING -> FeatureTaskRuntimeEvidence.Observation.RECURRENCE_VERIFIED
    }
    require(evidence.observation == expectedObservation) {
      "Prior gap disposition '$gapId' evidence.observation must be '${expectedObservation.wire()}' when " +
        "status is '${status.wire()}', was '${evidence.observation.wire()}'; status 'resolved' pairs with " +
        "'resolution_verified' and 'recurring' pairs with 'recurrence_verified'."
    }
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
    val negativeCounters = mapOf(
      "recurring_gap_count" to recurringGapCount,
      "new_gap_count" to newGapCount,
      "attempted_repair_item_count" to attemptedRepairItemCount,
      "resolved_repair_item_count" to resolvedRepairItemCount,
      "audit_gap_iteration_count" to auditGapIterationCount,
    ).filterValues { it < 0 }
    require(negativeCounters.isEmpty()) {
      "Audit-repair progress counters must be non-negative, was $negativeCounters."
    }
    require(!firstPassConvergence || auditGapIterationCount == 0) {
      "first_pass_convergence is true, so audit_gap_iteration_count must be 0, was $auditGapIterationCount."
    }
  }
}

const val AUDIT_REPAIR_CONTRACT_VERSION: String = "0.2"
const val FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY: String = "feature_task_runtime_audit_repair_state"
const val MAX_AUDIT_REPAIR_TEXT_LENGTH: Int = 2048
const val MAX_AUDIT_REPAIR_GAPS: Int = 50
const val MAX_AUDIT_REPAIR_ITEMS: Int = 100
const val MAX_COMPACT_LIST_ITEMS: Int = 50
const val MAX_PATH_LIST_ITEMS: Int = 100
const val MAX_REPOSITORY_FINGERPRINT_LENGTH: Int = 256

private val GAP_ID = Regex("ac-[0-9]{3,}-gap-[1-9][0-9]*")

// Criterion refs are canonically uppercase (`AC-005`) while the identifiers derived from them are
// canonically lowercase, so an agent transcribing a ref into a gap_id emits a case the domain would
// otherwise reject with no way to discover the rule. Ingest seams canonicalize instead.
fun canonicalAuditIdentifier(rawIdentifier: String): String = rawIdentifier.trim().lowercase()

// Durable payloads are authored by an agent against the snake_case wire contract, so rejection
// messages must name values the way that contract spells them.
internal fun Enum<*>.wire(): String = name.lowercase()

// A rejection message quotes the offending value so the author can find it, but the value may be the
// oversized payload that was just rejected, so the excerpt stays bounded and single-line.
private const val REJECTION_PREVIEW_CHARS: Int = 80

private fun String.preview(): String {
  val flattened = map { if (it.isISOControl()) ' ' else it }.joinToString("")
  return if (flattened.length <= REJECTION_PREVIEW_CHARS) {
    flattened
  } else {
    flattened.take(REJECTION_PREVIEW_CHARS) + "…"
  }
}

private fun requireNonBlank(value: String, field: String) {
  require(value.isNotBlank()) { "$field must be nonblank." }
  requireDurableText(value, field)
}
private fun requireNonBlankList(values: List<String>, field: String) {
  require(values.isNotEmpty()) { "$field must contain at least one entry, was empty." }
  require(values.all(String::isNotBlank)) {
    "$field entries must be nonblank; blank at ${values.indexOfFirst(String::isBlank)}."
  }
}
private fun requireCompactList(values: List<String>, field: String, maximumItems: Int) {
  require(values.size <= maximumItems) {
    "$field allows at most $maximumItems entries, had ${values.size}."
  }
  values.forEach { requireDurableText(it, "$field entry") }
}

// Every durable text field either describes a code defect or names the work that repairs it, so all of
// them must be able to carry symbols and commands: `=`, `[]`, and `<>` are ordinary content
// (`--tests=`, `results[0]`, `List<String>`). Rejecting that punctuation made the fields unsatisfiable
// for their own purpose. Pasted payloads are excluded structurally instead. The identical rule lives in
// `compactSummary` in feature-task-runtime-audit-repair-plan-schema.yaml, pinned by
// FeatureTaskRuntimeAuditRepairSchemaParityTest.
private fun requireDurableText(value: String, field: String) {
  require(value.length <= MAX_AUDIT_REPAIR_TEXT_LENGTH) {
    "$field allows at most $MAX_AUDIT_REPAIR_TEXT_LENGTH characters, had ${value.length}."
  }
  require(value.none(Char::isISOControl)) {
    "$field must be a single-line durable value; \"${value.preview()}\" contains a line break or control character."
  }
  require(value.none { it == '`' }) {
    "$field must not contain code-fence or quoted-source syntax; remove the backticks from \"${value.preview()}\"."
  }
  require(!SERIALIZED_PAYLOAD.containsMatchIn(value)) {
    "$field must be a short single-line description, not serialized, patch, or tool-output syntax; " +
      "\"${value.preview()}\" looks like a pasted payload."
  }
  require(!SUMMARY_ROLE_PREFIX.containsMatchIn(value)) {
    "$field must not contain a prompt transcript; \"${value.preview()}\" starts with a role prefix."
  }
}
private val SERIALIZED_PAYLOAD = Regex(
  "\\{\\s*\"|\"\\s*:\\s*[\\[{\"]|@@[^@]*@@|^(?:diff --git|\\+\\+\\+ |--- )",
)
private val SUMMARY_ROLE_PREFIX = Regex(
  "(?i)^\\s*(system|user|assistant|developer|tool)(?:\\s+(?:prompt|message|output))?\\s*:",
)
private fun requireUnique(values: List<String>, field: String) {
  val duplicates = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
  require(duplicates.isEmpty()) { "$field must be unique, duplicated $duplicates." }
}
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
