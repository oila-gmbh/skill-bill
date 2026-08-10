package skillbill.application.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateProgressStore
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFinding
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

@Inject
class FeatureTaskRuntimeValidationGateProgressStore(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
) : ValidationGateProgressStore {
  override fun persist(workflowId: String, progress: FeatureTaskRuntimeValidationGateProgress, dbOverride: String?) {
    recorder.persistValidationGateProgress(workflowId, progress, dbOverride)
  }
}

@Inject
class FeatureTaskRuntimeValidationGateCoordinator(
  private val resolver: ValidationGateResolver,
  private val runner: ValidationGateRunner,
  private val progressStore: ValidationGateProgressStore,
  private val suppressionDeltaService: FeatureTaskRuntimeSuppressionDeltaService,
) {
  @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
  fun execute(cycle: ValidationGateCycleRequest, onGateRunCount: (Int) -> Unit = {}): ValidationGateCycleResult {
    val repoRoot = cycle.repoRoot
    val request = cycle.request
    val validationDepth = cycle.validationDepth
    val changedPaths = cycle.changedPaths
    val agentRepairLauncher = cycle.agentRepairLauncher
    when (val resolution = resolver.resolve(repoRoot, changedPaths)) {
      is ValidationGateResolution.Absent -> return ValidationGateCycleResult.AbsentFallback
      is ValidationGateResolution.Declared -> {
        val declaration = resolution.declaration
        val measurements = mutableListOf<FeatureTaskRuntimeValidationGateRunRecord>()
        var repairsUsed = 0
        val harvestedJustifications = mutableListOf<SuppressionJustification>()

        while (true) {
          val intermediate = runGate(
            repoRoot = repoRoot,
            declaration = declaration,
            validationDepth = validationDepth,
            cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
            terminalVerifying = false,
          )
          recordGateProgress(request, measurements, intermediate, onGateRunCount)

          if (intermediate.outcome == ValidationGateRunOutcome.PASSED) {
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
                  measurements = measurements,
                )
              terminal.outcome == ValidationGateRunOutcome.PASSED ->
                settleSuppressionGate(
                  cycle = cycle,
                  declaration = declaration,
                  measurements = measurements,
                  justifications = harvestedJustifications,
                )
              else -> terminalBlocked(
                "Validation gate terminal run failed after a clean intermediate result.",
                measurements = measurements,
              )
            }
          }

          val findingsForRepair = if (intermediate.findings.isNotEmpty()) {
            intermediate.findings
          } else {
            listOf(unparseableGateFailureFinding(intermediate))
          }
          val projection = ValidationFindingSetProjector.project(findingsForRepair)
          if (projection.hasUnreportedRemainder) {
            persistRemainingFindings(request, measurements, projection, onGateRunCount)
            return terminalBlocked(
              "Validation gate findings exceed the handoff budget (${
                projection.droppedCount
              } unreported); repair cannot succeed while findings remain unreported.",
              remainingFindings = projection,
              measurements = measurements,
            )
          }

          if (repairsUsed >= MAX_VALIDATE_GATE_REPAIR_ITERATIONS) {
            persistRemainingFindings(request, measurements, projection, onGateRunCount)
            return terminalBlocked(
              "Validation gate repair cycle exhausted after $MAX_VALIDATE_GATE_REPAIR_ITERATIONS iterations " +
                "with ${findingsForRepair.size} remaining finding(s)" +
                if (projection.droppedCount > 0) {
                  " (${projection.droppedCount} additional findings were omitted from the handoff budget)"
                } else {
                  "."
                },
              remainingFindings = projection,
              measurements = measurements,
            )
          }

          when (val repair = agentRepairLauncher.launch(projection, repairsUsed + 1)) {
            is ValidationGateAgentRepairResult.Blocked -> return ValidationGateCycleResult.Terminal(
              ValidationGateCycleTerminalOutcome.Blocked(
                reason = repair.reason,
                remainingFindings = projection,
                measurements = measurements,
              ),
            )
            is ValidationGateAgentRepairResult.Completed -> {
              harvestedJustifications += extractJustifications(repair.output)
            }
          }
          repairsUsed++
        }
      }
    }
  }

  private fun settleSuppressionGate(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
    justifications: List<SuppressionJustification>,
  ): ValidationGateCycleResult {
    val measured = suppressionDeltaService.measure(
      repoRoot = cycle.repoRoot,
      baseRef = cycle.baseRef,
      changedPaths = cycle.changedPaths,
      declaration = declaration,
    ).getOrElse { error ->
      return terminalBlocked(
        "Validation suppression gate could not measure the suppression delta: ${error.message.orEmpty()}",
        measurements = measurements,
      )
    }
    return when (val decision = SuppressionJustificationGate.evaluate(measured, justifications)) {
      is SuppressionGateDecision.Block -> terminalBlocked(decision.reason, measurements = measurements)
      is SuppressionGateDecision.Allow -> terminalCompleted(
        repositoryCheckpoint = cycle.repositoryCheckpoint,
        measurements = measurements,
        checks = emptyList(),
        justifications = decision.justifications,
      )
    }
  }

  private fun extractJustifications(output: FeatureTaskRuntimePhaseOutput): List<SuppressionJustification> {
    val envelope = JsonSupport.parseObjectOrNull(output.payload)?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: return emptyList()
    val produced = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]).orEmpty()
    val validationResult = JsonSupport.anyToStringAnyMap(produced["validation_result"]).orEmpty()
    val raw = validationResult["suppression_justifications"]
      ?: produced["suppression_justifications"]
    return when (val parsed = SuppressionJustification.parseAll(raw)) {
      is SuppressionJustification.ParseResult.Present -> parsed.values
      else -> emptyList()
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
    persistProgress(request, measurements, remainingFindings = null, onGateRunCount)
  }

  private fun persistRemainingFindings(
    request: FeatureTaskRuntimeRunRequest,
    measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
    remainingFindings: ValidationFindingSetProjection,
    onGateRunCount: (Int) -> Unit,
  ) {
    persistProgress(request, measurements, remainingFindings, onGateRunCount)
  }

  private fun persistProgress(
    request: FeatureTaskRuntimeRunRequest,
    measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
    remainingFindings: ValidationFindingSetProjection?,
    onGateRunCount: (Int) -> Unit,
  ) {
    val progress = FeatureTaskRuntimeValidationGateProgress(
      gateRunCount = measurements.size,
      gateRuns = measurements.toList(),
      remainingFindings = remainingFindings?.toHandoffMaps().orEmpty(),
      remainingFindingsDroppedCount = remainingFindings?.droppedCount ?: 0,
    )
    progressStore.persist(request.workflowId, progress, request.dbPathOverride)
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
    justifications: List<SuppressionJustification> = emptyList(),
  ): ValidationGateCycleResult = ValidationGateCycleResult.Terminal(
    ValidationGateCycleTerminalOutcome.Completed(
      output = runtimeOwnedValidationOutput(
        repositoryCheckpoint = repositoryCheckpoint,
        measurements = measurements,
        checks = checks,
        justifications = justifications,
      ),
    ),
  )

  private fun terminalBlocked(
    reason: String,
    remainingFindings: ValidationFindingSetProjection? = null,
    measurements: List<FeatureTaskRuntimeValidationGateRunRecord> = emptyList(),
  ): ValidationGateCycleResult = ValidationGateCycleResult.Terminal(
    ValidationGateCycleTerminalOutcome.Blocked(
      reason = reason,
      remainingFindings = remainingFindings,
      measurements = measurements,
    ),
  )

  companion object {
    fun unparseableGateFailureFinding(result: ValidationGateRunResult): ValidationGateFinding = ValidationGateFinding(
      module = "<validation-gate>",
      ruleOrTestId = "unparseable_gate_failure",
      message = "Validation gate reported outcome=${result.outcome.wireValue} exit=${result.exitCode} " +
        "without parseable findings; repair the underlying failure the gate detected.",
      location = null,
    )

    fun runtimeOwnedValidationOutput(
      repositoryCheckpoint: String,
      measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
      checks: List<String>,
      justifications: List<SuppressionJustification> = emptyList(),
    ): FeatureTaskRuntimePhaseOutput {
      val validationResult = linkedMapOf<String, Any?>(
        "validation_status" to "passed",
        "checks" to checks,
        "repository_checkpoint" to mapOf("fingerprint" to repositoryCheckpoint),
        "gate_run_count" to measurements.size,
        "gate_runs" to measurements.map { it.toArtifactMap() },
      )
      if (justifications.isNotEmpty()) {
        validationResult["suppression_justifications"] = justifications.map { it.toArtifactMap() }
      }
      val payload = JsonSupport.mapToJsonString(
        mapOf(
          "contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
          "status" to VALIDATE_PHASE_STATUS_COMPLETED,
          "summary" to "Validation satisfied by runtime-owned gate execution.",
          "verdict" to FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
          "produced_outputs" to mapOf(
            "validation_result" to validationResult,
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
