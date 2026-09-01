package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

@Inject
class FeatureTaskRuntimeRunLoopRecordRejection {
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
    return runLoop.collaborators.phaseAttemptsContinued2.blockAndPersistInPhase(
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
  fun retryRejectionReason(payloadFreeReason: String, validationReason: String?): String =
    if (validationReason.isNullOrBlank()) {
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

  /**
   * Extracts a payload-free bounded-reference constraint from semantic-gate detail. Returns null when
   * the detail does not name artifact_ref or check_ref, so audit identifiers and expected=/actual=
   * receipt lists never reach the retry reason by themselves.
   */
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

  /**
   * Strips known response-value dumps from semantic-gate detail before it can enter a retry prompt
   * outside the authorized repair section. Schema-structure fragments (property names, found/expected
   * types, maxLength caps) remain so length and shape corrections still fire.
   *
   * Caps at [SCHEMA_GATE_DETAIL_MAX_CHARS] before pattern work so an oversized wire verdict cannot
   * amplify retry CPU; when the cap cuts inside a quoted verdict, [scrubOffVocabularyVerdictQuote]
   * strips the open marker through end rather than leaving a partial response-derived quote.
   */
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
}
