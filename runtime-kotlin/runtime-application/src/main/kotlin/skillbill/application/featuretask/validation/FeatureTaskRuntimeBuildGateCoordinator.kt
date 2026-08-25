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
import skillbill.ports.validation.model.unparseableGateFailureMessage
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRepairWindowPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

private fun runtimeOwnedBuildProse(): String =
  "Build satisfied by runtime-owned gate execution. Verdict: ${FeatureTaskRuntimeVerdict.SATISFIED.wireValue}."

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
      is ValidationGateResolution.Absent -> ValidationGateCycleResult.AbsentFallback
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
      return terminalCompletedResult()
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
    var currentFindings = openFindings
    while (true) {
      if (currentFindings.isEmpty()) {
        return terminalCompletedResult()
      }
      val projection = ValidationFindingSetProjection(findings = currentFindings)
      if (repairsUsed >= MAX_REPAIR_TURNS) {
        persistProgress(
          state = state,
          repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
          remainingFindings = projection,
          completeFindings = currentFindings,
          repairsUsed = repairsUsed,
        )
        return terminalBlockedResult(
          "Build gate still reports ${currentFindings.size} finding(s) after $MAX_REPAIR_TURNS repair " +
            "turns; remaining findings are recorded for the operator.",
          remainingFindings = projection,
          measurements = measurements,
        )
      }
      persistProgress(
        state = state,
        repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
        remainingFindings = null,
        completeFindings = currentFindings,
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
      val verify = runGate(state.cycle, declaration, ValidationGateCyclePhase.POST_REPAIR_VERIFY)
      val verifyFindings = buildFindingsForRepairFromResult(verify)
      recordGateProgress(
        state = state,
        result = verify,
        findings = verifyFindings,
        repairWindowPhase = repairWindowPhaseFor(verifyFindings),
        repairsUsed = repairsUsed,
      )
      if (verifyFindings.isEmpty()) {
        return terminalCompletedResult()
      }
      currentFindings = verifyFindings
    }
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
    const val MAX_REPAIR_TURNS: Int = 3

    private fun operatorResumeRepairTurns(repairsUsed: Int): Int =
      if (repairsUsed >= MAX_REPAIR_TURNS) 0 else repairsUsed

    fun runtimeOwnedBuildOutput(): FeatureTaskRuntimePhaseOutput = FeatureTaskRuntimePhaseOutput(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
      iteration = 1,
      payload = runtimeOwnedBuildProse(),
    )
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
          message = unparseableGateFailureMessage(
            gateLabel = "Build gate",
            outcome = result.outcome.wireValue,
            exitCode = result.exitCode,
            stdout = result.stdout,
          ),
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

private fun terminalCompletedResult(): ValidationGateCycleResult = ValidationGateCycleResult.Terminal(
  ValidationGateCycleTerminalOutcome.Completed(
    output = FeatureTaskRuntimeBuildGateCoordinator.runtimeOwnedBuildOutput(),
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
