package skillbill.application.goalrunner

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.application.goalrunner.model.GoalChildOrphanReplacementRequest
import skillbill.application.goalrunner.model.GoalChildOrphanReplacementResult
import skillbill.application.goalrunner.model.GoalRunnerAppliedRepair
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeRepairRequest
import skillbill.application.goalrunner.model.GoalRunnerRepairCoordinatorDeps
import skillbill.application.goalrunner.model.GoalRunnerRepairRequest
import skillbill.application.goalrunner.model.GoalRunnerRepairResult
import skillbill.application.goalrunner.model.GoalRunnerRepairStatus
import skillbill.application.goalrunner.model.PortableReviewBaselineWriteRequest
import skillbill.application.goalrunner.planning.goalPlanningHardResetRemedy
import skillbill.ports.goalrunner.persistence.model.PortableReviewBaselineRepairContext
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOrphanChildReplacementWrite
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path

class GoalRunnerRepairCoordinator(
  deps: GoalRunnerRepairCoordinatorDeps,
) {
  private val manifestStore = deps.manifestStore
  private val phaseRecorder = deps.phaseRecorder
  private val workerSupervisor = deps.workerSupervisor
  private val childRepairStore = deps.childRepairStore
  private val gitOperations = deps.gitOperations
  private val portableReviewBaselinePersistence = deps.portableReviewBaselinePersistence
  private val repositoryRoot = deps.repositoryRoot
  private val repositoryEnclosingRootPort = deps.repositoryEnclosingRootPort

  fun repair(request: GoalRunnerRepairRequest): GoalRunnerRepairResult {
    val repoRoot = request.repoRoot ?: repositoryRoot.path
    val loaded = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, repoRoot)
      ?: return notFound(request.issueKey)
    manifestStore.bindRepositoryIdentity(
      loaded.parentWorkflowId,
      goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort),
      request.dbPathOverride,
    )
    if (request.replaceOrphan) {
      return applyOrphanReplacement(request, loaded, repoRoot)
    }
    val children = loaded.manifest.subtasks
      .filter { request.subtaskId == null || it.id == request.subtaskId }
      .filter { !it.workflowId.isNullOrBlank() }
    val repositoryIdentity = goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)
    val diagnoses = children.map { subtask ->
      childRepairStore.diagnoseChildWedges(
        GoalRunnerChildWedgeDiagnosisRequest(
          workflowId = requireNotNull(subtask.workflowId),
          issueKey = request.issueKey,
          subtaskId = subtask.id,
          subtasks = loaded.manifest.subtasks,
          repoRoot = repoRoot,
          dbPathOverride = request.dbPathOverride,
          portableContext = PortableReviewBaselineRepairContext(
            manifest = loaded.manifest,
            repositoryIdentity = repositoryIdentity,
            subtaskId = subtask.id,
            workflowId = requireNotNull(subtask.workflowId),
          ),
        ),
      )
    }
    val wedged = diagnoses.filterNot(GoalRunnerChildWedgeDiagnosis::isHealthy)
    return when {
      request.subtaskId != null && children.isEmpty() ->
        subtaskNotFound(request, loaded.parentWorkflowId)
      wedged.isEmpty() ->
        healthyResult(request, loaded.parentWorkflowId, diagnoses)
      wedged.any { diagnosis -> diagnosis.wedges.any { it.wedgeClass.operatorRequired } } ->
        operatorRequiredResult(request, loaded.parentWorkflowId, diagnoses)
      !request.apply ->
        inspectedResult(request, loaded.parentWorkflowId, diagnoses)
      else ->
        applyRepairs(
          ApplyRepairsArgs(
            request,
            loaded.parentWorkflowId,
            loaded.manifest,
            repositoryIdentity,
            diagnoses,
            wedged,
            repoRoot,
          ),
        )
    }
  }

  private fun notFound(issueKey: String): GoalRunnerRepairResult = GoalRunnerRepairResult(
    issueKey = issueKey,
    status = GoalRunnerRepairStatus.NOT_FOUND,
  )

  private fun subtaskNotFound(request: GoalRunnerRepairRequest, parentWorkflowId: String): GoalRunnerRepairResult =
    GoalRunnerRepairResult(
      issueKey = request.issueKey,
      status = GoalRunnerRepairStatus.NOT_FOUND,
      parentWorkflowId = parentWorkflowId,
      refusalReason = "No child workflow is bound to subtask ${request.subtaskId}.",
    )

  private fun healthyResult(
    request: GoalRunnerRepairRequest,
    parentWorkflowId: String,
    diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
  ): GoalRunnerRepairResult {
    val status = if (request.apply && request.subtaskId != null) {
      GoalRunnerRepairStatus.NOT_WEDGED
    } else {
      GoalRunnerRepairStatus.HEALTHY
    }
    val healthy = diagnoses.firstOrNull { request.subtaskId == null || it.subtaskId == request.subtaskId }
    return GoalRunnerRepairResult(
      issueKey = request.issueKey,
      status = status,
      parentWorkflowId = parentWorkflowId,
      diagnoses = diagnoses,
      refusalReason = if (status == GoalRunnerRepairStatus.NOT_WEDGED) {
        "Subtask ${request.subtaskId} is not wedged; passed checks: " +
          (healthy?.passedChecks?.joinToString(", ") ?: "none")
      } else {
        "Goal children passed every repair check; no durable write."
      },
    )
  }

  private fun operatorRequiredResult(
    request: GoalRunnerRepairRequest,
    parentWorkflowId: String,
    diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
  ): GoalRunnerRepairResult = GoalRunnerRepairResult(
    issueKey = request.issueKey,
    status = if (request.apply) GoalRunnerRepairStatus.OPERATOR_REQUIRED else GoalRunnerRepairStatus.INSPECTED,
    parentWorkflowId = parentWorkflowId,
    diagnoses = diagnoses,
    refusalReason = "Phase-output contract version is incompatible with the installed runtime. " +
      "Recover with: '${goalPlanningHardResetRemedy(request.issueKey)}'.",
  )

  private fun inspectedResult(
    request: GoalRunnerRepairRequest,
    parentWorkflowId: String,
    diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
  ): GoalRunnerRepairResult = GoalRunnerRepairResult(
    issueKey = request.issueKey,
    status = GoalRunnerRepairStatus.INSPECTED,
    parentWorkflowId = parentWorkflowId,
    diagnoses = diagnoses,
  )

  private fun applyRepairs(args: ApplyRepairsArgs): GoalRunnerRepairResult {
    val applied = mutableListOf<GoalRunnerAppliedRepair>()
    for (diagnosis in args.wedged) {
      val workflowId = diagnosis.workflowId ?: continue
      if (childWorkerLeaseLive(workflowId, args.request.dbPathOverride)) {
        return GoalRunnerRepairResult(
          issueKey = args.request.issueKey,
          status = GoalRunnerRepairStatus.LIVE_LEASE_REFUSED,
          parentWorkflowId = args.parentWorkflowId,
          diagnoses = args.diagnoses,
          appliedRepairs = applied,
          liveLeaseWorkflowId = workflowId,
          refusalReason =
          "Child workflow '$workflowId' holds a live worker lease; a running worker owns that state.",
        )
      }
      val repairResult = childRepairStore.applyChildWedgeRepairs(
        GoalRunnerChildWedgeRepairRequest(
          workflowId = workflowId,
          issueKey = args.request.issueKey,
          subtaskId = diagnosis.subtaskId,
          wedgeClasses = diagnosis.wedges.map { it.wedgeClass },
          repoRoot = args.repoRoot,
          dbPathOverride = args.request.dbPathOverride,
          portableContext = PortableReviewBaselineRepairContext(
            manifest = args.manifest,
            repositoryIdentity = args.repositoryIdentity,
            subtaskId = diagnosis.subtaskId,
            workflowId = workflowId,
          ),
        ),
      )
      applied += repairResult.repairs
    }
    return GoalRunnerRepairResult(
      issueKey = args.request.issueKey,
      status = GoalRunnerRepairStatus.REPAIRED,
      parentWorkflowId = args.parentWorkflowId,
      diagnoses = args.diagnoses,
      appliedRepairs = applied,
    )
  }

  private fun applyOrphanReplacement(
    request: GoalRunnerRepairRequest,
    loaded: GoalRunnerManifestState,
    repoRoot: Path,
  ): GoalRunnerRepairResult {
    if (!request.apply) {
      return GoalRunnerRepairResult(
        issueKey = request.issueKey,
        status = GoalRunnerRepairStatus.INSPECTED,
        parentWorkflowId = loaded.parentWorkflowId,
        refusalReason = "Pass --apply to retire the orphan child workflow and capture a replacement baseline.",
      )
    }
    val subtaskId = requireNotNull(request.subtaskId)
    val canonicalRepository = repositoryEnclosingRootPort.canonicalPath(repoRoot)
    val reviewMode = manifestStore.reviewMode(loaded.parentWorkflowId, request.dbPathOverride)
    val replacementRequest = GoalChildOrphanReplacementRequest(
      state = loaded,
      subtaskId = subtaskId,
      repoRoot = repoRoot,
      repositoryIdentity = repositoryEnclosingRootPort.repositoryIdentity(canonicalRepository),
      gitOperations = gitOperations,
      codeReviewMode = reviewMode,
    )
    return when (val replacement = GoalChildOrphanReplacement.replaceOrphan(replacementRequest)) {
      is GoalChildOrphanReplacementResult.Blocked -> GoalRunnerRepairResult(
        issueKey = request.issueKey,
        status = GoalRunnerRepairStatus.OPERATOR_REQUIRED,
        parentWorkflowId = loaded.parentWorkflowId,
        refusalReason = PortableReviewBaselineRehydrator.blockedReasonMessage(replacement.reason, replacement.detail),
      )
      is GoalChildOrphanReplacementResult.Replaced -> writeReplacedOrphan(
        WriteReplacedOrphanArgs(
          request = request,
          loaded = loaded,
          repoRoot = repoRoot,
          subtaskId = subtaskId,
          replacementRequest = replacementRequest,
          replacement = replacement,
          reviewMode = reviewMode,
        ),
      )
    }
  }

  private fun writeReplacedOrphan(args: WriteReplacedOrphanArgs): GoalRunnerRepairResult {
    val subtask = args.loaded.manifest.subtasks.single { it.id == args.subtaskId }
    val setup = GoalChildOrphanReplacement.childWorkflowSetup(
      args.replacementRequest,
      args.replacement,
      governedSpecPath = subtask.specPath,
      reviewPolicy = GoalRunnerReviewPolicy(
        codeReviewMode = args.reviewMode ?: CodeReviewExecutionMode.DEFAULT,
        agentAddonSelection = manifestStore.reviewPolicy(args.loaded.parentWorkflowId, args.request.dbPathOverride)
          ?.agentAddonSelection ?: AgentAddonSelection(),
      ),
    )
    val saved = manifestStore.replaceOrphanChildWorkflow(
      GoalRunnerOrphanChildReplacementWrite(
        state = args.replacement.state,
        subtaskId = args.subtaskId,
        sourceWorkflowId = args.replacement.sourceWorkflowId,
        setup = setup,
        auditEntry = args.replacement.auditEntry,
        dbPathOverride = args.request.dbPathOverride,
      ),
    )
    PortableReviewBaselineWriter(portableReviewBaselinePersistence).persistBeforeImplementation(
      PortableReviewBaselineWriteRequest(
        repoRoot = args.repoRoot,
        manifest = saved.manifest,
        subtaskId = args.subtaskId,
        workflowId = args.replacement.replacementWorkflowId,
        repositoryIdentity = args.replacementRequest.repositoryIdentity,
        goalBranch = setup.goalBranch,
        reviewBaseline = args.replacement.reviewBaseline,
      ),
    )
    return GoalRunnerRepairResult(
      issueKey = args.request.issueKey,
      status = GoalRunnerRepairStatus.REPAIRED,
      parentWorkflowId = args.loaded.parentWorkflowId,
      refusalReason = "Replaced orphan child '${args.replacement.sourceWorkflowId}' with " +
        "'${args.replacement.replacementWorkflowId}'.",
    )
  }

  private fun childWorkerLeaseLive(workflowId: String, dbPathOverride: String?): Boolean {
    val ownership = runCatching { phaseRecorder.workerOwnership(workflowId, dbPathOverride) }.getOrNull()
      ?: return false
    return when (workerSupervisor.inspect(ownership)) {
      FeatureTaskRuntimeProcessInspection.ExactLive -> true
      FeatureTaskRuntimeProcessInspection.NotRunning,
      is FeatureTaskRuntimeProcessInspection.OwnershipMismatch,
      is FeatureTaskRuntimeProcessInspection.Unsupported,
      -> false
    }
  }

  private data class ApplyRepairsArgs(
    val request: GoalRunnerRepairRequest,
    val parentWorkflowId: String,
    val manifest: DecompositionManifest,
    val repositoryIdentity: String,
    val diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
    val wedged: List<GoalRunnerChildWedgeDiagnosis>,
    val repoRoot: Path,
  )

  private data class WriteReplacedOrphanArgs(
    val request: GoalRunnerRepairRequest,
    val loaded: GoalRunnerManifestState,
    val repoRoot: Path,
    val subtaskId: Int,
    val replacementRequest: GoalChildOrphanReplacementRequest,
    val replacement: GoalChildOrphanReplacementResult.Replaced,
    val reviewMode: CodeReviewExecutionMode?,
  )
}
