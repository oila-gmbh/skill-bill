package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_PLAN_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimeRepairPlanError

const val REPAIR_PLAN_MAX_ENTRIES: Int = 50
const val REPAIR_PLAN_MAX_ROOT_CAUSE_UTF8_BYTES: Int = 512
const val REPAIR_PLAN_MAX_MINIMAL_CHANGE_UTF8_BYTES: Int = 512

enum class FeatureTaskRuntimeRepairClassification(val wireValue: String) {
  LOCAL_PATCH_SITE("local_patch_site"),
  DESIGN_SYMPTOM("design_symptom"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeRepairClassification = entries.firstOrNull { it.wireValue == value }
      ?: repairPlanError("classification", "must be one of ${entries.joinToString { it.wireValue }}.")
  }
}

data class FeatureTaskRuntimeRepairPlanEntry(
  val findingRef: String,
  val rootCause: String,
  val minimalChange: String,
  val classification: FeatureTaskRuntimeRepairClassification,
  val priorRoundRemedyRef: String? = null,
) {
  init {
    requireRepairPlanIdentity(findingRef, "finding_ref")
    requireRepairPlanText(rootCause, "root_cause", REPAIR_PLAN_MAX_ROOT_CAUSE_UTF8_BYTES)
    requireRepairPlanText(minimalChange, "minimal_change", REPAIR_PLAN_MAX_MINIMAL_CHANGE_UTF8_BYTES)
    priorRoundRemedyRef?.let { requireRepairPlanIdentity(it, "prior_round_remedy_ref") }
    if (
      classification == FeatureTaskRuntimeRepairClassification.LOCAL_PATCH_SITE &&
      priorRoundRemedyRef != null
    ) {
      repairPlanError(
        "prior_round_remedy_ref",
        "is only recorded when the finding is classified a design_symptom of an earlier round's remedy.",
      )
    }
  }

  val isDesignSymptom: Boolean get() = classification == FeatureTaskRuntimeRepairClassification.DESIGN_SYMPTOM

  @OpenBoundaryMap("Repair plan entry at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "finding_ref" to findingRef,
    "root_cause" to rootCause,
    "minimal_change" to minimalChange,
    "classification" to classification.wireValue,
  ).apply { priorRoundRemedyRef?.let { put("prior_round_remedy_ref", it) } }

  companion object {
    @OpenBoundaryMap("Repair plan entry decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): FeatureTaskRuntimeRepairPlanEntry {
      raw.requireOnlyReviewStateKeys(
        setOf("finding_ref", "root_cause", "minimal_change", "classification", "prior_round_remedy_ref"),
        path,
      )
      return FeatureTaskRuntimeRepairPlanEntry(
        findingRef = raw.requireReviewStateString("finding_ref", path),
        rootCause = raw.requireReviewStateString("root_cause", path),
        minimalChange = raw.requireReviewStateString("minimal_change", path),
        classification = FeatureTaskRuntimeRepairClassification.fromWire(
          raw.requireReviewStateString("classification", path),
        ),
        priorRoundRemedyRef = raw.optionalReviewStateString("prior_round_remedy_ref", path),
      )
    }
  }
}

data class FeatureTaskRuntimeRepairPlan(
  val roundNumber: Int,
  val entries: List<FeatureTaskRuntimeRepairPlanEntry>,
  val contractVersion: String = FEATURE_TASK_RUNTIME_REPAIR_PLAN_CONTRACT_VERSION,
) {
  init {
    if (contractVersion != FEATURE_TASK_RUNTIME_REPAIR_PLAN_CONTRACT_VERSION) {
      repairPlanError("contract_version", "must be '$FEATURE_TASK_RUNTIME_REPAIR_PLAN_CONTRACT_VERSION'.")
    }
    if (roundNumber < 1) {
      repairPlanError("round_number", "must be a positive integer.")
    }
    if (entries.isEmpty()) {
      repairPlanError("entries", "must plan at least one carried finding.")
    }
    if (entries.size > REPAIR_PLAN_MAX_ENTRIES) {
      repairPlanError("entries", "allows at most $REPAIR_PLAN_MAX_ENTRIES entries.")
    }
    val refs = entries.map { normalizeIdentityPart(it.findingRef) }
    if (refs.distinct().size != refs.size) {
      repairPlanError("entries", "may plan each carried finding at most once.")
    }
  }

  val escalates: Boolean get() = entries.any(FeatureTaskRuntimeRepairPlanEntry::isDesignSymptom)

  val designSymptomRefs: List<String>
    get() = entries.filter(FeatureTaskRuntimeRepairPlanEntry::isDesignSymptom).map { it.findingRef }

  @OpenBoundaryMap("Repair plan at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to contractVersion,
    "round_number" to roundNumber,
    "entries" to entries.map(FeatureTaskRuntimeRepairPlanEntry::toArtifactMap),
  )

  companion object {
    @OpenBoundaryMap("Repair plan decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): FeatureTaskRuntimeRepairPlan {
      raw.requireOnlyReviewStateKeys(setOf("contract_version", "round_number", "entries"), path)
      return FeatureTaskRuntimeRepairPlan(
        contractVersion = raw.requireReviewStateString("contract_version", path),
        roundNumber = raw.requireReviewStateInt("round_number", path),
        entries = raw.requireReviewStateList("entries", path).mapIndexed { index, value ->
          FeatureTaskRuntimeRepairPlanEntry.fromArtifactMap(
            value.asReviewStateMap("$path.entries[$index]"),
            "$path.entries[$index]",
          )
        },
      )
    }
  }
}

internal fun repairPlanError(fieldPath: String, payloadFreeReason: String): Nothing =
  throw InvalidFeatureTaskRuntimeRepairPlanError(
    fieldPath = fieldPath,
    reason = payloadFreeReason,
    payloadFreeReason = payloadFreeReason,
  )

private fun requireRepairPlanText(value: String, field: String, maxUtf8Bytes: Int) {
  sanitizedTextViolation(value, maxUtf8Bytes)?.let { reason -> repairPlanError(field, reason) }
}

private fun requireRepairPlanIdentity(value: String, field: String) {
  utf8BudgetViolation(value, REPAIR_RECEIPT_MAX_LABEL_UTF8_BYTES)?.let { reason -> repairPlanError(field, reason) }
}

fun FeatureTaskRuntimeRepairPlan.coversCarriedFindings(
  carriedFindings: List<GoalSubtaskReviewCompactFinding>,
): Boolean {
  if (carriedFindings.isEmpty()) return true
  val planned = entries.mapTo(mutableSetOf()) { normalizeIdentityPart(it.findingRef) }
  return carriedFindings.all { carried ->
    val id = carried.findingId?.let(::normalizeIdentityPart)
    if (id != null) id in planned else normalizeIdentityPart(carried.label) in planned
  }
}
