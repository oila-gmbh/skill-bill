package skillbill.application.goalrunner

import skillbill.contracts.workflow.GOAL_PORTABLE_REVIEW_BASELINE_CONTRACT_VERSION
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.goal.model.PortableReviewBaseline
import skillbill.workflow.goal.model.PortableReviewBaselineCodec

object PortableReviewBaselineMapping {
  fun fromReviewBaseline(
    workflowId: String,
    repositoryIdentity: String,
    goalBranch: String,
    reviewBaseline: GoalSubtaskReviewBaseline,
  ): PortableReviewBaseline {
    val body = linkedMapOf<String, Any?>(
      "contract_version" to GOAL_PORTABLE_REVIEW_BASELINE_CONTRACT_VERSION,
      "workflow_id" to workflowId,
      "repository_identity" to repositoryIdentity,
      "goal_branch" to goalBranch,
      "review_base_sha" to reviewBaseline.reviewBaseSha,
      "baseline_untracked_paths" to reviewBaseline.baselineUntrackedPaths.distinct().sorted(),
      "owned_pathspec" to reviewBaseline.ownedPathspec.distinct().sorted(),
    )
    return PortableReviewBaseline(
      workflowId = workflowId,
      repositoryIdentity = repositoryIdentity,
      goalBranch = goalBranch,
      reviewBaseSha = reviewBaseline.reviewBaseSha,
      baselineUntrackedPaths = reviewBaseline.baselineUntrackedPaths,
      ownedPathspec = reviewBaseline.ownedPathspec,
      integrityDigest = PortableReviewBaselineCodec.digest(body),
    )
  }

  fun toReviewBaseline(artifact: PortableReviewBaseline): GoalSubtaskReviewBaseline = GoalSubtaskReviewBaseline(
    artifact.reviewBaseSha,
    artifact.baselineUntrackedPaths,
    artifact.ownedPathspec,
  )
}
