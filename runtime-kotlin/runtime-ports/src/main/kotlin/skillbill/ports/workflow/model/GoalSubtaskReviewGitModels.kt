package skillbill.ports.workflow.model

import skillbill.boundary.OpenBoundaryMap

data class GoalSubtaskReviewBaseline(
  val reviewBaseSha: String,
  val baselineUntrackedPaths: List<String>,
) {
  init {
    require(GOAL_REVIEW_GIT_SHA.matches(reviewBaseSha)) {
      "reviewBaseSha must be a 40- or 64-character lowercase commit SHA."
    }
    require(baselineUntrackedPaths.all(String::isNotBlank)) { "baselineUntrackedPaths must not contain blanks." }
  }
}

data class GoalSubtaskReviewBaselineResult(
  val status: String,
  val baseline: GoalSubtaskReviewBaseline? = null,
  val error: String = "",
) {
  val ok: Boolean get() = status == "ok" && baseline != null
}

data class GoalSubtaskReviewInput(
  val reviewBaseSha: String,
  val currentHeadSha: String,
  val trackedDelta: String,
  val ownedUntrackedPatches: String,
) {
  init {
    require(GOAL_REVIEW_GIT_SHA.matches(reviewBaseSha)) {
      "reviewBaseSha must be a 40- or 64-character lowercase commit SHA."
    }
    require(GOAL_REVIEW_GIT_SHA.matches(currentHeadSha)) {
      "currentHeadSha must be a 40- or 64-character lowercase commit SHA."
    }
  }

  val reviewText: String get() = buildString {
    append(trackedDelta)
    if (ownedUntrackedPatches.isNotBlank()) {
      if (isNotEmpty() && !endsWith("\n")) append('\n')
      append(ownedUntrackedPatches)
    }
  }

  @OpenBoundaryMap("Exact goal-review git input at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "review_base_sha" to reviewBaseSha,
    "current_head_sha" to currentHeadSha,
    "tracked_delta" to trackedDelta,
    "owned_untracked_patches" to ownedUntrackedPatches,
  )
}

data class GoalSubtaskReviewInputResult(
  val status: String,
  val input: GoalSubtaskReviewInput? = null,
  val error: String = "",
) {
  val ok: Boolean get() = status == "ok" && input != null
}

private val GOAL_REVIEW_GIT_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")
