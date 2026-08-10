package skillbill.application.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import java.nio.file.Path

private const val VALIDATE_PHASE_STATUS_COMPLETED = "completed"

/** Agent repair launch within the runtime-owned validate gate cycle. */
fun interface ValidationGateAgentRepairLauncher {
  fun launch(
    findings: ValidationFindingSetProjection,
    repairIteration: Int,
  ): ValidationGateAgentRepairResult
}

sealed interface ValidationGateAgentRepairResult {
  data class Completed(val output: FeatureTaskRuntimePhaseOutput) : ValidationGateAgentRepairResult
  data class Blocked(val reason: String) : ValidationGateAgentRepairResult
}

sealed interface ValidationGateCycleResult {
  /** Fall back to legacy agent-run validate (absent gate declaration). */
  data object AbsentFallback : ValidationGateCycleResult

  data class Terminal(val outcome: ValidationGateCycleTerminalOutcome) : ValidationGateCycleResult
}

sealed interface ValidationGateCycleTerminalOutcome {
  data class Completed(val output: FeatureTaskRuntimePhaseOutput) : ValidationGateCycleTerminalOutcome
  data class Blocked(val reason: String) : ValidationGateCycleTerminalOutcome
}

@Inject
class FeatureTaskRuntimeValidationGateCoordinator(
  private val resolver: ValidationGateResolver,
  private val runner: ValidationGateRunner,
  private val recorder: FeatureTaskRuntimePhaseRecorder,
) {
  @Suppress("LongMethod", "ReturnCount")
  fun execute(
    repoRoot: Path,
    request: FeatureTaskRuntimeRunRequest,
    validationDepth: ValidationDepth,
    changedPaths: List<String>,
    repositoryCheckpoint: String,
    agentRepairLauncher: ValidationGateAgentRepairLauncher,
    onGateRunCount: (Int) -> Unit = {},
  ): ValidationGateCycleResult {
    when (val resolution = resolver.resolve(repoRoot, changedPaths)) {
      is ValidationGateResolution.Absent -> return ValidationGateCycleResult.AbsentFallback
      is ValidationGateResolution.Declared -> {
        val declaration = resolution.declaration
        val measurements = mutableListOf<FeatureTaskRuntimeValidationGateRunRecord>()
        var remainingFindings = emptyList<skillbill.ports.validation.model.ValidationGateFinding>()

        repeat(MAX_VALIDATE_GATE_REPAIR_ITERATIONS) { repairIndex ->
          val cacheEligible = runGate(
            repoRoot = repoRoot,
            declaration = declaration,
            validationDepth = validationDepth,
            cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
            terminalVerifying = false,
          )
          recordGateProgress(request, measurements, cacheEligible, onGateRunCount)

          if (cacheEligible.findings.isEmpty()) {
            val terminal = runGate(
              repoRoot = repoRoot,
              declaration = declaration,
              validationDepth = validationDepth,
              cacheMode = ValidationGateCacheMode.FORCED_FULL,
              terminalVerifying = true,
            )
            recordGateProgress(request, measurements, terminal, onGateRunCount)
            return when {
              terminal.outcome == ValidationGateRunOutcome.REJECTED_ZERO_WORK ->
                terminalBlocked(
                  "Validation gate terminal run reported zero executed work; a satisfied outcome requires " +
                    "pack-attested execution, not a cache-served no-op.",
                )
              terminal.outcome == ValidationGateRunOutcome.PASSED ->
                terminalCompleted(
                  repositoryCheckpoint = repositoryCheckpoint,
                  measurements = measurements,
                  checks = emptyList(),
                )
              else -> terminalBlocked(
                "Validation gate terminal run failed after a clean intermediate result.",
              )
            }
          }

          remainingFindings = cacheEligible.findings
          val projection = ValidationFindingSetProjector.project(remainingFindings)
          if (projection.hasUnreportedRemainder) {
            return terminalBlocked(
              "Validation gate findings exceed the handoff budget (${
                projection.droppedCount
              } unreported); repair cannot succeed while findings remain unreported.",
            )
          }

          when (val repair = agentRepairLauncher.launch(projection, repairIndex + 1)) {
            is ValidationGateAgentRepairResult.Blocked -> return ValidationGateCycleResult.Terminal(
              ValidationGateCycleTerminalOutcome.Blocked(repair.reason),
            )
            is ValidationGateAgentRepairResult.Completed -> Unit
          }
        }

        val finalProjection = ValidationFindingSetProjector.project(remainingFindings)
        return terminalBlocked(
          "Validation gate repair cycle exhausted after $MAX_VALIDATE_GATE_REPAIR_ITERATIONS iterations " +
            "with ${remainingFindings.size} remaining finding(s)" +
            if (finalProjection.droppedCount > 0) {
              " (${
                finalProjection.droppedCount
              } additional findings were omitted from the handoff budget)"
            } else {
              "."
            },
        )
      }
    }
  }

  private fun runGate(
    repoRoot: Path,
    declaration: ValidationGateDeclaration,
    validationDepth: ValidationDepth,
    cacheMode: ValidationGateCacheMode,
    terminalVerifying: Boolean,
  ): ValidationGateRunResult = runner.run(
    ValidationGateRunRequest(
      repoRoot = repoRoot,
      argv = validationGateArgv(declaration, validationDepth, cacheMode),
      cacheMode = cacheMode,
      declaration = declaration,
      terminalVerifying = terminalVerifying,
    ),
  )

  private fun recordGateProgress(
    request: FeatureTaskRuntimeRunRequest,
    measurements: MutableList<FeatureTaskRuntimeValidationGateRunRecord>,
    result: ValidationGateRunResult,
    onGateRunCount: (Int) -> Unit,
  ) {
    measurements += FeatureTaskRuntimeValidationGateRunRecord(
      durationMs = result.durationMs,
      outcome = result.outcome.wireValue,
      cacheMode = result.cacheMode.wireValue,
      executedWorkUnits = result.executedWorkUnits,
    )
    val progress = FeatureTaskRuntimeValidationGateProgress(
      gateRunCount = measurements.size,
      gateRuns = measurements.toList(),
    )
    recorder.persistValidationGateProgress(request.workflowId, progress, request.dbPathOverride)
    request.eventSink.emit(
      FeatureTaskRuntimeRunEvent.ValidationGateProgress(
        workflowId = request.workflowId,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        gateRunCount = progress.gateRunCount,
      ),
    )
    onGateRunCount(progress.gateRunCount)
  }

  private fun terminalCompleted(
    repositoryCheckpoint: String,
    measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
    checks: List<String>,
  ): ValidationGateCycleResult = ValidationGateCycleResult.Terminal(
    ValidationGateCycleTerminalOutcome.Completed(
      output = runtimeOwnedValidationOutput(
        repositoryCheckpoint = repositoryCheckpoint,
        measurements = measurements,
        checks = checks,
      ),
    ),
  )

  private fun terminalBlocked(reason: String): ValidationGateCycleResult =
    ValidationGateCycleResult.Terminal(ValidationGateCycleTerminalOutcome.Blocked(reason))

  companion object {
    fun runtimeOwnedValidationOutput(
      repositoryCheckpoint: String,
      measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
      checks: List<String>,
    ): FeatureTaskRuntimePhaseOutput {
      val payload = JsonSupport.mapToJsonString(
        mapOf(
          "contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
          "status" to VALIDATE_PHASE_STATUS_COMPLETED,
          "summary" to "Validation satisfied by runtime-owned gate execution.",
          "verdict" to FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
          "produced_outputs" to mapOf(
            "validation_result" to mapOf(
              "validation_status" to "passed",
              "checks" to checks,
              "repository_checkpoint" to mapOf("fingerprint" to repositoryCheckpoint),
              "gate_run_count" to measurements.size,
              "gate_runs" to measurements.map { it.toArtifactMap() },
            ),
          ),
        ),
      )
      return FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        iteration = 1,
        payload = payload,
      )
    }
  }
}
