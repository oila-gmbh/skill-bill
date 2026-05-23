package skillbill.application

import skillbill.application.model.DecompositionManifestRuntimeUpdate
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.Path

private val statusTrackedSteps = setOf("implement", "review", "audit", "validate", "pr_description", "finish")
private val completionSteps = setOf("pr_description", "finish")
private val terminalSkippedSteps = setOf("pr_description", "finish")

internal fun DecompositionSubtask.withRuntimeFields(
  manifest: DecompositionManifest,
  update: DecompositionManifestRuntimeUpdate,
  status: String?,
): DecompositionSubtask {
  val artifacts = mergedArtifacts(update)
  val nextStatus = status ?: this.status
  return copy(
    status = nextStatus,
    branch = branchName(artifacts["branch"]).ifBlank { manifest.branchFor(id) ?: branch },
    workflowId = update.workflowId.ifBlank { workflowId },
    reviewResult = artifacts["review_result"].asStringAnyMapOrNull() ?: reviewResult,
    auditResult = artifacts["audit_report"].asStringAnyMapOrNull() ?: auditResult,
    validationResult = artifacts["validation_result"].asStringAnyMapOrNull() ?: validationResult,
    blockedReason = blockedReasonFrom(update, nextStatus) ?: blockedReason.takeUnless { nextStatus != "blocked" },
    lastResumableStep = update.currentStepId.takeIf(String::isNotBlank) ?: lastResumableStep,
  )
}

internal fun DecompositionManifest.currentSubtaskIdForUpdate(
  repoRoot: Path,
  update: DecompositionManifestRuntimeUpdate,
): Int? {
  val assessment = mergedArtifacts(update)["assessment"] as? Map<*, *>
  val specPath = assessment?.get("spec_path")?.toString()?.takeIf(String::isNotBlank)
  val matchedId = specPath?.let { matchingSubtaskId(repoRoot, it) }
  return if (specPath != null) {
    matchedId
  } else {
    currentSubtaskIntent.subtaskId.takeIf { it != 0 }
  }
}

internal fun statusFromUpdate(update: DecompositionManifestRuntimeUpdate): String? {
  val stepUpdates = update.stepUpdates.orEmpty()
  return when {
    update.workflowStatus == "blocked" || stepUpdates.any { it["status"] == "blocked" } -> "blocked"
    stepUpdates.any { it["status"] == "skipped" && it["step_id"] in terminalSkippedSteps } -> "skipped"
    update.workflowStatus == "completed" ||
      stepUpdates.any { it["status"] == "completed" && it["step_id"] in completionSteps } -> "complete"
    update.currentStepId in statusTrackedSteps || stepUpdates.any { it["step_id"] in statusTrackedSteps } ->
      "in_progress"
    else -> null
  }
}

internal fun intentFor(subtaskId: Int, status: String?): CurrentSubtaskIntent = when (status) {
  "blocked" -> CurrentSubtaskIntent(subtaskId = subtaskId, action = "blocked")
  "complete", "skipped" -> CurrentSubtaskIntent(subtaskId = 0, action = "none")
  "in_progress" -> CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume")
  else -> CurrentSubtaskIntent(subtaskId = subtaskId, action = "start")
}

internal fun DecompositionManifest.withParentStatus(): DecompositionManifest {
  val parentStatus = when {
    subtasks.all { it.status in setOf("complete", "skipped") } -> "complete"
    subtasks.any { it.status == "blocked" } -> "blocked"
    subtasks.any { it.status in setOf("in_progress", "complete", "skipped") || it.hasStarted() } -> "in_progress"
    else -> "pending"
  }
  return copy(status = parentStatus)
}

private fun DecompositionManifest.matchingSubtaskId(repoRoot: Path, specPath: String): Int? {
  val absoluteSpecPath = resolvedParentSpecPath(repoRoot, Path.of(specPath)).normalize()
  return subtasks.firstOrNull { subtask ->
    resolvedParentSpecPath(repoRoot, Path.of(subtask.specPath)).normalize() == absoluteSpecPath
  }?.id
}

private fun DecompositionManifest.branchFor(subtaskId: Int): String? = when (executionModel) {
  DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK -> featureBranch
  DecompositionExecutionModel.STACKED_BRANCHES -> stackBranches.firstOrNull { it.subtaskId == subtaskId }?.branch
}

private fun mergedArtifacts(update: DecompositionManifestRuntimeUpdate): Map<String, Any?> =
  LinkedHashMap(update.existingArtifacts).apply { update.artifactsPatch?.let(::putAll) }

private fun blockedReasonFrom(update: DecompositionManifestRuntimeUpdate, status: String): String? =
  if (status == "blocked") {
    val artifacts = mergedArtifacts(update)
    artifacts["blocked_reason"]?.toString()?.takeIf(String::isNotBlank)
      ?: "Workflow step '${update.currentStepId.ifBlank { "unknown" }}' is blocked."
  } else {
    null
  }
