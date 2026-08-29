@file:Suppress("TooManyFunctions")

package skillbill.application.workflow

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.asStringAnyMapOrNull
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.decomposition.decodeDecompositionManifestMap
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.featuretask.model.GoalContinuationCandidate
import skillbill.error.LegacyProseWorkflowError
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.model.WorkflowStateSnapshot

internal fun WorkflowStateSnapshot.decompositionRuntime(
  validator: DecompositionManifestValidator,
): DecompositionManifest? = decodeArtifacts(artifactsJson)[DECOMPOSITION_RUNTIME_ARTIFACT_KEY].asStringAnyMapOrNull()
  ?.let { decodeDecompositionManifestMap(it, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY) }

internal fun WorkflowStateSnapshot.hasDecompositionPlan(): Boolean =
  decodeArtifacts(artifactsJson)["plan"].asStringAnyMapOrNull()?.get("mode") == "decompose"

// Terminal statuses for feature-task workflows — a reclaimed row carrying one of these
// must not be treated as a live resumable parent.
private val IMPLEMENT_TERMINAL_STATUSES: Set<String> = setOf("completed", "failed", "abandoned")

/**
 * Parent discovery must see both runtime and legacy prose rows. Prose parents still carry a valid
 * `decomposition_runtime` artifact and are load-bearing for continuation lookup / goal status; they
 * must never be fed into WorkflowEngine runtime schema validation (see [requireRuntimeModeForEngineWrite]).
 */
private fun WorkflowStateRepository.listFeatureTaskWorkflowsForParentDiscovery(): List<WorkflowStateRecord> {
  val byId = LinkedHashMap<String, WorkflowStateRecord>()
  listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, Int.MAX_VALUE).forEach { row ->
    byId[row.workflowId] = row
  }
  listFeatureTaskWorkflows(FeatureTaskWorkflowMode.PROSE, Int.MAX_VALUE).forEach { row ->
    byId.putIfAbsent(row.workflowId, row)
  }
  return byId.values.toList()
}

/**
 * Expanding parent discovery to mode=prose must not convert resume/continue/save into a runtime
 * schema assertion over retired step ids. Call this before any TASK_RUNTIME engine.updateRecord.
 */
internal fun WorkflowStateRecord.requireRuntimeModeForEngineWrite() {
  if (mode != FeatureTaskWorkflowMode.RUNTIME) {
    throw LegacyProseWorkflowError(workflowId, issueKey)
  }
}

/**
 * Single-scan lookup that returns a valid parent (or the first non-terminal corrupt-manifest row as
 * a fallback) without issuing a second full table scan when the primary lookup misses. This avoids
 * materialising the table twice inside the caller's BEGIN IMMEDIATE write lock.
 *
 * Applies all guards from [findDecomposedParentWorkflow] for the valid candidate, and the same
 * child-exclusion, terminal-status, and ambiguity guards for the corrupt fallback.
 */
internal fun WorkflowStateRepository.findDecomposedParentOrCorruptFallback(
  issueKey: String,
  validator: DecompositionManifestValidator,
  currentProjectedManifest: DecompositionManifest?,
): WorkflowStateRecord? {
  val normalizedIssueKey = issueKey.trim()
  val validCandidates = mutableListOf<DecomposedParentCandidate>()
  val corruptCandidates = mutableListOf<WorkflowStateRecord>()
  listFeatureTaskWorkflowsForParentDiscovery()
    .filter { row ->
      val snapshot = row.toSnapshot()
      !snapshot.isGoalContinuationChildWorkflow() &&
        row.issueKey == normalizedIssueKey &&
        (
          snapshot.hasDecompositionPlan() ||
            DECOMPOSITION_RUNTIME_ARTIFACT_KEY in decodeArtifacts(snapshot.artifactsJson)
          )
    }
    .forEach { row ->
      val manifest = row.toSnapshot().decompositionRuntime(validator)
      when {
        manifest != null &&
          manifest.issueKey == normalizedIssueKey &&
          row.workflowStatus !in IMPLEMENT_TERMINAL_STATUSES ->
          validCandidates += DecomposedParentCandidate(row, manifest)
        manifest == null && row.workflowStatus !in IMPLEMENT_TERMINAL_STATUSES ->
          corruptCandidates += row
      }
    }
  val nonStale = validCandidates.filterNot { it.isStaleAbandonedLineage(currentProjectedManifest) }
  val active = nonStale.filter { it.manifest.isActiveGoalRuntime() }
  if (active.size > 1) {
    error(
      "Ambiguous decomposed parent workflows for '$normalizedIssueKey': " +
        active.joinToString { it.record.workflowId } +
        ". Pass an explicit workflow or manifest selector before continuing.",
    )
  }
  val validRecord = (active.firstOrNull() ?: nonStale.firstOrNull())?.record
  if (validRecord != null) return validRecord
  if (corruptCandidates.size > 1) {
    error(
      "Ambiguous corrupt-manifest parent rows for '$normalizedIssueKey': " +
        corruptCandidates.joinToString { it.workflowId } +
        ". Operator intervention is required to resolve the duplicate parent rows.",
    )
  }
  return corruptCandidates.firstOrNull()
}

internal fun WorkflowStateRepository.findDecomposedParentWorkflow(
  issueKey: String,
  validator: DecompositionManifestValidator,
  currentProjectedManifest: DecompositionManifest? = null,
): WorkflowStateRecord? {
  val normalizedIssueKey = issueKey.trim()
  val candidates = listFeatureTaskWorkflowsForParentDiscovery().mapNotNull { row ->
    val snapshot = row.toSnapshot()
    if (snapshot.isGoalContinuationChildWorkflow()) return@mapNotNull null
    val manifest = snapshot.decompositionRuntime(validator) ?: return@mapNotNull null
    if (
      (snapshot.hasDecompositionPlan() || row.issueKey?.trim() == normalizedIssueKey) &&
      manifest.issueKey == normalizedIssueKey
    ) {
      DecomposedParentCandidate(row, manifest)
    } else {
      null
    }
  }.filterNot { candidate -> candidate.isStaleAbandonedLineage(currentProjectedManifest) }
  val activeCandidates = candidates.filter { candidate -> candidate.manifest.isActiveGoalRuntime() }
  if (activeCandidates.size > 1) {
    error(
      "Ambiguous decomposed parent workflows for '$normalizedIssueKey': " +
        activeCandidates.joinToString { candidate -> candidate.record.workflowId } +
        ". Pass an explicit workflow or manifest selector before continuing.",
    )
  }
  return activeCandidates.firstOrNull()?.record
    ?: candidates.firstOrNull()?.record
}

internal fun WorkflowStateRepository.findDecomposedParentWorkflowForRuntime(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
): WorkflowStateRecord? = listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, Int.MAX_VALUE).firstOrNull { row ->
  val snapshot = row.toSnapshot()
  !snapshot.isGoalContinuationChildWorkflow() &&
    (snapshot.hasDecompositionPlan() || row.issueKey?.trim() == manifest.issueKey) &&
    snapshot.decompositionRuntime(validator)?.sameRuntimeIdentity(manifest) == true
}

private fun DecompositionManifest.sameRuntimeIdentity(other: DecompositionManifest): Boolean =
  issueKey == other.issueKey &&
    parentSpecPath == other.parentSpecPath &&
    subtasks.map { it.specPath } == other.subtasks.map { it.specPath }

private val GOAL_TERMINAL_MANIFEST_STATUSES: Set<String> = setOf("complete", "skipped")

/**
 * Resolves the goal parent that owns durable state for [issueKey] in [repositoryIdentity], or null
 * when there is none. Goal parents carry no execution identity, so repository scoping is inferred
 * from the goal's children: children in this repository bind the goal here, and a goal with no
 * children anywhere has not launched yet and is treated as belonging to the asking repository.
 */
internal fun WorkflowStateRepository.goalContinuationFor(
  issueKey: String,
  repositoryIdentity: String,
  validator: DecompositionManifestValidator,
): GoalContinuationCandidate? {
  val record = findDecomposedParentWorkflow(issueKey, validator)
    ?.takeIf { it.workflowStatus !in IMPLEMENT_TERMINAL_STATUSES }
    ?: return null
  val manifest = record.toSnapshot().decompositionRuntime(validator)
    ?.takeIf { it.status !in GOAL_TERMINAL_MANIFEST_STATUSES }
    ?: return null
  val boundToThisRepository = findGoalChildFeatureTaskCandidates(issueKey, repositoryIdentity).isNotEmpty() ||
    countGoalChildIdentities(issueKey) == 0
  if (!boundToThisRepository) return null
  val running = record.workflowStatus == "running"
  return GoalContinuationCandidate(
    parentWorkflowId = record.workflowId,
    issueKey = manifest.issueKey,
    status = record.workflowStatus,
    currentSubtaskId = manifest.currentSubtaskIntent.subtaskId.takeIf { it > 0 },
    currentAction = manifest.currentSubtaskIntent.action,
    completeCount = manifest.subtasks.count { it.status == "complete" },
    pendingCount = manifest.subtasks.count { it.status !in GOAL_TERMINAL_MANIFEST_STATUSES },
    blockedCount = manifest.subtasks.count { it.status == "blocked" },
    updatedAt = record.updatedAt,
    summary = if (running) {
      "A goal run for '${manifest.issueKey}' is already in progress; check it before starting another."
    } else {
      "A prepared goal for '${manifest.issueKey}' owns durable state; continue it instead of starting new work."
    },
  )
}

private data class DecomposedParentCandidate(
  val record: WorkflowStateRecord,
  val manifest: DecompositionManifest,
)

// An abandoned parent row whose stored subtask lineage no longer matches the current governed
// manifest on disk, and which never recorded any real subtask progress, is dead bookkeeping from a
// superseded manifest edit (e.g. a subtask inserted/renumbered after the row was created). It must
// not shadow the current manifest for a fresh continuation lookup. A row still carrying real
// progress (any subtask `hasStarted()`) is never treated as stale here, regardless of workflow
// status, so genuine history is never discarded.
private fun DecomposedParentCandidate.isStaleAbandonedLineage(
  currentProjectedManifest: DecompositionManifest?,
): Boolean {
  if (currentProjectedManifest == null || record.workflowStatus != "abandoned") return false
  if (manifest.subtasks.any { subtask -> subtask.hasStarted() }) return false
  return manifest.subtasks.map { it.specPath } != currentProjectedManifest.subtasks.map { it.specPath }
}

internal fun DecompositionManifest.isActiveGoalRuntime(): Boolean = status !in setOf("complete", "skipped") &&
  subtasks.any { subtask -> subtask.status !in setOf("complete", "skipped") }

internal fun WorkflowStateSnapshot.isGoalContinuationChildWorkflow(): Boolean {
  val goalContinuation = decodeArtifacts(artifactsJson)["goal_continuation"].asStringAnyMapOrNull() ?: return false
  return goalContinuation["enabled"] == true ||
    goalContinuation.containsKey("issue_key") ||
    goalContinuation.containsKey("subtask_id")
}
