package skillbill.goalrunner.subtaskreview.model

import skillbill.review.model.ReviewFindingCitationDiagnosticWithFinding

data class StructuredGoalReviewFindingsParse(
  val findings: List<StructuredGoalReviewFinding>,
  val citationDiagnostics: List<ReviewFindingCitationDiagnosticWithFinding>,
)
