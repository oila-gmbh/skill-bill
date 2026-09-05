package skillbill.application.subtaskreview

import skillbill.contracts.JsonCodec
import skillbill.goalrunner.model.ReviewFindingOutcome
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.toOutcomeRecord
import skillbill.review.ReviewFindingActionability
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.goal.model.reviewStateError

object GoalSubtaskReviewOutcomeDispositionReduction {
  fun reviewFindingOutcomes(
    supersededFindings: List<UnaddressedFinding>,
    currentFindings: List<UnaddressedFinding>,
    blockerDispositions: List<GoalSubtaskBlockerDisposition>,
  ): List<ReviewFindingOutcomeRecord> {
    val dispositionsByFindingId = blockerDispositions.associateBy(GoalSubtaskBlockerDisposition::findingId)
    val stillReported = currentFindings.mapTo(mutableSetOf(), UnaddressedFinding::findingKey)
    val dispositionVerdictsByKey = supersededFindings.mapNotNull { finding ->
      dispositionsByFindingId[finding.findingId]?.let { finding.findingKey to it.verdict }
    }.toMap()
    fun supersededOutcome(finding: UnaddressedFinding): ReviewFindingOutcome =
      when (dispositionVerdictsByKey[finding.findingKey]) {
        GoalSubtaskBlockerDispositionVerdict.RESOLVED -> ReviewFindingOutcome.ADDRESSED
        GoalSubtaskBlockerDispositionVerdict.UNRESOLVED -> ReviewFindingOutcome.CARRIED
        null -> ReviewFindingOutcome.ADDRESSED
      }

    val supersededOutcomes = supersededFindings
      .filter { finding -> finding.findingKey !in stillReported }
      .map { finding -> finding.toOutcomeRecord(supersededOutcome(finding)) }
    val currentOutcomes = currentFindings
      .map { finding -> finding.toOutcomeRecord(ReviewFindingOutcome.CARRIED) }
    return supersededOutcomes + currentOutcomes
  }

  fun blockerDispositions(
    output: Map<String, Any?>,
    priorBlockerFindingIds: List<String> = emptyList(),
  ): List<GoalSubtaskBlockerDisposition> {
    val dispositions = output["produced_outputs"]
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get("blocker_dispositions")
      ?.let { it as? List<*> }
      ?.mapIndexed(::blockerDisposition)
      .orEmpty()
    if (priorBlockerFindingIds.isEmpty()) return dispositions
    val expected = priorBlockerFindingIds.toSet()
    val emitted = dispositions.map(GoalSubtaskBlockerDisposition::findingId).toSet()
    (emitted - expected).sorted().takeIf { it.isNotEmpty() }?.let { unknown ->
      reviewStateError(
        "produced_outputs.blocker_dispositions",
        "dispositions ${unknown.joinToString()} do not correspond to any Blocker the prior pass " +
          "emitted (expected ${expected.sorted().joinToString()}).",
      )
    }
    return dispositions
  }

  fun refutedBlockerSupersedes(
    priorFindings: List<UnaddressedFinding>,
    currentFindings: List<UnaddressedFinding>,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<GoalSubtaskBlockerDisposition> {
    if (priorFindings.isEmpty()) return emptyList()
    val currentByKey = currentFindings.associateBy(UnaddressedFinding::findingKey)
    val byRef = recordedVerdicts.groupBy(ReviewFindingVerdict::findingRef)
    return priorFindings.mapNotNull { prior ->
      if (prior.severity != "blocker") return@mapNotNull null
      val current = currentByKey[prior.findingKey] ?: return@mapNotNull null
      val findingId = prior.findingId ?: return@mapNotNull null
      val currentId = current.findingId ?: return@mapNotNull null
      val verification = ReviewFindingActionability.verificationVerdict(byRef[currentId].orEmpty())
        ?: ReviewFindingActionability.verificationVerdict(byRef[findingId].orEmpty())
      if (verification?.claimVerdict != ReviewClaimVerdict.REFUTED) return@mapNotNull null
      val evidence = verification.citations.map { citation -> "${citation.path}:${citation.line}" }
      if (evidence.isEmpty()) return@mapNotNull null
      GoalSubtaskBlockerDisposition(
        findingId = findingId,
        verdict = GoalSubtaskBlockerDispositionVerdict.RESOLVED,
        evidence = evidence,
      )
    }
  }
}

private fun blockerDisposition(index: Int, entry: Any?): GoalSubtaskBlockerDisposition {
  val path = "produced_outputs.blocker_dispositions[$index]"
  val disposition = JsonCodec.anyToStringAnyMap(entry)
    ?: reviewStateError(path, "must be an object.")
  val evidence = (disposition["evidence"] as? List<*>)
    ?.mapNotNull { it as? String }
    ?.map(String::trim)
    ?.filter(String::isNotBlank)
    .orEmpty()
  if (evidence.isEmpty()) {
    reviewStateError("$path.evidence", "must cite the specific changed lines that settle the Blocker.")
  }
  return GoalSubtaskBlockerDisposition(
    findingId = (disposition["finding_id"] as? String)?.trim()?.takeIf(String::isNotBlank)
      ?: reviewStateError("$path.finding_id", "must be a non-blank prior Blocker finding id."),
    verdict = GoalSubtaskBlockerDispositionVerdict.fromWire(
      (disposition["verdict"] as? String)?.trim()
        ?: reviewStateError("$path.verdict", "must be resolved or unresolved."),
    ),
    evidence = evidence,
  )
}
