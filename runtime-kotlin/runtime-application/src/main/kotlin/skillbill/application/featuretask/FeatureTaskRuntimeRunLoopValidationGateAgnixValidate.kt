package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

@Inject
class FeatureTaskRuntimeRunLoopValidationGateAgnixValidate {
  internal fun validationGateCycleRequest(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ValidationGateCycleRequestArgs,
  ): ValidationGateCycleRequest {
    val run = args.context.attempt.run
    val state = args.context.attempt.state
    val iteration = args.context.attempt.iteration
    val observability = args.context.attempt.observability
    val phaseTokenAccumulator = args.context.phaseTokenAccumulator
    val validationDepth = run.request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT
    return ValidationGateCycleRequest(
      repoRoot = run.request.repoRoot,
      request = run.request,
      validationDepth = validationDepth,
      changedPaths = runLoop.collaborators.validationGateContinued2.validationChangedPaths(runLoop, run),
      repositoryCheckpoint = args.checkpoint,
      agentTriageLauncher = ValidationGateAgentTriageLauncher { findings ->
        runLoop.collaborators.validationGate.launchValidationGateTriage(
          runLoop,
          ValidationGateTriageArgs(
            args.context,
            findings,
          ),
        )
      },
      agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, repairIteration, triagePlan ->
        runLoop.collaborators.validationGateContinued2.launchValidationGateRepair(
          runLoop,
          ValidationGateRepairArgs(
            context = args.context,
            findings = findings,
            repairTurn = repairIteration,
            triagePlan = triagePlan,
          ),
        )
      },
    )
  }

  internal fun resolveValidationGateCheckpoint(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): String? =
    runLoop.phaseGates.gitOperations.repositoryFingerprint(run.request.repoRoot).value.takeIf(String::isNotBlank)

  internal fun settleValidationGateCycleResult(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: SettleValidationGateCycleArgs,
  ): PhaseOutcome {
    val run = args.context.attempt.run
    val state = args.context.attempt.state
    val observability = args.context.attempt.observability
    val phaseTokenAccumulator = args.context.phaseTokenAccumulator
    val iteration = args.context.attempt.iteration
    return when (args.cycle) {
      ValidationGateCycleResult.AbsentFallback ->
        runLoop.collaborators.validationGateContinued3.runPhaseAttempts(
          runLoop,
          run.copy(agentRunValidateFallback = true),
          runLoop.state,
          runLoop.observability,
          runLoop.phaseTokenAccumulator,
        )
      is ValidationGateCycleResult.Terminal -> {
        runLoop.observability.started(
          run.phaseId,
          run.resolvedAgent.resolvedAgentId,
          iteration,
          run.modelDirective,
          FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
        )
        when (val terminal = args.cycle.outcome) {
          is ValidationGateCycleTerminalOutcome.Completed ->
            runLoop.collaborators.validationGateContinued2.settleRuntimeOwnedValidation(
              runLoop,
              run,
              iteration,
              terminal.output.payload,
              runLoop.observability,
            )
          is ValidationGateCycleTerminalOutcome.Blocked ->
            runLoop.collaborators.phaseAttempts.blockInPhase(
              runLoop,
              PhaseBlockRequest(
                run = run,
                attemptCount = iteration,
                reason = terminal.reason,
                observability = runLoop.observability,
                failureDisposition = terminal.failureDisposition
                  ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
              ),
            )
        }
      }
    }
  }
}
