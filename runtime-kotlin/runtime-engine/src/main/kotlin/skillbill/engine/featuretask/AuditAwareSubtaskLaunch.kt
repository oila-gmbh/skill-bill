package skillbill.engine.featuretask

import skillbill.error.AuditRepairProviderSessionError
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.time.Duration.Companion.minutes

internal class AuditAwareSubtaskLaunch(
  private val runLoop: FeatureTaskRuntimeRunLoop,
  private val run: PhaseRun,
  private val prepared: PreparedLaunch,
  private val isReviewPhase: Boolean,
  private val isVerifyFindingsPhase: Boolean,
) {
  private val launchBinding = FeatureTaskRuntimeRunLoopLaunch.prepareAuditLaunch(runLoop, run)

  fun launch(): AgentRunLaunchOutcome {
    val existingCycle = launchBinding?.let {
      runLoop.phaseSettlementService.auditRepairCycle(run.request.workflowId, it.auditAttempt)
    }
    if (existingCycle != null && launchBinding.providerSessionId == null) {
      runLoop.phaseSettlementService.pauseForUnavailableAuditRepairSession(
        requireNotNull(launchBinding),
        "The durable audit cycle has no provider continuation identity, so a fresh session is not allowed.",
      )
      return UnsupportedAgentRunLaunch(
        agent = InstallAgent.fromNormalizedId(run.resolvedAgent.resolvedAgentId, label = "resolved agent"),
        reason = "The original audit provider session could not be proven.",
      )
    }
    return verifyOutcome(launchRequest(request()))
  }

  private fun request(): SkillRunRequest {
    val launched = FeatureTaskRuntimeRunLoopOutputPersistence.launchedModelDirective(run)
    val requestedSessionId = launchBinding?.requestedSessionId ?: run.request.sessionId
    val sessionId = launchBinding?.providerSessionId ?: requestedSessionId
    return SkillRunRequest(
      issueKey = run.request.issueKey,
      repoRoot = run.request.repoRoot,
      timeout = run.request.timeout,
      modelOverride = launched.modelOverride,
      effortOverride = launched.effortOverride,
      compaction = run.compaction,
      promptOverride = prepared.prompt,
      readOnlyPhase = isReviewPhase || isVerifyFindingsPhase,
      progressIdleTimeout = READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes
        .takeIf { isReviewPhase || isVerifyFindingsPhase || launchBinding != null },
      progressProbe = AgentRunProgressProbe {
        launchBinding?.let { binding ->
          runLoop.phaseSettlementService.auditRepairCycle(run.request.workflowId, binding.auditAttempt)
            ?.current?.let { "${it.revision}:${it.stage.wireValue}" }
        }
      },
      auditRepairExecutionId = launchBinding?.executionId,
      auditRepairSessionId = sessionId.takeIf { launchBinding != null },
      auditRepairResume = launchBinding?.providerSessionId != null,
      auditRepairProviderSessionSink = launchBinding?.let { binding ->
        { providerSessionId ->
          runLoop.phaseSettlementService.recordAuditRepairProviderSession(
            binding = binding,
            ownerToken = binding.ownerToken,
            fencingGeneration = binding.fencingGeneration,
            providerSessionId = providerSessionId,
          )
        }
      },
      activityStampSink = runLoop.activityStampWriter.sink(
        workflowId = run.request.workflowId,
        parentWorkflowId = run.request.goalContinuation?.parentWorkflowId,
      ),
    )
  }

  private fun launchRequest(request: SkillRunRequest): AgentRunLaunchOutcome {
    return try {
      runLoop.subtaskLauncher.launch(
        GoalRunnerSubtaskLaunchRequest(
          invokedAgentId = run.resolvedAgent.invokedAgentId,
          configuredAgentOverrideId = run.resolvedAgent.configuredAgentOverrideId,
          skillRunRequest = request,
        ),
      )
    } catch (error: AuditRepairProviderSessionError) {
      runLoop.diagnostics.warning("Audit provider identity failed; the audit launch is blocked.", error)
      return UnsupportedAgentRunLaunch(
        agent = InstallAgent.fromNormalizedId(run.resolvedAgent.resolvedAgentId, label = "resolved agent"),
        reason = error.message.orEmpty(),
      )
    } catch (error: IllegalArgumentException) {
      if (launchBinding != null) {
        runLoop.phaseSettlementService.pauseForUnavailableAuditRepairSession(
          launchBinding,
          "The configured audit provider cannot resume the original session: ${error.message.orEmpty()}",
        )
        return UnsupportedAgentRunLaunch(
          agent = InstallAgent.fromNormalizedId(run.resolvedAgent.resolvedAgentId, label = "resolved agent"),
          reason = "The original audit provider session could not be resumed.",
        )
      }
      throw error
    }
  }

  private fun verifyOutcome(outcome: AgentRunLaunchOutcome): AgentRunLaunchOutcome {
    val facts = outcome as? AgentRunLaunchFacts
    if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT && launchBinding != null) {
      val persistedBinding = runLoop.phaseSettlementService.auditRepairLaunchBinding(
        launchBinding.workflowId,
        launchBinding.executionId,
        launchBinding.requestedSessionId,
      )
      val providerSession = facts?.providerSessionId?.takeIf(String::isNotBlank)
        ?: persistedBinding?.providerSessionId
      if (providerSession == null) {
        runLoop.phaseSettlementService.pauseForUnavailableAuditRepairSession(
          launchBinding,
          "The audit provider did not expose a continuation session identity.",
        )
        return UnsupportedAgentRunLaunch(
          agent = InstallAgent.fromNormalizedId(run.resolvedAgent.resolvedAgentId, label = "resolved agent"),
          reason = "Audit repair provider session identity was unavailable.",
        )
      }
      if (launchBinding.providerSessionId != null && providerSession != launchBinding.providerSessionId) {
        runLoop.phaseSettlementService.pauseForUnavailableAuditRepairSession(
          launchBinding,
          "The audit provider resumed a different session than the durable launch binding.",
        )
        return UnsupportedAgentRunLaunch(
          agent = InstallAgent.fromNormalizedId(run.resolvedAgent.resolvedAgentId, label = "resolved agent"),
          reason = "Audit repair provider session '$providerSession' does not match the durable session.",
        )
      }
      runLoop.phaseSettlementService.recordAuditRepairProviderSession(
        binding = launchBinding,
        ownerToken = launchBinding.ownerToken,
        fencingGeneration = launchBinding.fencingGeneration,
        providerSessionId = providerSession,
      )
    }
    return outcome
  }
}
