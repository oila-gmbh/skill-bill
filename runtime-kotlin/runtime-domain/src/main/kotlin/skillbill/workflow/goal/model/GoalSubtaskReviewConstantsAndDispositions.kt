package skillbill.workflow.goal.model

import skillbill.boundary.OpenBoundaryMap

const val GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY: String = "goal_subtask_review_state"
const val GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY: String = "goal_subtask_review_input"
const val GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY: String = "goal_subtask_review_results"
const val GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX: String = "goal_subtask_review_results"
const val GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY: String = "blocker"

/** Additive evidence log of review/remediation base recoveries; unknown to older runtimes. */
const val GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY: String = "goal_review_base_recoveries"

enum class GoalSubtaskOperatorDecision(val wireValue: String) {
  RETRY_FIX("retry_fix"),
  ACCEPT_AND_ADVANCE("accept_and_advance"),
  ABANDON_SUBTASK("abandon_subtask"),
  ;

  companion object {
    fun fromWire(value: String): GoalSubtaskOperatorDecision = entries.firstOrNull { it.wireValue == value }
      ?: reviewStateError("operator_decision", "must be one of ${entries.joinToString { it.wireValue }}.")
  }
}

/**
 * How a recorded operator decision releases the pause. Every decision releases it — leaving a decided
 * pause intact would re-pause the subtask identically and strand it on the operator's own choice.
 */
enum class GoalSubtaskPauseRelease {
  /** One fresh, unbudgeted `implement_fix` iteration. */
  RETRY_FIX,

  /** Forward to `validate` with the unresolved Blockers accepted as-is. */
  ADVANCE,

  /** Terminal: the subtask is abandoned rather than repaired. */
  ABANDON,
}

enum class GoalSubtaskBlockerDispositionVerdict(val wireValue: String) {
  RESOLVED("resolved"),
  UNRESOLVED("unresolved"),
  ;

  companion object {
    fun fromWire(value: String): GoalSubtaskBlockerDispositionVerdict = when (value) {
      "superseded" -> reviewStateError(
        "verdict",
        "superseded is removed; records naming it must be regenerated.",
      )
      else -> entries.firstOrNull { it.wireValue == value }
        ?: reviewStateError("verdict", "must be one of ${entries.joinToString { it.wireValue }}.")
    }
  }
}

data class GoalSubtaskBlockerDisposition(
  val findingId: String,
  val verdict: GoalSubtaskBlockerDispositionVerdict,
  val evidence: List<String>,
) {
  init {
    require(findingId.isNotBlank()) { "GoalSubtaskBlockerDisposition.findingId must be non-blank." }
    require(evidence.isNotEmpty()) {
      "GoalSubtaskBlockerDisposition.evidence must contain at least one evidence entry."
    }
    require(evidence.all(String::isNotBlank)) {
      "GoalSubtaskBlockerDisposition.evidence must contain only non-blank strings."
    }
  }

  @OpenBoundaryMap("Blocker disposition at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "finding_id" to findingId,
    "verdict" to verdict.wireValue,
    "evidence" to evidence,
  )

  companion object {
    @OpenBoundaryMap("Blocker disposition decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): GoalSubtaskBlockerDisposition {
      raw.requireOnlyReviewStateKeys(setOf("finding_id", "verdict", "evidence"), path)
      val evidence = raw.requireReviewStateList("evidence", path).mapIndexed { index, value ->
        value as? String ?: reviewStateError(
          "$path.evidence[$index]",
          "must be a string.",
        )
      }
      return GoalSubtaskBlockerDisposition(
        findingId = raw.requireReviewStateString("finding_id", path),
        verdict = GoalSubtaskBlockerDispositionVerdict.fromWire(raw.requireReviewStateString("verdict", path)),
        evidence = evidence,
      )
    }
  }
}

fun unionRefutedBlockerDispositions(
  agentEmitted: List<GoalSubtaskBlockerDisposition>,
  runtimeSuperseded: List<GoalSubtaskBlockerDisposition>,
): List<GoalSubtaskBlockerDisposition> {
  if (runtimeSuperseded.isEmpty()) return agentEmitted
  val byId = agentEmitted.associateBy(GoalSubtaskBlockerDisposition::findingId).toMutableMap()
  runtimeSuperseded.forEach { disposition -> byId[disposition.findingId] = disposition }
  return byId.values.toList()
}

enum class GoalSubtaskReviewDisposition(val wireValue: String) {
  PENDING("pending"),
  PAUSED("paused"),
  REVIEW_CAP_REACHED("review_cap_reached"),
  ;

  companion object {
    fun fromWire(value: String): GoalSubtaskReviewDisposition = entries.firstOrNull { it.wireValue == value }
      ?: reviewStateError("disposition", "must be one of ${entries.joinToString { it.wireValue }}.")
  }
}
