package skillbill.application.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.emitFeatureTaskRuntimeEventSafely
import skillbill.application.featuretask.validation.model.SuppressionDelta
import skillbill.application.featuretask.validation.model.SuppressionGateDecision
import skillbill.application.featuretask.validation.model.SuppressionJustification
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateProgressStore
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.config.model.applyValidationGateGradleWrapper
import skillbill.contracts.JsonSupport
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
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.FullValidateRepairPlanItem
import skillbill.workflow.taskruntime.model.FullValidateSubstantiationReceipt
import java.nio.file.Path

private const val VALIDATE_PHASE_STATUS_COMPLETED = "completed"

private data class ValidationGateLaunchSpec(
  val repoRoot: Path,
  val declaration: ValidationGateDeclaration,
  val validationDepth: ValidationDepth,
  val cacheMode: ValidationGateCacheMode,
  val terminalVerifying: Boolean,
  val findingParseMode: ValidationGateFindingParseMode,
)

private data class ValidationGateCycleState(
  val cycle: ValidationGateCycleRequest,
  val measurements: MutableList<FeatureTaskRuntimeValidationGateRunRecord>,
  val harvestedJustifications: MutableList<SuppressionJustification>,
  val onGateRunCount: (Int) -> Unit,
  val session: FullValidateRepairSession,
)

private class FullValidateRepairSession(
  var discoveryIdentities: List<String> = emptyList(),
  var plan: List<FullValidateRepairPlanItem> = emptyList(),
  var receipts: List<FullValidateSubstantiationReceipt> = emptyList(),
) {
  fun resetFor(findings: List<ValidationGateFinding>) {
    discoveryIdentities = findings.map { it.identity() }
    plan = FullValidateRepairCoverage.oneToOnePlan(findings)
    receipts = emptyList()
  }
}

private sealed interface PagedRepairOutcome
private data class PagedRepairCompleted(val repairsUsed: Int) : PagedRepairOutcome
private data class PagedRepairBlocked(val result: ValidationGateCycleResult) : PagedRepairOutcome

private sealed interface CoveredRepairOutcome
private data class CoveredRepairAccepted(
  val output: FeatureTaskRuntimePhaseOutput,
  val ordinal: Int,
) : CoveredRepairOutcome
private data class CoveredRepairBlocked(val reason: String) : CoveredRepairOutcome

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
  private val repoLocalConfig: RepoLocalConfigPort,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
  fun execute(cycle: ValidationGateCycleRequest, onGateRunCount: (Int) -> Unit = {}): ValidationGateCycleResult {
    return when (val resolution = resolver.resolve(cycle.changedPaths)) {
      is ValidationGateResolution.Absent -> ValidationGateCycleResult.AbsentFallback
      is ValidationGateResolution.Incompatible -> terminalBlockedResult(resolution.reason)
      is ValidationGateResolution.Declared ->
        if (cycle.validationDepth == ValidationDepth.BUILD_ONLY) {
          executeBuildOnly(cycle, resolution.declaration, onGateRunCount)
        } else {
          executeFull(cycle, resolution.declaration, onGateRunCount)
        }
    }
  }

  @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
  private fun executeFull(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    onGateRunCount: (Int) -> Unit,
  ): ValidationGateCycleResult {
    val measurements = mutableListOf<FeatureTaskRuntimeValidationGateRunRecord>()
    var repairsUsed = 0
    var confirmationRetriesUsed = 0
    val harvestedJustifications = mutableListOf<SuppressionJustification>()
    val session = FullValidateRepairSession()
    val state = ValidationGateCycleState(
      cycle = cycle,
      measurements = measurements,
      harvestedJustifications = harvestedJustifications,
      onGateRunCount = onGateRunCount,
      session = session,
    )
    val discovery = runGate(
      ValidationGateLaunchSpec(
        repoRoot = cycle.repoRoot,
        declaration = declaration,
        validationDepth = cycle.validationDepth,
        cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
        terminalVerifying = false,
        findingParseMode = ValidationGateFindingParseMode.COLLECT_ALL,
      ),
    )
    var completeFindings = findingsForRepairFromResult(discovery)
    session.resetFor(completeFindings)
    recordGateProgress(state, discovery, completeFindings)
    while (true) {
      if (completeFindings.isNotEmpty()) {
        when (
          val repair = repairPagedFindings(state, completeFindings, repairsUsed, confirmationRetriesUsed)
        ) {
          is PagedRepairBlocked -> return repair.result
          is PagedRepairCompleted -> repairsUsed = repair.repairsUsed
        }
      }
      val confirmationCoverage = FullValidateRepairCoverage.evaluate(
        requiredIdentities = session.discoveryIdentities,
        plan = session.plan,
        receipts = session.receipts,
      )
      if (!confirmationCoverage.accepted) {
        val remaining = ValidationFindingSetProjector.page(completeFindings, offset = 0)
        persistProgress(state, remaining, completeFindings, confirmationRetriesUsed = confirmationRetriesUsed)
        return terminalBlockedResult(
          confirmationCoverage.reason,
          remainingFindings = remaining,
          measurements = measurements,
        )
      }
      val confirmation = runGate(
        ValidationGateLaunchSpec(
          repoRoot = cycle.repoRoot,
          declaration = declaration,
          validationDepth = cycle.validationDepth,
          cacheMode = ValidationGateCacheMode.FORCED_FULL,
          terminalVerifying = true,
          findingParseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      recordGateProgress(state, confirmation, completeFindings, confirmationRetriesUsed)
      when (confirmation.outcome) {
        ValidationGateRunOutcome.REJECTED_ZERO_WORK ->
          return terminalBlockedResult(
            "Validation gate terminal run reported zero executed work; a satisfied outcome requires " +
              "pack-attested execution, not a cache-served no-op.",
            measurements = measurements,
          )
        ValidationGateRunOutcome.PASSED,
        ValidationGateRunOutcome.FAILED,
        -> {
          val nextFindings = nextRepairFindingsFromConfirmation(confirmation)
          if (confirmation.outcome == ValidationGateRunOutcome.PASSED && nextFindings.isEmpty()) {
            return settleSuppressionGate(
              cycle = cycle,
              declaration = declaration,
              measurements = measurements,
              justifications = harvestedJustifications,
              repairIterationHint = repairsUsed + 1,
            )
          }
          if (confirmationRetriesUsed >= FULL_CONFIRMATION_RETRY_CAP) {
            val remaining = ValidationFindingSetProjector.page(nextFindings, offset = 0)
            persistProgress(state, remaining, nextFindings, confirmationRetriesUsed = confirmationRetriesUsed)
            return terminalBlockedResult(
              "Validation gate confirmation retry cap ($FULL_CONFIRMATION_RETRY_CAP) exhausted " +
                "with remaining findings.",
              remainingFindings = remaining.copy(
                findings = nextFindings,
                scheduledRemainderCount = 0,
              ),
              measurements = measurements,
            )
          }
          confirmationRetriesUsed++
          completeFindings = nextFindings
          session.resetFor(completeFindings)
        }
      }
    }
  }

  @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
  private fun executeBuildOnly(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    onGateRunCount: (Int) -> Unit,
  ): ValidationGateCycleResult {
    val measurements = mutableListOf<FeatureTaskRuntimeValidationGateRunRecord>()
    var repairsUsed = 0
    val harvestedJustifications = mutableListOf<SuppressionJustification>()
    val state = ValidationGateCycleState(
      cycle = cycle,
      measurements = measurements,
      harvestedJustifications = harvestedJustifications,
      onGateRunCount = onGateRunCount,
      session = FullValidateRepairSession(),
    )
    val agentRepairLauncher = cycle.agentRepairLauncher
    while (true) {
      val intermediate = runGate(
        ValidationGateLaunchSpec(
          repoRoot = cycle.repoRoot,
          declaration = declaration,
          validationDepth = ValidationDepth.BUILD_ONLY,
          cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
          terminalVerifying = false,
          findingParseMode = ValidationGateFindingParseMode.ARTIFACTS_ONLY,
        ),
      )
      recordGateProgress(state, intermediate)
      if (intermediate.outcome == ValidationGateRunOutcome.PASSED) {
        val terminal = runGate(
          ValidationGateLaunchSpec(
            repoRoot = cycle.repoRoot,
            declaration = declaration,
            validationDepth = ValidationDepth.BUILD_ONLY,
            cacheMode = ValidationGateCacheMode.FORCED_FULL,
            terminalVerifying = true,
            findingParseMode = ValidationGateFindingParseMode.ARTIFACTS_ONLY,
          ),
        )
        recordGateProgress(state, terminal)
        return when {
          terminal.outcome == ValidationGateRunOutcome.REJECTED_ZERO_WORK ->
            terminalBlockedResult(
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
              repairIterationHint = repairsUsed + 1,
            )
          else -> terminalBlockedResult(
            "Validation gate terminal run failed after a clean intermediate result.",
            measurements = measurements,
          )
        }
      }
      val findingsForRepair = findingsForRepairFromResult(intermediate)
      val projection = ValidationFindingSetProjector.project(findingsForRepair)
      if (projection.hasUnreportedRemainder) {
        persistProgress(state, projection)
        return terminalBlockedResult(
          "Validation gate findings exceed the handoff budget (${
            projection.droppedCount
          } unreported); repair cannot succeed while findings remain unreported.",
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
          harvestedJustifications += extractSuppressionJustifications(repair.output)
        }
      }
      repairsUsed++
    }
  }

  private fun repairPagedFindings(
    state: ValidationGateCycleState,
    completeFindings: List<ValidationGateFinding>,
    repairsUsed: Int,
    confirmationRetriesUsed: Int,
  ): PagedRepairOutcome {
    var offset = 0
    var used = repairsUsed
    var nextOrdinal = repairsUsed + 1
    while (offset < completeFindings.size) {
      val page = ValidationFindingSetProjector.page(completeFindings, offset)
      persistProgress(
        state,
        page,
        completeFindings,
        findingsPageOffset = offset,
        confirmationRetriesUsed = confirmationRetriesUsed,
      )
      when (val covered = launchCoveredRepair(state.cycle, page, nextOrdinal, state.session)) {
        is CoveredRepairBlocked ->
          return PagedRepairBlocked(
            ValidationGateCycleResult.Terminal(
              ValidationGateCycleTerminalOutcome.Blocked(
                reason = covered.reason,
                remainingFindings = page,
                measurements = state.measurements,
              ),
            ),
          )
        is CoveredRepairAccepted -> {
          state.harvestedJustifications += extractSuppressionJustifications(covered.output)
          used = covered.ordinal
          nextOrdinal = covered.ordinal + 1
        }
      }
      offset += page.findings.size
      if (page.findings.isEmpty()) {
        break
      }
    }
    return PagedRepairCompleted(used)
  }

  private fun launchCoveredRepair(
    cycle: ValidationGateCycleRequest,
    page: ValidationFindingSetProjection,
    startingOrdinal: Int,
    session: FullValidateRepairSession,
  ): CoveredRepairOutcome {
    val launchedIdentities = page.findings.map { it.identity() }.toSet()
    var ordinal = startingOrdinal
    var coverageRelaunches = 0
    var projected = page
    while (true) {
      when (val repair = cycle.agentRepairLauncher.launch(projected, ordinal)) {
        is ValidationGateAgentRepairResult.Blocked ->
          return CoveredRepairBlocked(repair.reason)
        is ValidationGateAgentRepairResult.Completed -> {
          val parsed = FullValidateRepairArtifacts.parse(repair.output)
          val plan = FullValidateRepairCoverage.replacePlanIfUnionUnchanged(
            current = session.plan,
            launchedIdentities = launchedIdentities,
            supplied = parsed.plan,
          )
          val receipts = mergeRepairReceipts(session.receipts, parsed.receipts)
          val coverage = FullValidateRepairCoverage.evaluate(launchedIdentities, plan, receipts)
          if (coverage.accepted) {
            session.plan = plan
            session.receipts = receipts
            return CoveredRepairAccepted(repair.output, ordinal)
          }
          if (coverageRelaunches >= FullValidateRepairCoverage.RELAUNCH_CAP) {
            return CoveredRepairBlocked(coverage.reason)
          }
          coverageRelaunches++
          ordinal++
          projected = page.copy(coverageRejectionReason = coverage.reason)
        }
      }
    }
  }

  private fun settleSuppressionGate(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
    justifications: List<SuppressionJustification>,
    repairIterationHint: Int,
  ): ValidationGateCycleResult {
    val measured = suppressionDeltaService.measure(
      repoRoot = cycle.repoRoot,
      baseRef = cycle.baseRef,
      changedPaths = cycle.changedPaths,
      declaration = declaration,
    ).getOrElse { error ->
      return terminalBlockedResult(
        "Validation suppression gate could not measure the suppression delta: ${error.message.orEmpty()}",
        measurements = measurements,
      )
    }
    val initial = SuppressionJustificationGate.evaluate(measured, justifications)
    val decision = when {
      initial is SuppressionGateDecision.Block && justifications.isEmpty() && measured.totalIntroduced > 0 -> {
        val projection = suppressionJustificationProjection(measured)
        when (val harvest = cycle.agentRepairLauncher.launch(projection, repairIterationHint)) {
          is ValidationGateAgentRepairResult.Blocked -> return ValidationGateCycleResult.Terminal(
            ValidationGateCycleTerminalOutcome.Blocked(
              reason = harvest.reason,
              remainingFindings = projection,
              measurements = measurements,
            ),
          )
          is ValidationGateAgentRepairResult.Completed ->
            SuppressionJustificationGate.evaluate(measured, extractSuppressionJustifications(harvest.output))
        }
      }
      else -> initial
    }
    return when (decision) {
      is SuppressionGateDecision.Block -> terminalBlockedResult(decision.reason, measurements = measurements)
      is SuppressionGateDecision.Allow -> terminalCompletedResult(
        repositoryCheckpoint = cycle.repositoryCheckpoint,
        measurements = measurements,
        checks = emptyList(),
        justifications = decision.justifications,
      )
    }
  }

  private fun suppressionJustificationProjection(delta: SuppressionDelta): ValidationFindingSetProjection {
    val findings = delta.introductions.map { intro ->
      ValidationGateFinding(
        module = SUPPRESSION_JUSTIFICATION_MODULE,
        ruleOrTestId = SUPPRESSION_JUSTIFICATION_RULE_ID,
        message = "Runtime measured ${intro.introducedCount} introduced '${intro.marker}' " +
          "occurrence(s) on ${intro.path}. Emit suppression_justifications accounting for every " +
          "introduced marker (path, silenced_rule_or_check, short rationale). Do not invoke the gate.",
        location = intro.path,
      )
    }
    return ValidationFindingSetProjection(findings = findings, droppedCount = 0)
  }

  private fun runGate(spec: ValidationGateLaunchSpec): ValidationGateRunResult {
    val packArgv = validationGateArgv(spec.declaration, spec.validationDepth, spec.cacheMode)
    val gradleWrapper = repoLocalConfig
      .readRepoLocalConfig(ReadRepoLocalConfigRequest(spec.repoRoot))
      .config
      .validationGate
      .gradleWrapper
    return runner.run(
      ValidationGateRunRequest(
        repoRoot = spec.repoRoot,
        argv = applyValidationGateGradleWrapper(packArgv, gradleWrapper),
        cacheMode = spec.cacheMode,
        declaration = spec.declaration,
        terminalVerifying = spec.terminalVerifying,
        findingParseMode = spec.findingParseMode,
      ),
    )
  }

  private fun recordGateProgress(
    state: ValidationGateCycleState,
    result: ValidationGateRunResult,
    completeFindings: List<ValidationGateFinding> = emptyList(),
    confirmationRetriesUsed: Int = 0,
  ) {
    state.measurements += FeatureTaskRuntimeValidationGateRunRecord(
      durationMs = result.durationMs,
      outcome = result.outcome.wireValue,
      cacheMode = result.cacheMode.wireValue,
      executedWorkUnits = result.executedWorkUnits,
    )
    persistProgress(
      state,
      remainingFindings = null,
      completeFindings,
      confirmationRetriesUsed = confirmationRetriesUsed,
    )
  }

  private fun persistProgress(
    state: ValidationGateCycleState,
    remainingFindings: ValidationFindingSetProjection?,
    completeFindings: List<ValidationGateFinding> = emptyList(),
    findingsPageOffset: Int = 0,
    confirmationRetriesUsed: Int = 0,
  ) {
    val progress = FeatureTaskRuntimeValidationGateProgress(
      gateRunCount = state.measurements.size,
      gateRuns = state.measurements.toList(),
      remainingFindings = remainingFindings?.toHandoffMaps().orEmpty(),
      remainingFindingsDroppedCount = remainingFindings?.droppedCount ?: 0,
      completeFindings = ValidationFindingSetProjection(
        findings = completeFindings,
        droppedCount = 0,
      ).toHandoffMaps(),
      findingsPageOffset = findingsPageOffset,
      confirmationRetriesUsed = confirmationRetriesUsed,
      discoveryIdentities = state.session.discoveryIdentities,
      validationRepairPlan = state.session.plan,
      substantiationReceipts = state.session.receipts,
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
    const val SUPPRESSION_JUSTIFICATION_MODULE: String = "<suppression-gate>"
    const val SUPPRESSION_JUSTIFICATION_RULE_ID: String = "suppression_justification_required"
    const val FULL_CONFIRMATION_RETRY_CAP: Int = 2

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

private fun mergeRepairReceipts(
  current: List<FullValidateSubstantiationReceipt>,
  incoming: List<FullValidateSubstantiationReceipt>,
): List<FullValidateSubstantiationReceipt> {
  val byIdentity = LinkedHashMap<String, FullValidateSubstantiationReceipt>()
  current.forEach { byIdentity[it.identity] = it }
  incoming.forEach { byIdentity[it.identity] = it }
  return byIdentity.values.toList()
}

private fun nextRepairFindingsFromConfirmation(confirmation: ValidationGateRunResult): List<ValidationGateFinding> {
  if (confirmation.findings.isNotEmpty()) {
    return confirmation.findings
  }
  if (confirmation.outcome == ValidationGateRunOutcome.PASSED) {
    return emptyList()
  }
  return findingsForRepairFromResult(confirmation)
}

private fun findingsForRepairFromResult(result: ValidationGateRunResult): List<ValidationGateFinding> =
  result.findings.ifEmpty {
    if (result.outcome == ValidationGateRunOutcome.PASSED) {
      emptyList()
    } else {
      listOf(FeatureTaskRuntimeValidationGateCoordinator.unparseableGateFailureFinding(result))
    }
  }

private fun extractSuppressionJustifications(output: FeatureTaskRuntimePhaseOutput): List<SuppressionJustification> {
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

private fun terminalCompletedResult(
  repositoryCheckpoint: String,
  measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
  checks: List<String>,
  justifications: List<SuppressionJustification> = emptyList(),
): ValidationGateCycleResult = ValidationGateCycleResult.Terminal(
  ValidationGateCycleTerminalOutcome.Completed(
    output = FeatureTaskRuntimeValidationGateCoordinator.runtimeOwnedValidationOutput(
      repositoryCheckpoint = repositoryCheckpoint,
      measurements = measurements,
      checks = checks,
      justifications = justifications,
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
