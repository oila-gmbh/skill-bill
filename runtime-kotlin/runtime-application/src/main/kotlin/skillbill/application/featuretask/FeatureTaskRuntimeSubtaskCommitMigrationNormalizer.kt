package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.ports.workflow.gitops.commitMessage
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

object FeatureTaskRuntimeSubtaskCommitMigrationNormalizer {
  fun reconcile(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    if (!isGoalContinuationRun(runLoop.request)) return true
    val context = loadMigrationContext(runLoop, precedingPhaseId, branch, blockedReason)
    if (context is MigrationContextRefused) return refuseSubtaskMigration(runLoop, context.request)
    val ready = (context as MigrationContextReady).value
    val active = activeMigrationCheckpoints(runLoop, ready, precedingPhaseId, branch, blockedReason)
    if (active is MigrationContextRefused) return refuseSubtaskMigration(runLoop, active.request)
    return settleMigration(
      MigrationSettlementRequest(
        runLoop,
        ready,
        (active as ActiveMigrationCheckpoints).value,
        precedingPhaseId,
        branch,
        blockedReason,
      ),
    )
  }
}

private data class MigrationContext(
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val identities: List<FeatureTaskRuntimeCheckpointIdentity>,
  val resolved: FeatureTaskRuntimeResolvedBranch,
  val headSha: String,
  val headMessage: String,
)

private sealed interface MigrationContextOutcome

private data class MigrationContextReady(val value: MigrationContext) : MigrationContextOutcome

private data class MigrationContextRefused(
  val request: SubtaskMigrationRefusalRequest,
) : MigrationContextOutcome

private data class ActiveMigrationCheckpoints(
  val value: List<FeatureTaskRuntimeCheckpointIdentity>,
) : MigrationContextOutcome

private data class MigrationSettlementRequest(
  val runLoop: FeatureTaskRuntimeRunLoop,
  val context: MigrationContext,
  val active: List<FeatureTaskRuntimeCheckpointIdentity>,
  val precedingPhaseId: String,
  val branch: String,
  val blockedReason: (String, String) -> String,
)

private fun loadMigrationContext(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): MigrationContextOutcome = runCatching {
  readMigrationContext(runLoop, precedingPhaseId, branch, blockedReason)
}.fold(
  onSuccess = ::MigrationContextReady,
  onFailure = { error ->
    when (error) {
      is MigrationRefusal -> MigrationContextRefused(error.request)
      is IllegalStateException -> MigrationContextRefused(
        refusalRequest(
          precedingPhaseId,
          branch,
          blockedReason,
          "durable migration evidence could not be read " +
            "(${error.message ?: error::class.simpleName}); operator decision: " +
            "repair the workflow store before normalizing",
          error,
        ),
      )
      else -> throw error
    }
  },
)

private fun readMigrationContext(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): MigrationContext {
  val refuse: (String) -> Nothing = { reason ->
    throw MigrationRefusal(refusalRequest(precedingPhaseId, branch, blockedReason, reason))
  }
  val identity = runLoop.collaborators.checkpointContinued4.subtaskCommitIdentity(runLoop)
  val identities = runLoop.recorder
    .loadCheckpointIdentities(runLoop.request.workflowId, runLoop.request.dbPathOverride)
    ?: refuse(
      "the workflow row has no checkpoint-identity ledger; " +
        "operator decision: restore the durable active-subtask identity before resuming",
    )
  val resolved = runLoop.recorder
    .loadResolvedBranch(runLoop.request.workflowId, runLoop.request.dbPathOverride)
    ?: refuse(
      "the resolved branch is missing; operator decision: restore the durable branch before normalizing",
    )
  val checkedOut = runLoop.phaseGates.gitOperations.currentBranch(runLoop.request.repoRoot)
  if (!checkedOut.ok || checkedOut.value.orEmpty().trim() != branch.trim() ||
    resolved.branch.trim() != branch.trim()
  ) {
    refuse(
      "the active subtask branch is not durably owned and checked out; " +
        "operator decision: resolve branch ownership before normalizing",
    )
  }
  val head = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
  val headSha = head.value.orEmpty().trim()
  if (!head.ok || headSha.isBlank()) {
    refuse(
      "the active subtask span cannot be proven because HEAD could not be resolved; " +
        "operator decision: resolve the checked-out branch before resuming",
    )
  }
  val headMessage = runLoop.phaseGates.gitOperations.commitMessage(
    runLoop.request.repoRoot,
    headSha,
  )
  return MigrationContext(identity, identities, resolved, headSha, headMessage.value.orEmpty())
}

private fun activeMigrationCheckpoints(
  runLoop: FeatureTaskRuntimeRunLoop,
  context: MigrationContext,
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): MigrationContextOutcome {
  val result = runCatching {
    context.identities
      .filter {
        it.issueKey == context.identity.issueKey && it.subtaskId == context.identity.subtaskId
      }
      .mapNotNull { checkpoint ->
        val reachability = runLoop.phaseGates.gitOperations.isCommitAncestor(
          runLoop.request.repoRoot,
          checkpoint.commitSha,
          context.headSha,
        )
        when {
          !reachability.ok -> throw MigrationRefusal(
            refusalRequest(
              precedingPhaseId,
              branch,
              blockedReason,
              "checkpoint '${checkpoint.commitSha}' reachability could not be proven " +
                "(${reachability.error}); operator decision: repair Git object access before normalizing",
            ),
          )
          reachability.value == "true" -> checkpoint
          else -> null
        }
      }
      .sortedBy { it.sequenceNumber }
  }
  return result.fold(
    onSuccess = ::ActiveMigrationCheckpoints,
    onFailure = { error ->
      if (error is MigrationRefusal) MigrationContextRefused(error.request) else throw error
    },
  )
}

private class MigrationRefusal(val request: SubtaskMigrationRefusalRequest) : RuntimeException()

private fun settleMigration(request: MigrationSettlementRequest): Boolean {
  val runLoop = request.runLoop
  val active = request.active
  val validationFailure = migrationSettlementValidationFailure(request)
  if (validationFailure != null) {
    return refuseSubtaskMigration(
      runLoop,
      refusalRequest(
        request.precedingPhaseId,
        request.branch,
        request.blockedReason,
        validationFailure,
      ),
    )
  }
  return when {
    active.isEmpty() -> settleEmptyMigration(request)
    active.size == 1 -> settleSingleMigration(request, active.single())
    else -> settleAccumulatedMigration(request)
  }
}

private fun migrationSettlementValidationFailure(request: MigrationSettlementRequest): String? {
  val context = request.context
  val baseSha = context.resolved.reviewBaseSha?.trim().takeIf { !it.isNullOrBlank() }
    ?: return "the active subtask span has no durable base SHA; operator decision: identify the span base before " +
      "normalizing"
  val baseReachability = request.runLoop.phaseGates.gitOperations.isCommitAncestor(
    request.runLoop.request.repoRoot,
    baseSha,
    context.headSha,
  )
  if (!baseReachability.ok || baseReachability.value != "true") {
    return "the durable subtask base '$baseSha' is not a proven ancestor of HEAD '${context.headSha}'; " +
      "operator decision: identify the exact active span before normalizing"
  }
  val spanRequest = SubtaskCommitSpanFailureRequest(
    repoRoot = request.runLoop.request.repoRoot,
    baseSha = baseSha,
    headSha = context.headSha,
    branch = request.branch,
    identity = context.identity,
    checkpoints = request.active,
  )
  return spanFailureForMigration(request.runLoop, spanRequest)
}

private fun spanFailureForMigration(
  runLoop: FeatureTaskRuntimeRunLoop,
  request: SubtaskCommitSpanFailureRequest,
): String? = runLoop.phaseGates.gitOperations.subtaskCommitSpanFailure(request)

private fun settleEmptyMigration(request: MigrationSettlementRequest): Boolean {
  val runLoop = request.runLoop
  val context = request.context
  if (context.resolved.reviewBaseSha == context.headSha &&
    !context.headMessage.contains("Skill-Bill-Subtask:")
  ) {
    return true
  }
  return refuseSubtaskMigration(
    runLoop,
    refusalRequest(
      request.precedingPhaseId,
      request.branch,
      request.blockedReason,
      "the active subtask span is ambiguous: no durable checkpoint identity proves " +
        "HEAD '${context.headSha}' belongs " +
        "to this subtask; operator decision: identify the owned commit span and restore its durable identity",
    ),
  )
}

private fun settleSingleMigration(
  request: MigrationSettlementRequest,
  checkpoint: FeatureTaskRuntimeCheckpointIdentity,
): Boolean {
  val runLoop = request.runLoop
  val context = request.context
  val valid = checkpoint.commitSha == context.headSha &&
    checkpoint.parentSha?.isNotBlank() == true &&
    context.headMessage.let {
      FeatureTaskRuntimeSubtaskCommitIdentity(checkpoint.issueKey, checkpoint.subtaskId).matches(it)
    }
  if (!valid) {
    return refuseSubtaskMigration(
      runLoop,
      refusalRequest(
        request.precedingPhaseId,
        request.branch,
        request.blockedReason,
        "the active subtask has an incomplete durable span: checkpoint '${checkpoint.commitSha}' " +
          "does not prove the current HEAD and matching trailer; operator decision: identify the exact " +
          "active span before resuming",
      ),
    )
  }
  val chainFailure = validateSubtaskMigrationChain(runLoop, listOf(checkpoint))
  return if (chainFailure == null) {
    true
  } else {
    refuseSubtaskMigration(
      runLoop,
      refusalRequest(
        request.precedingPhaseId,
        request.branch,
        request.blockedReason,
        chainFailure,
      ),
    )
  }
}

private fun settleAccumulatedMigration(request: MigrationSettlementRequest): Boolean {
  val runLoop = request.runLoop
  val context = request.context
  val active = request.active
  val precedingPhaseId = request.precedingPhaseId
  val branch = request.branch
  val blockedReason = request.blockedReason
  val latest = active.last()
  val latestIdentity = FeatureTaskRuntimeSubtaskCommitIdentity(latest.issueKey, latest.subtaskId)
  if (latest.commitSha != context.headSha || !latestIdentity.matches(context.headMessage)
  ) {
    return refuseSubtaskMigration(
      runLoop,
      refusalRequest(
        precedingPhaseId,
        branch,
        blockedReason,
        "the durable active-subtask pointer or trailer does not match HEAD '${context.headSha}'; " +
          "operator decision: identify the owned commit span before normalizing it",
      ),
    )
  }
  val chainFailure = validateSubtaskMigrationChain(runLoop, active)
  if (chainFailure != null) {
    return refuseSubtaskMigration(
      runLoop,
      refusalRequest(
        precedingPhaseId,
        branch,
        blockedReason,
        chainFailure,
      ),
    )
  }
  val ownedPaths = context.resolved.workflowOwnedPaths
    .filterNot(::isGovernedSpecPath)
    .filterNot(::isRuntimePrivatePath)
  return writeNormalizedSubtaskCommit(
    NormalizedSubtaskCommitRequest(
      runLoop = runLoop,
      precedingPhaseId = precedingPhaseId,
      branch = branch,
      blockedReason = blockedReason,
      ownedPaths = ownedPaths,
      identities = context.identities,
      active = active,
      headSha = context.headSha,
      message = context.headMessage,
    ),
  )
}

private fun refusalRequest(
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
  reason: String,
  cause: Throwable? = null,
) = SubtaskMigrationRefusalRequest(precedingPhaseId, branch, blockedReason, reason, cause)
