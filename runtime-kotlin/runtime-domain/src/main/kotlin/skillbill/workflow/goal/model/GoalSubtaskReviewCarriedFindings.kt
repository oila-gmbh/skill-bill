package skillbill.workflow.goal.model

/**
 * The carried set with every finding the verification stage refuted removed. Verification exists to
 * drop findings that do not survive scrutiny, so a refuted finding is not carried into the round at
 * all: the receipt owes it no entry and coverage never sends the round back for it. Leaving it in
 * made verification decorative — the stage refuted the claim and the next gate then demanded
 * paperwork for it anyway, blocking a round whose real findings were already closed.
 *
 * Matches on the normalized ref, exactly as coverage does. Apply it after [withStableFindingRefs]:
 * a finding with no ref can never be named by a refutation, so it stays carried.
 */
fun withoutRefutedFindings(
  findings: List<GoalSubtaskReviewCompactFinding>,
  refutedFindingIds: Set<String>,
): List<GoalSubtaskReviewCompactFinding> {
  if (refutedFindingIds.isEmpty()) return findings
  val refuted = refutedFindingIds.mapTo(linkedSetOf(), ::normalizeIdentityPart)
  return findings.filterNot { finding -> finding.findingId?.let(::normalizeIdentityPart) in refuted }
}
