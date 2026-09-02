package skillbill.application.featuretask

import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.workflow.repoRoot
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

internal fun FeatureTaskRuntimeRunLoop.validationGateCycleRequest(
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
    changedPaths = validationChangedPaths(run),
    repositoryCheckpoint = args.checkpoint,
    agentTriageLauncher = ValidationGateAgentTriageLauncher { findings ->
      launchValidationGateTriage(ValidationGateTriageArgs(args.context, findings))
    },
    agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, repairIteration, triagePlan ->
      launchValidationGateRepair(
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

internal fun FeatureTaskRuntimeRunLoop.resolveValidationGateCheckpoint(run: PhaseRun): String? =
  gitOperations.repositoryFingerprint(run.request.repoRoot).value.takeIf(String::isNotBlank)

internal fun FeatureTaskRuntimeRunLoop.settleValidationGateCycleResult(
  args: SettleValidationGateCycleArgs,
): PhaseOutcome {
  val run = args.context.attempt.run
  val state = args.context.attempt.state
  val observability = args.context.attempt.observability
  val phaseTokenAccumulator = args.context.phaseTokenAccumulator
  val iteration = args.context.attempt.iteration
  return when (args.cycle) {
    ValidationGateCycleResult.AbsentFallback ->
      runPhaseAttempts(
        run.copy(agentRunValidateFallback = true),
        state,
        observability,
        phaseTokenAccumulator,
      )
    is ValidationGateCycleResult.Terminal -> {
      observability.started(
        run.phaseId,
        run.resolvedAgent.resolvedAgentId,
        iteration,
        run.modelDirective,
        FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
      )
      when (val terminal = args.cycle.outcome) {
        is ValidationGateCycleTerminalOutcome.Completed ->
          settleRuntimeOwnedValidation(run, iteration, terminal.output.payload, observability)
        is ValidationGateCycleTerminalOutcome.Blocked ->
          blockInPhase(
            PhaseBlockRequest(
              run = run,
              attemptCount = iteration,
              reason = terminal.reason,
              observability = observability,
              failureDisposition = terminal.failureDisposition
                ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
            ),
          )
      }
    }
  }
}
