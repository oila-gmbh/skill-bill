package skillbill.application.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.emitFeatureTaskRuntimeEventSafely
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCyclePhase
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.config.model.applyValidationGateGradleWrapper
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRepairWindowPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

private const val BUILD_PHASE_STATUS_COMPLETED = "completed"

private data class BuildGateCycleState(
  val cycle: ValidationGateCycleRequest,
  val measurements: MutableList<FeatureTaskRuntimeValidationGateRunRecord>,
  val onGateRunCount: (Int) -> Unit,
)

@Inject
class FeatureTaskRuntimeBuildGateCoordinator(
  private val resolver: ValidationGateResolver,
  private val runner: ValidationGateRunner,
  private val progressStore: FeatureTaskRuntimeBuildGateProgressStore,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  fun execute(cycle: ValidationGateCycleRequest, onGateRunCount: (Int) -> Unit = {}): ValidationGateCycleResult =
    when (val resolution = resolver.resolve(cycle.changedPaths)) {
      is ValidationGateResolution.Absent ->
        terminalBlockedResult("Dominant platform pack declares no validation_gate; build cannot run.")
      is ValidationGateResolution.Incompatible -> terminalBlockedResult(resolution.reason)
      is ValidationGateResolution.Declared -> {
        val declaration = resolution.declaration
        if (declaration.buildCommand == null || declaration.cacheBypassingBuildCommand == null) {
          terminalBlockedResult(
            "Pack '${resolution.packSlug}' declares validation_gate but no build_command pair for the build phase.",
          )
        } else {
          checkAndRepair(cycle, declaration, onGateRunCount)
        }
      }
    }

  private fun checkAndRepair(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    onGateRunCount: (Int) -> Unit,
  ): ValidationGateCycleResult {
    val loaded = progressStore.load(cycle.request.workflowId, cycle.request.dbPathOverride)
    val measurements = loaded?.gateRuns?.toMutableList() ?: mutableListOf()
    val state = BuildGateCycleState(cycle, measurements, onGateRunCount)

    if (loaded?.repairWindowPhase == FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN) {
      return repairLoop(
        state = state,
        declaration = declaration,
        openFindings = decodeBuildPersistedFindings(loaded.completeFindings),
        initialRepairsUsed = operatorResumeRepairTurns(loaded.repairsUsed),
      )
    }

    val discovery = runGate(cycle, declaration, ValidationGateCyclePhase.INITIAL_DISCOVERY)
    val discoveryFindings = buildFindingsForRepairFromResult(discovery)
    recordGateProgress(
      state = state,
      result = discovery,
      findings = discoveryFindings,
      repairWindowPhase = repairWindowPhaseFor(discoveryFindings),
      repairsUsed = 0,
    )
    if (discoveryFindings.isEmpty()) {
      return terminalCompletedResult(cycle.repositoryCheckpoint, measurements)
    }
    return repairLoop(
      state = state,
      declaration = declaration,
      openFindings = discoveryFindings,
      initialRepairsUsed = 0,
    )
  }

  private fun repairLoop(
    state: BuildGateCycleState,
    declaration: ValidationGateDeclaration,
    openFindings: List<ValidationGateFinding>,
    initialRepairsUsed: Int,
  ): ValidationGateCycleResult {
    val measurements = state.measurements
    var repairsUsed = initialRepairsUsed
    val projection = ValidationFindingSetProjection(findings = openFindings)
    if (repairsUsed >= MAX_REPAIR_TURNS) {
      persistProgress(
        state = state,
        repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
        remainingFindings = projection,
        completeFindings = openFindings,
        repairsUsed = repairsUsed,
      )
      return terminalBlockedResult(
        "Build gate still reports ${openFindings.size} finding(s) after $MAX_REPAIR_TURNS repair turn(s).",
        remainingFindings = projection,
        measurements = measurements,
      )
    }
    persistProgress(
      state = state,
      repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
      remainingFindings = null,
      completeFindings = openFindings,
      repairsUsed = repairsUsed,
    )
    when (val repair = state.cycle.agentRepairLauncher.launch(projection, repairsUsed + 1)) {
      is ValidationGateAgentRepairResult.Blocked -> return terminalBlockedResult(
        repair.reason,
        remainingFindings = projection,
        measurements = measurements,
      )
      is ValidationGateAgentRepairResult.Completed -> repairsUsed++
    }
    return verifyAfterRepair(state = state, declaration = declaration, repairsUsed = repairsUsed)
  }

  private fun verifyAfterRepair(
    state: BuildGateCycleState,
    declaration: ValidationGateDeclaration,
    repairsUsed: Int,
  ): ValidationGateCycleResult {
    val cycle = state.cycle
    val measurements = state.measurements
    val verify = runGate(cycle, declaration, ValidationGateCyclePhase.POST_REPAIR_VERIFY)
    val verifyFindings = buildFindingsForRepairFromResult(verify)
    recordGateProgress(
      state = state,
      result = verify,
      findings = verifyFindings,
      repairWindowPhase = repairWindowPhaseFor(verifyFindings),
      repairsUsed = repairsUsed,
    )
    if (verifyFindings.isEmpty()) {
      return terminalCompletedResult(cycle.repositoryCheckpoint, measurements)
    }
    val remaining = ValidationFindingSetProjection(findings = verifyFindings)
    persistProgress(
      state = state,
      repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
      remainingFindings = remaining,
      completeFindings = verifyFindings,
      repairsUsed = repairsUsed,
    )
    return terminalBlockedResult(
      "Build gate still reports ${verifyFindings.size} finding(s) after the build agent session.",
      remainingFindings = remaining,
      measurements = measurements,
    )
  }

  private fun repairWindowPhaseFor(
    findings: List<ValidationGateFinding>,
  ): FeatureTaskRuntimeValidationGateRepairWindowPhase = if (findings.isEmpty()) {
    FeatureTaskRuntimeValidationGateRepairWindowPhase.NONE
  } else {
    FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN
  }

  private fun runGate(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    cyclePhase: ValidationGateCyclePhase,
  ): ValidationGateRunResult {
    val packArgv = buildGateArgv(declaration, cyclePhase)
    val cacheMode = when (cyclePhase) {
      ValidationGateCyclePhase.INITIAL_DISCOVERY -> ValidationGateCacheMode.CACHE_ELIGIBLE
      ValidationGateCyclePhase.POST_REPAIR_VERIFY -> ValidationGateCacheMode.FORCED_FULL
    }
    val gradleWrapper = repoLocalConfig
      .readRepoLocalConfig(ReadRepoLocalConfigRequest(cycle.repoRoot))
      .config
      .validationGate
      .gradleWrapper
    return runner.run(
      ValidationGateRunRequest(
        repoRoot = cycle.repoRoot,
        argv = applyValidationGateGradleWrapper(packArgv, gradleWrapper),
        cacheMode = cacheMode,
        declaration = declaration,
        terminalVerifying = cyclePhase == ValidationGateCyclePhase.POST_REPAIR_VERIFY,
        findingParseMode = ValidationGateFindingParseMode.COLLECT_ALL,
      ),
    )
  }

  private fun recordGateProgress(
    state: BuildGateCycleState,
    result: ValidationGateRunResult,
    findings: List<ValidationGateFinding>,
    repairWindowPhase: FeatureTaskRuntimeValidationGateRepairWindowPhase,
    repairsUsed: Int,
  ) {
    state.measurements += FeatureTaskRuntimeValidationGateRunRecord(
      durationMs = result.durationMs,
      outcome = result.outcome.wireValue,
      cacheMode = result.cacheMode.wireValue,
      executedWorkUnits = result.executedWorkUnits,
    )
    persistProgress(
      state = state,
      repairWindowPhase = repairWindowPhase,
      remainingFindings = null,
      completeFindings = findings,
      repairsUsed = repairsUsed,
    )
  }

  private fun persistProgress(
    state: BuildGateCycleState,
    repairWindowPhase: FeatureTaskRuntimeValidationGateRepairWindowPhase,
    remainingFindings: ValidationFindingSetProjection?,
    completeFindings: List<ValidationGateFinding>,
    repairsUsed: Int,
  ) {
    val progress = FeatureTaskRuntimeValidationGateProgress(
      gateRunCount = state.measurements.size,
      gateRuns = state.measurements.toList(),
      remainingFindings = remainingFindings?.toHandoffMaps().orEmpty(),
      completeFindings = ValidationFindingSetProjection(findings = completeFindings).toHandoffMaps(),
      repairWindowPhase = repairWindowPhase,
      repairsUsed = repairsUsed,
    )
    progressStore.persist(state.cycle.request.workflowId, progress, state.cycle.request.dbPathOverride)
    emitFeatureTaskRuntimeEventSafely(
      diagnostics = diagnostics,
      seam = "BuildGateProgress event-sink emission",
    ) {
      state.cycle.request.eventSink.emit(
        FeatureTaskRuntimeRunEvent.ValidationGateProgress(
          workflowId = state.cycle.request.workflowId,
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
          gateRunCount = progress.gateRunCount,
        ),
      )
    }
    state.onGateRunCount(progress.gateRunCount)
  }

  companion object {
    const val MAX_REPAIR_TURNS: Int = 1

    private fun operatorResumeRepairTurns(repairsUsed: Int): Int =
      if (repairsUsed >= MAX_REPAIR_TURNS) 0 else repairsUsed

    fun runtimeOwnedBuildOutput(
      repositoryCheckpoint: String,
      measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
      checks: List<String>,
    ): FeatureTaskRuntimePhaseOutput {
      val buildReceipt = linkedMapOf<String, Any?>(
        "contract_version" to FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION,
        "validation_status" to "passed",
        "checks" to checks,
        "repository_checkpoint" to mapOf("fingerprint" to repositoryCheckpoint),
        "gate_run_count" to measurements.size,
        "gate_runs" to measurements.map { it.toArtifactMap() },
      )
      val payload = JsonSupport.mapToJsonString(
        mapOf(
          "contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
          "status" to BUILD_PHASE_STATUS_COMPLETED,
          "summary" to "Build satisfied by runtime-owned gate execution.",
          "verdict" to FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
          "produced_outputs" to mapOf(
            "build_receipt" to buildReceipt,
          ),
        ),
      )
      return FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        iteration = 1,
        payload = payload,
      )
    }
  }
}

private fun buildFindingsForRepairFromResult(result: ValidationGateRunResult): List<ValidationGateFinding> =
  if (result.outcome == ValidationGateRunOutcome.PASSED) {
    emptyList()
  } else {
    result.findings.ifEmpty {
      listOf(
        ValidationGateFinding(
          module = "<build-gate>",
          ruleOrTestId = "unparseable_gate_failure",
          message = "Build gate reported outcome=${result.outcome.wireValue} exit=${result.exitCode} " +
            "without parseable findings.",
          location = null,
        ),
      )
    }
  }

private fun decodeBuildPersistedFindings(raw: List<Map<String, String?>>): List<ValidationGateFinding> =
  raw.map { map ->
    ValidationGateFinding(
      module = map["module"] ?: "",
      ruleOrTestId = map["rule_or_test_id"] ?: "",
      message = map["message"] ?: "",
      location = map["location"],
    )
  }

private fun terminalCompletedResult(
  repositoryCheckpoint: String,
  measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
): ValidationGateCycleResult = ValidationGateCycleResult.Terminal(
  ValidationGateCycleTerminalOutcome.Completed(
    output = FeatureTaskRuntimeBuildGateCoordinator.runtimeOwnedBuildOutput(
      repositoryCheckpoint = repositoryCheckpoint,
      measurements = measurements,
      checks = emptyList(),
    ),
  ),
)

private fun terminalBlockedResult(
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
