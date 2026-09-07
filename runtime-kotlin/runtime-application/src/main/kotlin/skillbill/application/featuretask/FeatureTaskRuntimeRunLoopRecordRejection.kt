package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeProducerOutputRead
import skillbill.application.featuretask.model.ProducerOutputQueryArgs
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput

object FeatureTaskRuntimeRunLoopRecordRejection {
  internal fun blockUnattributableRecordRejection(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: UnattributableRecordRejectionArgs,
  ): PhaseOutcome {
    val run = args.context.run
    val state = args.context.state
    val iteration = args.context.iteration
    val observability = args.context.observability
    val rejection = args.rejection
    val producer = args.producer
    val detail = payloadFreeRejectionReason(
      "reconciliation-${rejection.rejectionClass}",
      rejectionPath(rejection.rejectionDetail),
    )
    recordUnattributableRejectedEvidence(runLoop, run, runLoop.state, rejection)
    return FeatureTaskRuntimeRunLoopPhaseAttempts.blockAndPersistInPhase(
      runLoop,
      phaseBlockArgs(
        run = run,
        attemptCount = iteration,
        reason = unattributableRecordRejectionReason(run.phaseId, rejection, producer, detail),
        observability = runLoop.observability,
        payload = BlockAndPersistPayload(childNeverLaunched = true),
      ).withDisposition(FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION),
    )
  }

  fun rejectionPath(detail: String): String {
    Regex("""(?:instance location|path|pointer)\s*[:=]\s*['"]?(/[^\s,'"]*)""", RegexOption.IGNORE_CASE)
      .find(detail)
      ?.groupValues
      ?.get(1)
      ?.let { return it }
    val dollarPath = Regex("""\$(?:\.[A-Za-z0-9_-]+|\[[0-9]+])+""").find(detail)?.value ?: return "/"
    return dollarPath.removePrefix("$")
      .replace(Regex("""\.([A-Za-z0-9_-]+)"""), "/${'$'}1")
      .replace(Regex("""\[([0-9]+)]"""), "/${'$'}1")
  }

  fun payloadFreeRejectionReason(rule: String, path: String): String =
    "Rejected output violated '$rule' at '$path'. Inspect the private diagnostic for the exact response."

  fun retryRejectionReason(payloadFreeReason: String, validationReason: String?): String =
    if (validationReason.isNullOrBlank()) {
      payloadFreeReason
    } else {
      "$payloadFreeReason Violated constraint: ${boundedSchemaGateDetail(validationReason)}"
    }

  fun payloadFreeSemanticGateConstraint(
    runLoop: FeatureTaskRuntimeRunLoop,
    rule: String,
    detail: String,
    rejectedOutput: Map<String, Any?>,
  ): String? = when (rule) {
    "mutating-reconciliation" -> detail.takeUnless { it.isBlank() }
    "repair-receipt" -> detail.takeUnless { it.isBlank() }
    "producer-projection",
    "consumer-projection",
    "output-verification",
    -> scrubResponseDerivedGateDetail(runLoop, detail, rejectedOutput)
    else -> scrubBoundedReferenceGateConstraint(detail)
  }

  fun scrubBoundedReferenceGateConstraint(detail: String): String? {
    if (detail.isBlank()) return null
    val namesArtifactRef = detail.contains("artifact_ref")
    val namesCheckRef = detail.contains("check_ref")
    if (!namesArtifactRef && !namesCheckRef) return null
    val cap = BOUNDED_REF_LENGTH_CAP_PATTERN.find(detail)?.groupValues?.get(1)?.replace(",", "")
    return when {
      namesArtifactRef && cap != null -> "artifact_ref allows at most $cap characters."
      namesCheckRef && cap != null -> "check_ref allows at most $cap characters."
      namesArtifactRef ->
        "artifact_ref must be a bounded path or symbol reference such as " +
          "src/main/Example.kt or src/main/Example.kt:Example."
      else ->
        "check_ref must match AC-###, F-###, or a name ending in Test or Check, optionally followed " +
          "by :symbol; examples: AC-005, FeatureTaskRuntimeAuditEntryGateTest, or codeCheck:detekt."
    }
  }

  fun scrubResponseDerivedGateDetail(
    runLoop: FeatureTaskRuntimeRunLoop,
    detail: String,
    rejectedOutput: Map<String, Any?>,
  ): String? {
    if (detail.isBlank()) return null
    var text = detail.take(SCHEMA_GATE_DETAIL_MAX_CHARS)
    text = scrubOffVocabularyVerdictQuote(text)
    text = OFFENDING_VALUE_APPENDIX_PATTERN.replace(text, "")
    text = EXPECTED_ACTUAL_LIST_PATTERN.replace(text, "")
    responseStringValues(runLoop, rejectedOutput)
      .filter { value ->
        value.length >= MIN_RESPONSE_STRING_VALUE_LENGTH &&
          SCHEMA_DETAIL_TYPE_WORDS.none { typeWord -> typeWord.equals(value, ignoreCase = true) } &&
          text.contains(value)
      }
      .sortedByDescending(String::length)
      .forEach { value -> text = text.replace(value, "[response value omitted]") }
    return text.trim().takeUnless { it.isBlank() }
  }

  fun responseStringValues(runLoop: FeatureTaskRuntimeRunLoop, value: Any?): List<String> {
    val values = mutableListOf<String>()
    collectResponseStringValues(runLoop, value, values)
    return values.distinct()
  }

  fun collectResponseStringValues(runLoop: FeatureTaskRuntimeRunLoop, value: Any?, values: MutableList<String>) {
    when (value) {
      is String -> values += value
      is Map<*, *> -> value.values.forEach { nested -> collectResponseStringValues(runLoop, nested, values) }
      is Iterable<*> -> value.forEach { nested -> collectResponseStringValues(runLoop, nested, values) }
    }
  }

  internal fun attemptOnce(runLoop: FeatureTaskRuntimeRunLoop, args: RecordRejectionAttemptArgs): AttemptResult {
    val run = args.context.run
    val state = args.context.state
    val iteration = args.context.iteration
    val observability = args.context.observability
    val priorCorrection = args.priorCorrection
    val phaseTokenAccumulator = args.phaseTokenAccumulator
    FeatureTaskRuntimeRunLoopOutputPersistence.persistPhase(
      runLoop,
      PersistPhaseArgs(
        write = PhaseStateWriteArgs(
          run = run,
          iteration = iteration,
          status = STATUS_RUNNING,
          finished = false,
          outputArtifact = runLoop.state.outputFor(run.phaseId)?.payload,
        ),
        launched = FeatureTaskRuntimeRunLoopOutputPersistence.launchedModelDirective(run),
      ),
    )
    val launch = FeatureTaskRuntimeRunLoopLaunch.launchAndCapture(
      runLoop,
      run,
      runLoop.state,
      priorCorrection,
      runLoop.phaseTokenAccumulator,
    )
    return settleRecordRejectionLaunchOutcome(runLoop, args, launch)
  }

  internal fun recordUnattributableRejectedEvidence(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    rejection: RecordRejection,
  ) {
    val detail = payloadFreeRejectionReason(
      "reconciliation-${rejection.rejectionClass}",
      rejectionPath(rejection.rejectionDetail),
    )
    val rejectedOutput = run.declaration.projectionDeclarations
      .asSequence()
      .map { it.producerIteration.phaseId }
      .distinct()
      .mapNotNull { phaseId -> state.outputFor(phaseId) }
      .firstOrNull()
    val evidence = rejectedOutput?.let { output ->
      unattributableProducerEvidence(runLoop, state, output)
    }
    evidence?.let { writeUnattributableRejectedEvidence(runLoop, run, rejection, detail, it) }
  }

  private fun unattributableProducerEvidence(
    runLoop: FeatureTaskRuntimeRunLoop,
    state: FeatureTaskRuntimeRunState,
    output: FeatureTaskRuntimePhaseOutput,
  ): ProducerOutputEvidence? {
    val agentId = state.recordFor(output.phaseId)?.resolvedAgentId ?: return null
    return when (
      val read = runLoop.recorder.producerOutput(
        ProducerOutputQueryArgs(
          workflowId = runLoop.request.workflowId,
          phaseId = output.phaseId,
          attempt = output.iteration.coerceAtLeast(1),
          agentId = agentId,
          dbOverride = runLoop.request.dbPathOverride,
          generation = state.evidenceGeneration(output.phaseId),
        ),
      )
    ) {
      is FeatureTaskRuntimeProducerOutputRead.Found -> read.evidence
      is FeatureTaskRuntimeProducerOutputRead.Absent,
      is FeatureTaskRuntimeProducerOutputRead.Unreadable,
      -> null
    }
  }

  private fun writeUnattributableRejectedEvidence(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    rejection: RecordRejection,
    detail: String,
    evidence: ProducerOutputEvidence,
  ) {
    val payload = evidence.payload ?: byteArrayOf()
    FeatureTaskRuntimeRunLoopAttemptSettlement.recordRejectedOutput(
      runLoop,
      RecordRejectedOutputArgs(
        run = run,
        iteration = evidence.attempt,
        rule = "reconciliation-${rejection.rejectionClass}",
        reason = retryRejectionReason(detail, rejection.rejectionDetail),
        captured = CapturedPhaseOutput(
          text = payload.decodeToString(),
          bytes = payload,
          truncated = evidence.payload == null,
          byteSize = evidence.byteSize,
          sha256 = evidence.sha256,
        ),
        targeting = FeatureTaskRuntimeRunLoopAttemptSettlement.rejectedOutputTargeting(
          defaultRejectedOutputTargetingArgs(
            run,
            RejectedOutputTargetingOverrides(
              phaseId = evidence.phaseId,
              agentId = evidence.agentId,
              model = evidence.model,
              path = rejectionPath(rejection.rejectionDetail),
              repairTurn = evidence.repairTurn,
            ),
          ),
        ),
      ),
    )
  }

  internal fun unattributableRecordRejectionReason(
    consumerPhaseId: String,
    rejection: RecordRejection,
    producer: String?,
    detail: String,
  ): String = if (producer == null) {
    "Feature-task-runtime phase '$consumerPhaseId' rejected an upstream durable record " +
      "(${rejection.rejectionClass}) it cannot attribute to a producing phase, so no regeneration edge " +
      "applies; the run blocks durably. Recover the record out of band by deleting or migrating the " +
      "offending row. Detail: $detail"
  } else {
    "Feature-task-runtime phase '$consumerPhaseId' rejected the durable record produced by '$producer', but " +
      "'$producer' is absent from this run's resolved pipeline (a goal-continuation truncation dropped it), " +
      "so it cannot be regenerated in-band; the run blocks durably. Recover the record out of band by " +
      "deleting or migrating the offending row. Detail: $detail"
  }

  internal fun settleRecordRejectionLaunchOutcome(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: RecordRejectionAttemptArgs,
    launch: LaunchResult,
  ): AttemptResult {
    val run = args.context.run
    val state = args.context.state
    val iteration = args.context.iteration
    val observability = args.context.observability
    launch.providerLimitReason?.let { reason ->
      return AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseAttempts.pauseAndPersistInPhase(
          runLoop,
          PauseAndPersistInPhaseArgs(run, iteration, reason, runLoop.observability, launch.fileManifest),
        ),
      )
    }
    launch.infraFailureReason?.let { reason ->
      FeatureTaskRuntimeRunLoopAttemptSettlement.persistChildProcessFailureOutput(
        runLoop,
        run,
        iteration,
        reason,
        launch.infraFailureChildOutput,
      )
      return AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseAttempts.blockAndPersistInPhase(
          runLoop,
          phaseBlockArgs(
            run,
            iteration,
            reason,
            runLoop.observability,
            payload = BlockAndPersistPayload(
              childNeverLaunched = launch.childNeverLaunched,
              fileManifest = launch.fileManifest,
            ),
          ).withDisposition(launch.failureDisposition),
        ),
      )
    }
    launch.recordRejection?.let { rejection ->
      return AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseAttempts.settleRecordRejection(
          runLoop,
          SettleRecordRejectionArgs(run, runLoop.state, iteration, runLoop.observability, rejection),
        ),
      )
    }
    val fileManifest = requireNotNull(launch.fileManifest)
    return FeatureTaskRuntimeRunLoopAttemptSettlement.gateOutput(
      runLoop,
      GateOutputArgs(
        run = run,
        iteration = iteration,
        captured = requireNotNull(launch.capturedPhaseOutput),
        observability = runLoop.observability,
        fileManifest = fileManifest,
      ),
    )
  }

  fun scrubOffVocabularyVerdictQuote(text: String): String {
    val start = text.indexOf(OFF_VOCABULARY_VERDICT_OPEN, ignoreCase = true)
    if (start < 0) return text
    val afterOpenQuote = start + OFF_VOCABULARY_VERDICT_OPEN.length
    val closeAt = text.lastIndexOf(OFF_VOCABULARY_VERDICT_CLOSE_BOUNDARY)
    return if (closeAt >= afterOpenQuote) {
      text.substring(0, start) + "off-vocabulary verdict" + text.substring(closeAt + 1)
    } else {
      text.substring(0, start) + "off-vocabulary verdict"
    }
  }
}

const val OFF_VOCABULARY_VERDICT_OPEN = "off-vocabulary verdict '"

const val OFF_VOCABULARY_VERDICT_CLOSE_BOUNDARY = "' and no"

val OFFENDING_VALUE_APPENDIX_PATTERN =
  Regex("""(?:\s*[—-]\s*)?offending value:.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

val EXPECTED_ACTUAL_LIST_PATTERN =
  Regex("""\bexpected=\[[^\]]*]\s*actual=\[[^\]]*]\.?""", RegexOption.IGNORE_CASE)

val BOUNDED_REF_LENGTH_CAP_PATTERN =
  Regex("""(?:allows|must be) at most ([0-9][0-9,]*) characters""", RegexOption.IGNORE_CASE)

val SCHEMA_DETAIL_TYPE_WORDS = setOf(
  "array",
  "boolean",
  "integer",
  "null",
  "number",
  "object",
  "string",
)

const val MIN_RESPONSE_STRING_VALUE_LENGTH = 4

val INVENTORY_EXTENDING_PHASES: Set<String> = setOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
)
