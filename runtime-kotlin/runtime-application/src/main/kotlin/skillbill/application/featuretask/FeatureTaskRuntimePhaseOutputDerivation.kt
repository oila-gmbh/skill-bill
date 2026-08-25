package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.review.ReviewFindingActionability
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditCriterionGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDerivationResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDerivedSettlement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.canonicalAuditIdentifier
import skillbill.workflow.taskruntime.model.canonicalObligationId
import skillbill.workflow.taskruntime.model.displayAuditCriterionRef

internal data class FeatureTaskRuntimeDerivationContext(
  val phaseId: String,
  val outputText: String,
  val outputMap: Map<String, Any?>,
  val acceptanceCriterionRefs: List<String> = emptyList(),
  val carriedFindingIds: Set<String> = emptySet(),
  val reviewFindingIds: Set<String> = emptySet(),
)

@Suppress("TooManyFunctions")
internal object FeatureTaskRuntimePhaseOutputDerivation {
  private const val STATUS_COMPLETED = "completed"
  private const val STATUS_BLOCKED = "blocked"
  private const val STATUS_FAILED = "failed"
  private val AUDIT_CRITERION_REF = Regex("""(AC-\d+)""", RegexOption.IGNORE_CASE)

  @Suppress("ReturnCount")
  fun deriveSettlement(
    context: FeatureTaskRuntimeDerivationContext,
  ): FeatureTaskRuntimeDerivationResult<FeatureTaskRuntimeDerivedSettlement> {
    val structuredStatus = (context.outputMap["status"] as? String)?.trim()?.lowercase()
    val proseStatus = proseStatusToken(context.outputText)
    val status = resolveStructuredOrProse(
      structured = structuredStatus?.takeIf { it in SETTLEMENT_STATUSES },
      prose = proseStatus,
    ) ?: return FeatureTaskRuntimeDerivationResult.Indecisive
    if (status == STATUS_COMPLETED && proseStatus != null && proseStatus != STATUS_COMPLETED) {
      return FeatureTaskRuntimeDerivationResult.Indecisive
    }
    if (status != STATUS_COMPLETED && proseStatus == STATUS_COMPLETED) {
      return FeatureTaskRuntimeDerivationResult.Indecisive
    }
    val disposition = when (status) {
      STATUS_COMPLETED -> null
      else -> deriveFailureDisposition(context) ?: return FeatureTaskRuntimeDerivationResult.Indecisive
    }
    return FeatureTaskRuntimeDerivationResult.Decided(
      FeatureTaskRuntimeDerivedSettlement(status = status, failureDisposition = disposition),
    )
  }

  fun deriveRoutingVerdict(
    context: FeatureTaskRuntimeDerivationContext,
  ): FeatureTaskRuntimeDerivationResult<FeatureTaskRuntimeVerdict> = when (context.phaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> deriveReviewVerdict(context)
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> deriveAuditVerdict(context)
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS -> deriveVerifyFindingsVerdict(context)
    else -> FeatureTaskRuntimeDerivationResult.Decided(
      genericStructuredVerdict(context) ?: FeatureTaskRuntimeVerdict.ADVANCE,
    )
  }

  private fun genericStructuredVerdict(context: FeatureTaskRuntimeDerivationContext): FeatureTaskRuntimeVerdict? =
    (context.outputMap[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] as? String)
      ?.takeIf(String::isNotBlank)
      ?.let(FeatureTaskRuntimeVerdict::fromWire)

  fun verdictFor(context: FeatureTaskRuntimeDerivationContext): FeatureTaskRuntimeVerdict? =
    when (val routing = deriveRoutingVerdict(context)) {
      is FeatureTaskRuntimeDerivationResult.Decided -> routing.value
      FeatureTaskRuntimeDerivationResult.Indecisive -> null
    }

  fun dispositionsFrom(
    context: FeatureTaskRuntimeDerivationContext,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    findingVerificationVerdictFrom(context.outputMap)?.dispositions.orEmpty()

  fun verifiedFindingDispositions(
    context: FeatureTaskRuntimeDerivationContext,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    findingVerificationVerdictFrom(context.outputMap)?.verifiedDispositions.orEmpty()

  fun rejectedFindingDispositions(
    context: FeatureTaskRuntimeDerivationContext,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    findingVerificationVerdictFrom(context.outputMap)?.rejectedDispositions.orEmpty()

  fun unresolvedReviewFindings(context: FeatureTaskRuntimeDerivationContext): List<FeatureTaskRuntimeReviewFinding> =
    reviewVerdictFrom(context)?.unresolvedFindings.orEmpty()

  fun unmetAuditCriteria(context: FeatureTaskRuntimeDerivationContext): List<String> =
    auditVerdictFrom(context)?.blockingCriteria?.map { it.message }.orEmpty()

  fun canonicalAuditCriterionRefs(context: FeatureTaskRuntimeDerivationContext): List<String> =
    auditVerdictFrom(context)
      ?.blockingCriteria
      ?.mapNotNull { AUDIT_CRITERION_REF.find(it.message)?.value?.let(::canonicalAuditIdentifier) }
      ?.distinct()
      .orEmpty()

  fun obligationIdPresentInReturnedText(returnedText: String, obligationId: String): Boolean {
    if (returnedText.isBlank() || obligationId.isBlank()) return false
    val canonical = canonicalObligationId(obligationId)
    val candidates = listOf(
      obligationId.trim(),
      canonical,
      displayAuditCriterionRef(canonical),
    ).distinct()
    return candidates.any { containsWholeObligationId(returnedText, it) }
  }

  fun closedObligationIds(returnedText: String, obligationIds: List<String>): List<String> = obligationIds
    .filter { obligationIdPresentInReturnedText(returnedText, it) }
    .map(::canonicalObligationId)
    .distinct()

  private fun deriveFailureDisposition(
    context: FeatureTaskRuntimeDerivationContext,
  ): FeatureTaskRuntimeFailureDisposition? {
    val structured = (context.outputMap["failure_disposition"] as? String)
      ?.let(FeatureTaskRuntimeFailureDisposition::fromWireValue)
    if (structured != null) return structured
    return FAILURE_DISPOSITION_TOKENS.firstOrNull { token ->
      containsWholeToken(context.outputText, token)
    }?.let(FeatureTaskRuntimeFailureDisposition::fromWireValue)
  }

  @Suppress("ReturnCount")
  private fun deriveReviewVerdict(
    context: FeatureTaskRuntimeDerivationContext,
  ): FeatureTaskRuntimeDerivationResult<FeatureTaskRuntimeVerdict> {
    if (structuredReviewFindingsArrayNonEmpty(context)) {
      reviewVerdictFrom(context)?.let { return FeatureTaskRuntimeDerivationResult.Decided(it.verdict) }
    }
    val structured = structuredReviewVerdict(context.outputMap)
    val prose = proseReviewVerdict(context.outputText)
    if (structured != null) {
      val resolved = transitionalStructuredWins(structured, prose) { it.wireValue } ?: structured
      return FeatureTaskRuntimeDerivationResult.Decided(resolved)
    }
    if (prose != null) return FeatureTaskRuntimeDerivationResult.Decided(prose)
    val fromFindings = reviewVerdictFrom(context)
    if (fromFindings != null && fromFindings.findings.isNotEmpty()) {
      return FeatureTaskRuntimeDerivationResult.Decided(fromFindings.verdict)
    }
    return FeatureTaskRuntimeDerivationResult.Indecisive
  }

  private fun deriveAuditVerdict(
    context: FeatureTaskRuntimeDerivationContext,
  ): FeatureTaskRuntimeDerivationResult<FeatureTaskRuntimeVerdict> {
    if (structuredAuditGapsArrayNonEmpty(context)) {
      auditVerdictFrom(context)?.let { return FeatureTaskRuntimeDerivationResult.Decided(it.verdict) }
    }
    val structured = structuredAuditVerdict(context.outputMap)
    val prose = proseAuditVerdict(context.outputText)
    val resolved = transitionalStructuredWins(structured, prose) { it.wireValue }
      ?: prose
      ?: structured
      ?: return FeatureTaskRuntimeDerivationResult.Indecisive
    return FeatureTaskRuntimeDerivationResult.Decided(resolved)
  }

  private fun structuredReviewFindingsArrayNonEmpty(context: FeatureTaskRuntimeDerivationContext): Boolean {
    val findingsRaw = context.outputMap["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS) as? List<*>
    return findingsRaw?.isNotEmpty() == true
  }

  private fun structuredAuditGapsArrayNonEmpty(context: FeatureTaskRuntimeDerivationContext): Boolean {
    val producedOutputs = context.outputMap["produced_outputs"]?.let(JsonSupport::anyToStringAnyMap)
    val gapsRaw = producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS) as? List<*>
      ?: producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA) as? List<*>
    return gapsRaw?.isNotEmpty() == true
  }

  private fun deriveVerifyFindingsVerdict(
    context: FeatureTaskRuntimeDerivationContext,
  ): FeatureTaskRuntimeDerivationResult<FeatureTaskRuntimeVerdict> {
    val structured = structuredVerifyFindingsVerdict(context.outputMap)
    val derived = findingVerificationVerdictFrom(context.outputMap)?.verdict
    val prose = proseVerifyFindingsVerdict(context.outputText)
    val fromStructuredOrDispositions = structured ?: derived
    val resolved = transitionalStructuredWins(fromStructuredOrDispositions, prose) { it.wireValue }
      ?: prose
      ?: fromStructuredOrDispositions
      ?: return FeatureTaskRuntimeDerivationResult.Indecisive
    return FeatureTaskRuntimeDerivationResult.Decided(resolved)
  }

  private fun structuredReviewVerdict(outputMap: Map<String, Any?>): FeatureTaskRuntimeVerdict? =
    (outputMap[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] as? String)
      ?.takeIf(String::isNotBlank)
      ?.let { value ->
        runCatching { FeatureTaskRuntimeVerdict.rejectRemovedVerdict(value, "phase output verdict") }.getOrNull()
      }
      ?.takeIf { it == FeatureTaskRuntimeVerdict.APPROVED || it == FeatureTaskRuntimeVerdict.CHANGES_REQUESTED }

  private fun structuredAuditVerdict(outputMap: Map<String, Any?>): FeatureTaskRuntimeVerdict? =
    (outputMap[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] as? String)
      ?.takeIf(String::isNotBlank)
      ?.let { value -> runCatching { FeatureTaskRuntimeVerdict.fromWire(value) }.getOrNull() }
      ?.takeIf { it in FeatureTaskRuntimeVerdict.AUDIT_VERDICTS }

  private fun structuredVerifyFindingsVerdict(outputMap: Map<String, Any?>): FeatureTaskRuntimeVerdict? =
    (outputMap[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] as? String)
      ?.takeIf(String::isNotBlank)
      ?.let { value -> runCatching { FeatureTaskRuntimeVerdict.fromWire(value) }.getOrNull() }
      ?.takeIf {
        it == FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED ||
          it == FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED
      }

  private fun reviewVerdictFrom(context: FeatureTaskRuntimeDerivationContext): FeatureTaskRuntimeReviewVerdict? {
    val findingsRaw = context.outputMap["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS) as? List<*>
    if (findingsRaw != null) {
      val findings = findingsRaw.mapNotNull(::actionableReviewFinding)
      return FeatureTaskRuntimeReviewVerdict(findings)
    }
    if (context.reviewFindingIds.isEmpty()) return null
    val remediationRequested = containsWholeToken(context.outputText, "changes_requested") ||
      containsWholeToken(context.outputText, "needs_fix")
    if (!remediationRequested) return FeatureTaskRuntimeReviewVerdict(emptyList())
    return FeatureTaskRuntimeReviewVerdict(
      listOf(
        FeatureTaskRuntimeReviewFinding(
          FeatureTaskRuntimeReviewSeverity.MAJOR,
          "Review prose requested remediation.",
        ),
      ),
    )
  }

  @Suppress("ReturnCount")
  private fun auditVerdictFrom(context: FeatureTaskRuntimeDerivationContext): FeatureTaskRuntimeAuditVerdict? {
    val producedOutputs = context.outputMap["produced_outputs"]?.let(JsonSupport::anyToStringAnyMap)
    val gapsRaw = producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS) as? List<*>
      ?: producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA) as? List<*>
    if (gapsRaw != null) {
      val gaps = gapsRaw.mapNotNull(::auditCriterionGapFromEntry)
      return FeatureTaskRuntimeAuditVerdict(gaps)
    }
    if (containsWholeToken(context.outputText, "gaps_found")) {
      val unmetRefs = unmetAuditCriterionRefsFromProse(context)
      val gaps = unmetRefs.map { ref ->
        FeatureTaskRuntimeAuditCriterionGap(ref, FeatureTaskRuntimeAuditSeverity.MAJOR)
      }
      if (gaps.isNotEmpty()) return FeatureTaskRuntimeAuditVerdict(gaps)
      return FeatureTaskRuntimeAuditVerdict(
        listOf(
          FeatureTaskRuntimeAuditCriterionGap(
            "gaps_found declared in prose without criterion refs",
            FeatureTaskRuntimeAuditSeverity.MAJOR,
          ),
        ),
      )
    }
    if (containsAffirmativeWholeToken(context.outputText, "satisfied")) {
      return FeatureTaskRuntimeAuditVerdict(emptyList())
    }
    return null
  }

  private fun findingVerificationVerdictFrom(
    outputMap: Map<String, Any?>,
  ): FeatureTaskRuntimeFindingVerificationVerdict? {
    val dispositionsRaw = outputMap["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS) as? List<*>
      ?: return null
    val dispositions = runCatching {
      FeatureTaskRuntimeFindingVerificationDisposition.parseList(
        dispositionsRaw,
        "produced_outputs.${FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS}",
      )
    }.getOrNull() ?: return null
    return FeatureTaskRuntimeFindingVerificationVerdict(dispositions)
  }

  private fun proseStatusToken(text: String): String? = SETTLEMENT_STATUSES.firstOrNull { containsWholeToken(text, it) }

  private fun proseReviewVerdict(text: String): FeatureTaskRuntimeVerdict? = when {
    containsWholeToken(text, "changes_requested") || containsWholeToken(text, "needs_fix") ->
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
    containsAffirmativeWholeToken(text, "approved") -> FeatureTaskRuntimeVerdict.APPROVED
    else -> null
  }

  private fun proseAuditVerdict(text: String): FeatureTaskRuntimeVerdict? = when {
    containsWholeToken(text, "gaps_found") -> FeatureTaskRuntimeVerdict.GAPS_FOUND
    containsAffirmativeWholeToken(text, "satisfied") -> FeatureTaskRuntimeVerdict.SATISFIED
    else -> null
  }

  private fun proseVerifyFindingsVerdict(text: String): FeatureTaskRuntimeVerdict? = when {
    containsWholeToken(text, "findings_verified") -> FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED
    containsWholeToken(text, "no_findings_verified") -> FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED
    else -> null
  }

  private fun <T> transitionalStructuredWins(structured: T?, prose: T?, wire: (T) -> String): T? {
    if (structured == null) return prose
    if (prose == null) return structured
    return if (wire(structured) == wire(prose)) structured else structured
  }

  private fun resolveStructuredOrProse(structured: String?, prose: String?): String? = when {
    structured != null && prose != null && structured != prose -> null
    structured != null -> structured
    prose != null -> prose
    else -> null
  }

  private fun containsWholeToken(text: String, token: String): Boolean =
    Regex("""\b${Regex.escape(token)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)

  private fun containsAffirmativeWholeToken(text: String, token: String): Boolean =
    containsWholeToken(text, token) && !containsNegatedWholeToken(text, token)

  private fun containsNegatedWholeToken(text: String, token: String): Boolean =
    Regex("""\b(?:not|un)\s+${Regex.escape(token)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)

  private fun containsWholeObligationId(text: String, obligationId: String): Boolean {
    if (obligationId.isBlank()) return false
    val pattern = Regex(
      """(?<![a-zA-Z0-9-])${Regex.escape(obligationId)}(?![a-zA-Z0-9-])""",
      RegexOption.IGNORE_CASE,
    )
    return pattern.containsMatchIn(text)
  }

  private fun unmetAuditCriterionRefsFromProse(context: FeatureTaskRuntimeDerivationContext): List<String> {
    val proseRefs = AUDIT_CRITERION_REF.findAll(context.outputText)
      .map { it.value.uppercase() }
      .distinct()
      .toList()
    val candidateRefs = if (context.acceptanceCriterionRefs.isNotEmpty()) {
      val allowed = context.acceptanceCriterionRefs.map { it.uppercase() }.toSet()
      proseRefs.filter { it in allowed }
    } else {
      proseRefs
    }
    return candidateRefs.filter { ref -> !containsSatisfiedCriterionClaim(context.outputText, ref) }
  }

  private fun containsSatisfiedCriterionClaim(text: String, criterionRef: String): Boolean {
    val satisfiedNearRef = Regex(
      """${Regex.escape(criterionRef)}[^.\n]{0,80}\b(satisfied|met|implemented)\b""",
      RegexOption.IGNORE_CASE,
    )
    return satisfiedNearRef.findAll(text).any { match ->
      !NEGATED_CRITERION_CLAIM.containsMatchIn(match.value)
    }
  }

  private val NEGATED_CRITERION_CLAIM =
    Regex("""\b(?:not|un)\s+(?:satisfied|met|implemented)\b""", RegexOption.IGNORE_CASE)

  private val SETTLEMENT_STATUSES = setOf(STATUS_COMPLETED, STATUS_BLOCKED, STATUS_FAILED)
  private val FAILURE_DISPOSITION_TOKENS = listOf(
    "retryable",
    "non_retryable_policy_conflict",
    "needs_user_action",
    "process_failure",
    "invalid_output",
  )
}

private fun auditCriterionGapFromEntry(entry: Any?): FeatureTaskRuntimeAuditCriterionGap? {
  if (entry is String) {
    return entry.takeIf(String::isNotBlank)?.let {
      FeatureTaskRuntimeAuditCriterionGap(it, FeatureTaskRuntimeAuditSeverity.MAJOR)
    }
  }
  val map = JsonSupport.anyToStringAnyMap(entry) ?: return null
  val criterion = (map["criterion"] as? String)?.trim()?.takeIf(String::isNotBlank)
  val note = (map["note"] as? String)?.trim()?.takeIf(String::isNotBlank)
  val issue = sequenceOf(map["issue"], map["message"])
    .filterIsInstance<String>()
    .map(String::trim)
    .firstOrNull(String::isNotBlank)
  val message = auditCriterionGapMessage(criterion, note, issue)
  val wired = runCatching { FeatureTaskRuntimeAuditSeverity.fromWire(map["severity"] as? String) }.getOrNull()
  val severity = wired
    ?: if (criterion != null && (note != null || issue != null)) {
      FeatureTaskRuntimeAuditSeverity.MAJOR
    } else {
      null
    }
  return when {
    message == null -> null
    severity != null && !severity.blocksAuditGap -> null
    else -> FeatureTaskRuntimeAuditCriterionGap(message, severity ?: FeatureTaskRuntimeAuditSeverity.MAJOR)
  }
}

private fun auditCriterionGapMessage(criterion: String?, note: String?, issue: String?): String? = when {
  criterion != null && note != null -> "$criterion: $note"
  criterion != null && issue != null -> "$criterion: $issue"
  issue != null -> issue
  criterion != null -> criterion
  else -> null
}

private fun actionableReviewFinding(entry: Any?): FeatureTaskRuntimeReviewFinding? {
  val map = JsonSupport.anyToStringAnyMap(entry) ?: return null
  val severity = (map["severity"] as? String)?.takeIf(String::isNotBlank)
  val message = (map["message"] as? String)?.takeIf(String::isNotBlank)
  if (severity == null || message == null) return null
  val claimVerdict = optionalClaimVerdict(map["claim_verdict"])
  val scopeDisposition = optionalScopeDisposition(map["scope_disposition"])
  if (!ReviewFindingActionability.isActionable(claimVerdict, scopeDisposition)) {
    return null
  }
  return FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.fromWire(severity), message)
}

private fun optionalClaimVerdict(raw: Any?): ReviewClaimVerdict? {
  val value = (raw as? String)?.trim()?.takeIf(String::isNotBlank) ?: return null
  return ReviewClaimVerdict.entries.firstOrNull { it.wireValue == value }
}

private fun optionalScopeDisposition(raw: Any?): ReviewScopeDisposition? {
  val value = (raw as? String)?.trim()?.takeIf(String::isNotBlank) ?: return null
  return ReviewScopeDisposition.entries.firstOrNull { it.wireValue == value }
}
