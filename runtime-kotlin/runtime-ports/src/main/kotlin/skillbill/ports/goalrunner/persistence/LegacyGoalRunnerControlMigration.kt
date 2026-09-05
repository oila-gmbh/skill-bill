package skillbill.ports.goalrunner.persistence

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonCodec
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.workflow.decomposition.runtime.decodeArtifacts
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.engine.model.WorkflowStateSnapshot

fun migrateLegacyGoalRunnerControls(unitOfWork: GoalRunnerPersistenceSession, existing: WorkflowStateSnapshot) {
  val artifacts = decodeArtifacts(existing.artifactsJson)
  if (unitOfWork.goalRunnerControls.reviewPolicy(existing.workflowId) == null) {
    reviewPolicyFromLegacyArtifacts(artifacts)?.let {
      unitOfWork.goalRunnerControls.persistReviewPolicy(existing.workflowId, it)
    }
  }
  val durableAcceptances = unitOfWork.goalRunnerControls.outOfBandAcceptances(existing.workflowId)
  outOfBandAcceptancesFromLegacyArtifacts(artifacts)
    .filterKeys { it !in durableAcceptances }
    .values
    .forEach { acceptance ->
      unitOfWork.goalRunnerControls.persistOutOfBandAcceptance(existing.workflowId, acceptance)
    }
}

@OpenBoundaryMap("Legacy goal review policy artifact decode before durable control migration")
fun reviewPolicyFromLegacyArtifacts(artifacts: Map<String, Any?>): GoalRunnerReviewPolicy? {
  val raw = artifacts[GOAL_REVIEW_POLICY_ARTIFACT_KEY] ?: return null
  val policy = JsonCodec.anyToStringAnyMap(raw)
    ?: error("Goal review policy artifact '$GOAL_REVIEW_POLICY_ARTIFACT_KEY' must be a map.")
  val allowedKeys = setOf("code_review_mode", "parallel_review_agent", "agent_addon_selection")
  policy.keys.forEach { key ->
    require(key in allowedKeys) {
      "Goal review policy artifact '$GOAL_REVIEW_POLICY_ARTIFACT_KEY' has unsupported field '$key'."
    }
  }
  val mode = policy["code_review_mode"] as? String
    ?: error("Goal review policy artifact '$GOAL_REVIEW_POLICY_ARTIFACT_KEY' is missing code_review_mode.")
  val codeReviewMode = try {
    CodeReviewExecutionMode.fromWire(mode)
  } catch (error: IllegalArgumentException) {
    throw IllegalStateException("Goal review policy artifact has invalid code_review_mode '$mode'.", error)
  }
  val agentAddonSelection = decodeGoalAgentAddonSelection(policy["agent_addon_selection"])
  return GoalRunnerReviewPolicy(codeReviewMode, agentAddonSelection)
}

@OpenBoundaryMap("Legacy goal out-of-band acceptance artifact decode before durable control migration")
fun outOfBandAcceptancesFromLegacyArtifacts(artifacts: Map<String, Any?>): Map<Int, GoalRunnerOutOfBandAcceptance> {
  val raw = artifacts[GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY] ?: return emptyMap()
  val entries = raw as? List<*>
    ?: error("Goal acceptance artifact '$GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY' must be a list.")
  return entries.associate { element ->
    val entry = JsonCodec.anyToStringAnyMap(element)
      ?: error("Goal acceptance artifact '$GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY' entries must be maps.")
    val acceptance = GoalRunnerOutOfBandAcceptance(
      subtaskId = (entry["subtask_id"] as? Number)?.toInt()
        ?: error("Goal acceptance artifact entry is missing a numeric subtask_id."),
      commitSha = entry["commit_sha"] as? String
        ?: error("Goal acceptance artifact entry is missing commit_sha."),
      reason = entry["reason"] as? String
        ?: error("Goal acceptance artifact entry is missing reason."),
      acceptedAt = entry["accepted_at"] as? String
        ?: error("Goal acceptance artifact entry is missing accepted_at."),
    )
    acceptance.subtaskId to acceptance
  }
}
