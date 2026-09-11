package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskFinalisationBlocked
import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskFinalisationResult
import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskFinaliseRequest
import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskFinalised

internal class FeatureTaskRuntimeSubtaskCommitPublisher(
  private val host: FeatureTaskRuntimeSubtaskFinalisation,
) {
  fun commitAndPush(input: FinalisationCommitRequest): FeatureTaskRuntimeSubtaskFinalisationResult {
    return if (input.stageable.isEmpty()) publishCleanHead(input) else commitStageable(input)
  }

  private fun publishCleanHead(input: FinalisationCommitRequest): FeatureTaskRuntimeSubtaskFinalisationResult {
    val foreignRestored = host.restoreForeignIndex(input.foreignStagedPaths, input.foreignSnapshot)
    return if (foreignRestored != null) {
      host.blocked(foreignRestored)
    } else {
      publishExistingHead(input.request, input.request.metadata.branch, input.excluded)
    }
  }

  private fun commitStageable(input: FinalisationCommitRequest): FeatureTaskRuntimeSubtaskFinalisationResult {
    val request = input.request
    val stageable = input.stageable
    val branch = request.metadata.branch
    val decision = host.decide(
      branch = branch,
      identity = request.identity,
      durableCommitSha = request.durableCommitSha,
      sequenceNumber = request.sequenceNumber,
    )
    val commit = host.gitOperations.writeSubtaskCommitPreservingHistory(
      SubtaskCommitPreservationRequest(
        repoRoot = host.repoRoot,
        decision = decision,
        identity = request.identity,
        message = FeatureTaskRuntimeCheckpointMessage.finalise(
          request.handoff.outcomeMessage,
          request.metadata,
          request.identity,
        ),
        allowUnchangedIndex = true,
        ownedPaths = stageable,
        record = host.record,
      ),
    )
    val commitSha = when (val outcome = host.finalisationCommitSha(commit, stageable, input.restoreState)) {
      is FinalisationCommitShaBlocked -> return host.blocked(
        host.restoreForeignFinalisationIndex(outcome.reason, input.foreignStagedPaths, input.foreignSnapshot),
      )
      is FinalisationCommitShaReady -> outcome.value
    }
    val foreignRestored = host.restoreForeignIndex(input.foreignStagedPaths, input.foreignSnapshot)
    if (foreignRestored != null) return host.blocked(foreignRestored)
    return recordedOrFinalised(
      FinalizeCommittedSubtaskInput(
        request = request,
        branch = branch,
        stageable = stageable,
        excluded = input.excluded,
        commitSha = commitSha,
        rewrites = decision is FeatureTaskRuntimeSubtaskCommitAmend,
      ),
    )
  }

  private fun publishExistingHead(
    request: FeatureTaskRuntimeSubtaskFinaliseRequest,
    branch: String,
    excluded: List<String>,
  ): FeatureTaskRuntimeSubtaskFinalisationResult {
    val head = host.gitOperations.headCommitSha(host.repoRoot)
    val commitSha = head.value.orEmpty().trim()
    if (!head.ok || commitSha.isBlank()) {
      return host.blocked("HEAD could not be resolved (${head.error})")
    }
    return recordedOrFinalised(
      FinalizeCommittedSubtaskInput(
        request = request,
        branch = branch,
        stageable = emptyList(),
        excluded = excluded,
        commitSha = commitSha,
        rewrites = false,
      ),
    )
  }

  private fun recordedOrFinalised(input: FinalizeCommittedSubtaskInput): FeatureTaskRuntimeSubtaskFinalisationResult {
    val recordFailure = when {
      input.commitSha == input.request.durableCommitSha?.trim() -> null
      else -> host.recordCommit(input.commitSha, input.stageable)
    }
    return if (recordFailure != null) {
      FeatureTaskRuntimeSubtaskFinalisationBlocked(recordFailure)
    } else {
      finalizeCommittedSubtask(input)
    }
  }

  private fun finalizeCommittedSubtask(
    input: FinalizeCommittedSubtaskInput,
  ): FeatureTaskRuntimeSubtaskFinalisationResult {
    val forcedWithLease = input.rewrites && host.remoteDiverged(input.branch, input.commitSha)
    val pushFailure = host.push(input.branch, input.request.identity, input.commitSha, forcedWithLease)
    if (pushFailure != null) return host.blocked(pushFailure)
    if (!input.request.manifestCommitSha.isNullOrBlank()) {
      host.gitOperations.pruneSubtaskCheckpointRefs(
        repoRoot = host.repoRoot,
        request = FeatureTaskRuntimeCheckpointRefPruneRequest(
          issueKey = input.request.identity.issueKey,
          subtaskId = input.request.identity.subtaskId,
          manifestCommitSha = input.request.manifestCommitSha,
          featureBranch = input.branch,
        ),
        record = host.record,
      )
    }
    return FeatureTaskRuntimeSubtaskFinalised(
      commitSha = input.commitSha,
      stagedPaths = input.stageable,
      excludedSpecPaths = input.excluded,
      forcedWithLease = forcedWithLease,
    )
  }
}

private data class FinalizeCommittedSubtaskInput(
  val request: FeatureTaskRuntimeSubtaskFinaliseRequest,
  val branch: String,
  val stageable: List<String>,
  val excluded: List<String>,
  val commitSha: String,
  val rewrites: Boolean,
)
