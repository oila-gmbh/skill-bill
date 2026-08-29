package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap

/**
 * The durable resolved feature branch for one run. [branch] is the non-default feature branch the
 * run is pinned to; [baseBranch] is the branch it was created from (null when the run reused an
 * already-checked-out branch rather than creating one).
 */
data class FeatureTaskRuntimeResolvedBranch(
  val branch: String,
  val baseBranch: String? = null,
  val created: Boolean = false,
  val reviewBaseSha: String? = null,
  val baselineUntrackedPaths: List<String> = emptyList(),
  val baselineOwnedPaths: List<String> = emptyList(),
  val workflowOwnedPaths: List<String> = emptyList(),
) {
  init {
    require(branch.isNotBlank()) { "FeatureTaskRuntimeResolvedBranch.branch must be non-blank." }
    require(reviewBaseSha == null || REVIEW_BASE_SHA.matches(reviewBaseSha)) {
      "FeatureTaskRuntimeResolvedBranch.reviewBaseSha must be a 40- or 64-character lowercase commit SHA."
    }
    require(baselineUntrackedPaths.all(String::isNotBlank)) {
      "FeatureTaskRuntimeResolvedBranch.baselineUntrackedPaths must not contain blanks."
    }
    require(baselineOwnedPaths.all(String::isNotBlank)) {
      "FeatureTaskRuntimeResolvedBranch.baselineOwnedPaths must not contain blanks."
    }
    require(workflowOwnedPaths.all(String::isNotBlank)) {
      "FeatureTaskRuntimeResolvedBranch.workflowOwnedPaths must not contain blanks."
    }
  }

  @OpenBoundaryMap("Feature-task-runtime resolved-branch artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "branch" to branch,
    "created" to created,
  ).apply {
    baseBranch?.let { put("base_branch", it) }
    reviewBaseSha?.let { put("review_base_sha", it) }
    if (baselineUntrackedPaths.isNotEmpty()) put("baseline_untracked_paths", baselineUntrackedPaths)
    put("baseline_owned_paths", baselineOwnedPaths)
    put("workflow_owned_paths", workflowOwnedPaths)
  }

  companion object {
    /** Strict decode; loud-fails on a missing or malformed required field. */
    @OpenBoundaryMap("Feature-task-runtime resolved-branch decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeResolvedBranch = FeatureTaskRuntimeResolvedBranch(
      branch = raw.requireStringField("branch"),
      baseBranch = raw.optionalStringField("base_branch"),
      created = raw.optionalBooleanField("created") ?: false,
      reviewBaseSha = raw.optionalStringField("review_base_sha"),
      baselineUntrackedPaths = (raw["baseline_untracked_paths"] as? List<*>)
        ?.map { it as? String ?: error("baseline_untracked_paths must contain only strings.") }
        .orEmpty(),
      baselineOwnedPaths = (raw["baseline_owned_paths"] as? List<*>)
        ?.map { it as? String ?: error("baseline_owned_paths must contain only strings.") }
        .orEmpty(),
      workflowOwnedPaths = (raw["workflow_owned_paths"] as? List<*>)
        ?.map { it as? String ?: error("workflow_owned_paths must contain only strings.") }
        .orEmpty(),
    )
  }
}

private val REVIEW_BASE_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")
