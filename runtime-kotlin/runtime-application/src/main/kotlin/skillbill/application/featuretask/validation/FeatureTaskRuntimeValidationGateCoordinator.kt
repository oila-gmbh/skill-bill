package skillbill.application.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.emitFeatureTaskRuntimeEventSafely
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCyclePhase
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateProgressStore
import skillbill.application.featuretask.validation.model.ValidationGateProgressWrite
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.application.featuretask.validation.model.requiresUnparseableGateTriage
import skillbill.config.model.applyValidationGateGradleWrapper
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
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
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRepairWindowPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

private const val VALIDATE_PHASE_STATUS_COMPLETED = "completed"

private data class ValidationGateCycleState(
  val cycle: ValidationGateCycleRequest,
  val measurements: MutableList<FeatureTaskRuntimeValidationGateRunRecord>,
  val onGateRunCount: (Int) -> Unit,
)

class FeatureTaskRuntimeValidationGateProgressStore private constructor(
  private val recorder: FeatureTaskRuntimePhaseRecorder?,
  private val delegate: ValidationGateProgressStore?,
) : ValidationGateProgressStore {
  @Inject
  constructor(recorder: FeatureTaskRuntimePhaseRecorder) : this(recorder, null)

  internal constructor(delegate: ValidationGateProgressStore) : this(null, delegate)

  override fun persist(workflowId: String, progress: FeatureTaskRuntimeValidationGateProgress, dbOverride: String?) {
    when {
      delegate != null -> delegate.persist(workflowId, progress, dbOverride)
      recorder != null -> recorder.persistValidationGateProgress(workflowId, progress, dbOverride)
      else -> error("FeatureTaskRuntimeValidationGateProgressStore has no backing store.")
    }
  }

  override fun load(workflowId: String, dbOverride: String?): FeatureTaskRuntimeValidationGateProgress? = when {
    delegate != null -> delegate.load(workflowId, dbOverride)
    recorder != null -> recorder.loadValidationGateProgress(workflowId, dbOverride)
    else -> error("FeatureTaskRuntimeValidationGateProgressStore has no backing store.")
  }
}

@Inject
class FeatureTaskRuntimeValidationGateCoordinator(
  private val resolver: ValidationGateResolver,
  private val runner: ValidationGateRunner,
  private val progressStore: FeatureTaskRuntimeValidationGateProgressStore,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun execute(cycle: ValidationGateCycleRequest, onGateRunCount: (Int) -> Unit = {}): ValidationGateCycleResult {
    return when (val resolution = resolver.resolve(cycle.changedPaths)) {
      is ValidationGateResolution.Absent -> ValidationGateCycleResult.AbsentFallback
      is ValidationGateResolution.Incompatible -> terminalBlockedResult(resolution.reason)
      is ValidationGateResolution.Declared -> checkAndRepair(cycle, resolution.declaration, onGateRunCount)
    }
  }

  private fun checkAndRepair(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    onGateRunCount: (Int) -> Unit,
  ): ValidationGateCycleResult {
    val loaded = progressStore.load(cycle.request.workflowId, cycle.request.dbPathOverride)
    val measurements = loaded?.gateRuns?.toMutableList() ?: mutableListOf()
    val state = ValidationGateCycleState(cycle, measurements, onGateRunCount)

    if (loaded?.repairWindowPhase == FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN) {
      return repairLoop(
        state = state,
        declaration = declaration,
        openFindings = decodePersistedFindings(loaded.completeFindings),
        initialRepairsUsed = operatorResumeRepairTurns(loaded.repairsUsed),
        triagePlan = loaded.capturedTriagePlan,
      )
    }

    val discovery = runGate(cycle, declaration, ValidationGateCyclePhase.INITIAL_DISCOVERY)
    val discoveryFindings = findingsForRepairFromResult(discovery)
    recordGateProgress(
      state = state,
      result = discovery,
      write = ValidationGateProgressWrite(
        repairWindowPhase = repairWindowPhaseFor(discoveryFindings),
        remainingFindings = null,
        completeFindings = discoveryFindings,
        repairsUsed = 0,
        capturedTriagePlan = null,
      ),
    )
    if (discoveryFindings.isEmpty()) {
      return terminalCompletedResult(cycle.repositoryCheckpoint, measurements)
    }
    val triagePlan = runTriageIfNeeded(cycle, discoveryFindings, persistedPlan = null)
    if (triagePlan != null) {
      persistProgress(
        state = state,
        write = ValidationGateProgressWrite(
          repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
          remainingFindings = null,
          completeFindings = discoveryFindings,
          repairsUsed = 0,
          capturedTriagePlan = triagePlan,
        ),
      )
    }
    return repairLoop(
      state = state,
      declaration = declaration,
      openFindings = discoveryFindings,
      initialRepairsUsed = 0,
      triagePlan = triagePlan,
    )
  }

  private fun runTriageIfNeeded(
    cycle: ValidationGateCycleRequest,
    findings: List<ValidationGateFinding>,
    persistedPlan: String?,
  ): String? {
    if (!persistedPlan.isNullOrBlank()) return persistedPlan
    if (!requiresUnparseableGateTriage(findings)) return null
    return when (val triage = cycle.agentTriageLauncher.launch(ValidationFindingSetProjection(findings))) {
      is ValidationGateTriageResult.Captured -> triage.validationRepairPlan.takeIf { it.isNotBlank() }
      ValidationGateTriageResult.Empty -> null
    }
  }

  private fun repairLoop(
    state: ValidationGateCycleState,
    declaration: ValidationGateDeclaration,
    openFindings: List<ValidationGateFinding>,
    initialRepairsUsed: Int,
    triagePlan: String?,
  ): ValidationGateCycleResult {
    val cycle = state.cycle
    val measurements = state.measurements
    var repairsUsed = initialRepairsUsed
    var currentFindings = openFindings
    while (true) {
      if (currentFindings.isEmpty()) {
        return terminalCompletedResult(cycle.repositoryCheckpoint, measurements)
      }
      val projection = ValidationFindingSetProjection(findings = currentFindings)
      if (repairsUsed >= MAX_REPAIR_TURNS) {
        persistProgress(
          state = state,
          write = ValidationGateProgressWrite.findingsOpen(
            completeFindings = currentFindings,
            repairsUsed = repairsUsed,
            capturedTriagePlan = triagePlan,
            remainingFindings = projection,
          ),
        )
        return terminalBlockedResult(
          "Validation gate still reports ${currentFindings.size} finding(s) after $MAX_REPAIR_TURNS repair " +
            "turns; remaining findings are recorded for the operator.",
          remainingFindings = projection,
          measurements = measurements,
        )
      }
      persistProgress(
        state = state,
        write = ValidationGateProgressWrite.findingsOpen(
          completeFindings = currentFindings,
          repairsUsed = repairsUsed,
          capturedTriagePlan = triagePlan,
        ),
      )
      when (val repair = cycle.agentRepairLauncher.launch(projection, repairsUsed + 1, triagePlan)) {
        is ValidationGateAgentRepairResult.Blocked -> return terminalBlockedResult(
          repair.reason,
          remainingFindings = projection,
          measurements = measurements,
          failureDisposition = repair.failureDisposition,
        )
        is ValidationGateAgentRepairResult.Completed -> repairsUsed++
      }
      currentFindings = verifyAfterRepair(state, declaration, repairsUsed, triagePlan)
      if (currentFindings.isEmpty()) {
        return terminalCompletedResult(cycle.repositoryCheckpoint, measurements)
      }
    }
  }

  private fun verifyAfterRepair(
    state: ValidationGateCycleState,
    declaration: ValidationGateDeclaration,
    repairsUsed: Int,
    triagePlan: String?,
  ): List<ValidationGateFinding> {
    val verify = runGate(state.cycle, declaration, ValidationGateCyclePhase.POST_REPAIR_VERIFY)
    val verifyFindings = findingsForRepairFromResult(verify)
    recordGateProgress(
      state = state,
      result = verify,
      write = ValidationGateProgressWrite(
        repairWindowPhase = repairWindowPhaseFor(verifyFindings),
        remainingFindings = null,
        completeFindings = verifyFindings,
        repairsUsed = repairsUsed,
        capturedTriagePlan = triagePlan,
      ),
    )
    return verifyFindings
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
    val packArgv = validationGateArgv(declaration, cyclePhase)
    val cacheMode = when (cyclePhase) {
      ValidationGateCyclePhase.INITIAL_DISCOVERY -> ValidationGateCacheMode.CACHE_ELIGIBLE
      ValidationGateCyclePhase.POST_REPAIR_VERIFY -> ValidationGateCacheMode.FORCED_FULL
    }
    val terminalVerifying = cyclePhase == ValidationGateCyclePhase.POST_REPAIR_VERIFY
    val findingParseMode = ValidationGateFindingParseMode.COLLECT_ALL
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
        terminalVerifying = terminalVerifying,
        findingParseMode = findingParseMode,
      ),
    )
  }

  private fun recordGateProgress(
    state: ValidationGateCycleState,
    result: ValidationGateRunResult,
    write: ValidationGateProgressWrite,
  ) {
    state.measurements += FeatureTaskRuntimeValidationGateRunRecord(
      durationMs = result.durationMs,
      outcome = result.outcome.wireValue,
      cacheMode = result.cacheMode.wireValue,
      executedWorkUnits = result.executedWorkUnits,
    )
    persistProgress(state = state, write = write)
  }

  private fun persistProgress(state: ValidationGateCycleState, write: ValidationGateProgressWrite) {
    val progress = FeatureTaskRuntimeValidationGateProgress(
      gateRunCount = state.measurements.size,
      gateRuns = state.measurements.toList(),
      remainingFindings = write.remainingFindings?.toHandoffMaps().orEmpty(),
      completeFindings = ValidationFindingSetProjection(findings = write.completeFindings).toHandoffMaps(),
      repairWindowPhase = write.repairWindowPhase,
      repairsUsed = write.repairsUsed,
      capturedTriagePlan = write.capturedTriagePlan,
    )
    progressStore.persist(state.cycle.request.workflowId, progress, state.cycle.request.dbPathOverride)
    emitFeatureTaskRuntimeEventSafely(
      diagnostics = diagnostics,
      seam = "ValidationGateProgress event-sink emission",
    ) {
      state.cycle.request.eventSink.emit(
        FeatureTaskRuntimeRunEvent.ValidationGateProgress(
          workflowId = state.cycle.request.workflowId,
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
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

    fun unparseableGateFailureFinding(result: ValidationGateRunResult): ValidationGateFinding = ValidationGateFinding(
      module = "<validation-gate>",
      ruleOrTestId = "unparseable_gate_failure",
      message = unparseableGateFailureMessage(
        gateLabel = "Validation gate",
        outcome = result.outcome.wireValue,
        exitCode = result.exitCode,
        stdout = result.stdout,
      ),
      location = null,
    )

    fun runtimeOwnedValidationOutput(
      repositoryCheckpoint: String,
      measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
      checks: List<String>,
    ): FeatureTaskRuntimePhaseOutput {
      val validationResult = linkedMapOf<String, Any?>(
        "validation_status" to "passed",
        "checks" to checks,
        "repository_checkpoint" to mapOf("fingerprint" to repositoryCheckpoint),
        "gate_run_count" to measurements.size,
        "gate_runs" to measurements.map { it.toArtifactMap() },
      )
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

private fun findingsForRepairFromResult(result: ValidationGateRunResult): List<ValidationGateFinding> =
  if (result.outcome == ValidationGateRunOutcome.PASSED) {
    emptyList()
  } else {
    result.findings.ifEmpty {
      listOf(FeatureTaskRuntimeValidationGateCoordinator.unparseableGateFailureFinding(result))
    }
  }

private fun decodePersistedFindings(raw: List<Map<String, String?>>): List<ValidationGateFinding> = raw.map { map ->
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
    output = FeatureTaskRuntimeValidationGateCoordinator.runtimeOwnedValidationOutput(
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
  failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
): ValidationGateCycleResult = ValidationGateCycleResult.Terminal(
  ValidationGateCycleTerminalOutcome.Blocked(
    reason = reason,
    remainingFindings = remainingFindings,
    measurements = measurements,
    failureDisposition = failureDisposition,
  ),
)
