package skillbill.workflow.taskruntime.model

/**
 * Pure domain models for the structured audit verdict that drives the `audit_gap` backward edge. An
 * audit emits a [FeatureTaskRuntimeAuditVerdict]: its findings classify the run as
 * [FeatureTaskRuntimeVerdict.GAPS_FOUND] when at least one Blocker or Major criterion gap remains, or
 * [FeatureTaskRuntimeVerdict.SATISFIED] when findings are absent or only Minor/Nit. The classification
 * is a pure function of structured findings — prose alone cannot reopen implementation — and only
 * blocking findings scope remediation.
 * No raw maps live in the model: the runner decodes wire output into these types.
 */

enum class FeatureTaskRuntimeAuditSeverity(val wireValue: String, val blocksAuditGap: Boolean) {
  BLOCKER("blocker", true),
  MAJOR("major", true),
  MINOR("minor", false),
  NIT("nit", false),
  ;

  companion object {
    /** Missing severity is legacy 0.2 output and remains Major for backward-compatible safety. */
    fun fromWire(value: String?): FeatureTaskRuntimeAuditSeverity = when (value?.trim()?.lowercase()) {
      null, "" -> MAJOR
      "blocker" -> BLOCKER
      "major" -> MAJOR
      "minor" -> MINOR
      "nit" -> NIT
      else -> throw IllegalArgumentException("Unknown feature-task-runtime audit severity '$value'.")
    }
  }
}

/** One acceptance-criterion finding with the severity that controls implementation re-entry. */
data class FeatureTaskRuntimeAuditCriterionGap(
  val message: String,
  val severity: FeatureTaskRuntimeAuditSeverity = FeatureTaskRuntimeAuditSeverity.MAJOR,
) {
  init {
    require(message.isNotBlank()) { "FeatureTaskRuntimeAuditCriterionGap.message must be non-blank." }
  }
}

/**
 * The structured verdict an `audit` phase emits: the unmet acceptance criteria. [verdict] is derived
 * purely from the gaps ([FeatureTaskRuntimeVerdict.GAPS_FOUND] when any criterion is unmet, else
 * [FeatureTaskRuntimeVerdict.SATISFIED]), and [unmetCriteria] are carried into the remediation handoff and
 * the loud block on cap exhaustion.
 */
data class FeatureTaskRuntimeAuditVerdict(
  val unmetCriteria: List<FeatureTaskRuntimeAuditCriterionGap>,
) {
  val blockingCriteria: List<FeatureTaskRuntimeAuditCriterionGap>
    get() = unmetCriteria.filter { it.severity.blocksAuditGap }

  val verdict: FeatureTaskRuntimeVerdict
    get() = if (blockingCriteria.isEmpty()) {
      FeatureTaskRuntimeVerdict.SATISFIED
    } else {
      FeatureTaskRuntimeVerdict.GAPS_FOUND
    }
}
