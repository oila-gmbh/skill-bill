package skillbill.application.goalrunner

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.contracts.JsonSupport
import skillbill.error.LegacyProseWorkflowError
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_REQUEST
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_STOP_AFTER_SUBTASK
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.db.UnitOfWork
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.goal.model.CodeReviewExecutionMode

internal fun workflowFamilyFor(workflowStates: WorkflowStateRepository, workflowId: String): WorkflowFamily? {
  val featureTaskRow = workflowStates.getFeatureTaskWorkflow(workflowId)
  if (featureTaskRow != null) {
    return when (featureTaskRow.mode) {
      FeatureTaskWorkflowMode.RUNTIME -> WorkflowFamily.TASK_RUNTIME
      FeatureTaskWorkflowMode.PROSE, null -> throw LegacyProseWorkflowError(workflowId, featureTaskRow.issueKey)
    }
  }
  return if (workflowStates.getFeatureVerifyWorkflow(workflowId) != null) {
    WorkflowFamily.VERIFY
  } else {
    null
  }
}

/**
 * Moves legacy parent-owned controls out of workflow artifacts before a thin parent projection
 * replaces that artifact map. The operation is safe to repeat because existing durable controls
 * remain authoritative and already-migrated acceptance entries are not written again.
 */
internal fun migrateLegacyGoalRunnerControls(unitOfWork: UnitOfWork, existing: WorkflowStateSnapshot) {
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
internal fun reviewPolicyFromLegacyArtifacts(artifacts: Map<String, Any?>): GoalRunnerReviewPolicy? {
  val raw = artifacts[GOAL_REVIEW_POLICY_ARTIFACT_KEY] ?: return null
  val policy = JsonSupport.anyToStringAnyMap(raw)
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

internal fun outOfBandAcceptancesFromLegacyArtifacts(
  artifacts: Map<String, Any?>,
): Map<Int, GoalRunnerOutOfBandAcceptance> {
  val raw = artifacts[GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY] ?: return emptyMap()
  val entries = raw as? List<*>
    ?: error("Goal acceptance artifact '$GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY' must be a list.")
  return entries.associate { element ->
    val entry = JsonSupport.anyToStringAnyMap(element)
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

internal fun GoalRunnerControlState.pauseAtOperatorBoundary(
  pausedAtNow: String,
  targetReached: Boolean = false,
): GoalRunnerControlState = when {
  // Already paused: the original pause instant is the one that matters, so it is never restamped.
  paused -> copy(stopAfterConsumed = stopAfterConsumed || targetReached)
  pauseRequested -> copy(
    pauseConsumed = true,
    paused = true,
    pauseReason = pauseReason ?: GOAL_PAUSE_REASON_OPERATOR_REQUEST,
    pausedAt = pausedAtNow,
    stopAfterConsumed = stopAfterConsumed || targetReached,
  )
  targetReached -> copy(
    paused = true,
    pauseReason = GOAL_PAUSE_REASON_STOP_AFTER_SUBTASK,
    pausedAt = pausedAtNow,
    stopAfterConsumed = true,
  )
  else -> this
}

internal fun decodeGoalAgentAddonSelection(raw: Any?): AgentAddonSelection {
  val values = raw ?: return AgentAddonSelection()
  val entries = values as? List<*> ?: error("Goal review policy agent_addon_selection must be a list.")
  return AgentAddonSelection(
    entries.mapIndexed { index, value ->
      val entry = JsonSupport.anyToStringAnyMap(value)
        ?: error("Goal review policy agent_addon_selection entry $index must be a map.")
      check(entry.keys == setOf("slug", "source_identity", "content_sha256")) {
        "Goal review policy agent_addon_selection entry $index has invalid fields."
      }
      PersistedAgentAddonSelectionEntry(
        entry["slug"] as? String ?: error("Goal review policy add-on entry $index is missing slug."),
        entry["source_identity"] as? String
          ?: error("Goal review policy add-on entry $index is missing source_identity."),
        entry["content_sha256"] as? String
          ?: error("Goal review policy add-on entry $index is missing content_sha256."),
      )
    },
  )
}
