package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

internal fun FeatureTaskRuntimeRunLoop.blockUnattributableRecordRejection(
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
  recordUnattributableRejectedEvidence(run, state, rejection)
  return blockAndPersistInPhase(
    phaseBlockArgs(
      run = run,
      attemptCount = iteration,
      reason = unattributableRecordRejectionReason(run.phaseId, rejection, producer, detail),
      observability = observability,
      payload = BlockAndPersistPayload(childNeverLaunched = true),
    ).withDisposition(FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION),
  )
}

internal fun FeatureTaskRuntimeRunLoop.rejectionPath(detail: String): String {
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

internal fun FeatureTaskRuntimeRunLoop.payloadFreeRejectionReason(rule: String, path: String): String =
  "Rejected output violated '$rule' at '$path'. Inspect the private diagnostic for the exact response."

/**
 * The retry-facing counterpart of [payloadFreeRejectionReason]. A producer cannot repair an output from a
 * rule name and a path alone, so the validator's constraint text — the violated rule, the expected shape
 * and the offending field, all authored from the schema and never from the response — is appended for the
 * next prompt and for the private diagnostic row. The payload-free sentence stays the prefix so both
 * readers still learn where the raw response is kept.
 *
 * A null or blank [validationReason] means the producing seam had no value-free restatement to offer, so
 * the payload-free sentence stands alone; the value-bearing variant is never substituted in its place.
 */
internal fun FeatureTaskRuntimeRunLoop.retryRejectionReason(
  payloadFreeReason: String,
  validationReason: String?,
): String = if (validationReason.isNullOrBlank()) {
  payloadFreeReason
} else {
  "$payloadFreeReason Violated constraint: ${boundedSchemaGateDetail(validationReason)}"
}

/**
 * Semantic-gate detail that is safe to place outside the authorized repair section.
 *
 * Mutating-reconciliation is a fixed template. Producer/consumer projection and output-verification
 * may carry schema-structure text the producer needs, but only after response-derived dumps
 * (quoted wire verdicts, offending-value appendices, expected=/actual= receipt lists) are scrubbed.
 * Audit ledger/repair gates stay null except for scrubbed bounded artifact_ref/check_ref
 * constraints — those must reach the retry reason so compound or oversized refs get actionable
 * guidance instead of a generic audit sentence alone.
 */
internal fun FeatureTaskRuntimeRunLoop.payloadFreeSemanticGateConstraint(
  rule: String,
  detail: String,
  rejectedOutput: Map<String, Any?>,
): String? = when (rule) {
  "mutating-reconciliation" -> detail.takeUnless { it.isBlank() }
  "repair-receipt" -> detail.takeUnless { it.isBlank() }
  "producer-projection",
  "consumer-projection",
  "output-verification",
  -> scrubResponseDerivedGateDetail(detail, rejectedOutput)
  else -> scrubBoundedReferenceGateConstraint(detail)
}

/**
 * Extracts a payload-free bounded-reference constraint from semantic-gate detail. Returns null when
 * the detail does not name artifact_ref or check_ref, so audit identifiers and expected=/actual=
 * receipt lists never reach the retry reason by themselves.
 */
internal fun FeatureTaskRuntimeRunLoop.scrubBoundedReferenceGateConstraint(detail: String): String? {
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

/**
 * Strips known response-value dumps from semantic-gate detail before it can enter a retry prompt
 * outside the authorized repair section. Schema-structure fragments (property names, found/expected
 * types, maxLength caps) remain so length and shape corrections still fire.
 *
 * Caps at [SCHEMA_GATE_DETAIL_MAX_CHARS] before pattern work so an oversized wire verdict cannot
 * amplify retry CPU; when the cap cuts inside a quoted verdict, [scrubOffVocabularyVerdictQuote]
 * strips the open marker through end rather than leaving a partial response-derived quote.
 */
internal fun FeatureTaskRuntimeRunLoop.scrubResponseDerivedGateDetail(
  detail: String,
  rejectedOutput: Map<String, Any?>,
): String? {
  if (detail.isBlank()) return null
  var text = detail.take(SCHEMA_GATE_DETAIL_MAX_CHARS)
  text = scrubOffVocabularyVerdictQuote(text)
  text = OFFENDING_VALUE_APPENDIX_PATTERN.replace(text, "")
  text = EXPECTED_ACTUAL_LIST_PATTERN.replace(text, "")
  responseStringValues(rejectedOutput)
    .filter { value ->
      value.length >= MIN_RESPONSE_STRING_VALUE_LENGTH &&
        SCHEMA_DETAIL_TYPE_WORDS.none { typeWord -> typeWord.equals(value, ignoreCase = true) } &&
        text.contains(value)
    }
    .sortedByDescending(String::length)
    .forEach { value -> text = text.replace(value, "[response value omitted]") }
  return text.trim().takeUnless { it.isBlank() }
}

internal fun FeatureTaskRuntimeRunLoop.responseStringValues(value: Any?): List<String> {
  val values = mutableListOf<String>()
  collectResponseStringValues(value, values)
  return values.distinct()
}

internal fun FeatureTaskRuntimeRunLoop.collectResponseStringValues(value: Any?, values: MutableList<String>) {
  when (value) {
    is String -> values += value
    is Map<*, *> -> value.values.forEach { nested -> collectResponseStringValues(nested, values) }
    is Iterable<*> -> value.forEach { nested -> collectResponseStringValues(nested, values) }
  }
}

internal fun FeatureTaskRuntimeRunLoop.attemptOnce(args: RecordRejectionAttemptArgs): AttemptResult {
  val run = args.context.run
  val state = args.context.state
  val iteration = args.context.iteration
  val observability = args.context.observability
  val priorCorrection = args.priorCorrection
  val phaseTokenAccumulator = args.phaseTokenAccumulator
  // The running write is what the IDE reads as current_model while the child is in flight, so it
  // stamps the directive the launch below is rendered from. The settling exits then clear it only
  // where the launch proved no child ever ran, via LaunchResult.childNeverLaunched.
  persistPhase(
    PersistPhaseArgs(
      write = PhaseStateWriteArgs(
        run = run,
        iteration = iteration,
        status = STATUS_RUNNING,
        finished = false,
        outputArtifact = state.outputFor(run.phaseId)?.payload,
      ),
      launched = launchedModelDirective(run),
    ),
  )
  val launch = launchAndCapture(run, state, priorCorrection, phaseTokenAccumulator)
  launch.providerLimitReason?.let { reason ->
    return AttemptResult.settled(pauseAndPersistInPhase(run, iteration, reason, observability, launch.fileManifest))
  }
  launch.infraFailureReason?.let { reason ->
    // Persisted before the block so the artifact exists even if the block write then degrades:
    // the whole point is to leave something readable behind a failure that reaches no output gate.
    persistChildProcessFailureOutput(run, iteration, reason, launch.infraFailureChildOutput)
    return AttemptResult.settled(
      blockAndPersistInPhase(
        phaseBlockArgs(
          run,
          iteration,
          reason,
          observability,
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
      settleRecordRejection(run, state, iteration, observability, rejection),
    )
  }
  val fileManifest = requireNotNull(launch.fileManifest)
  return gateOutput(
    GateOutputArgs(
      run = run,
      iteration = iteration,
      captured = requireNotNull(launch.capturedPhaseOutput),
      observability = observability,
      fileManifest = fileManifest,
    ),
  )
}
