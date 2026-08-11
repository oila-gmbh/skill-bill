package skillbill.ports.review

import skillbill.review.plan.model.ReviewRoutingChangedFile

fun interface DeclaredReviewSpecialistsPort {
  /**
   * Specialists a review over [changedFiles] will actually route to, resolved against the installed
   * pack selection. Scoping by the review's own diff evidence is required, not an optimization:
   * asking for every installed pack's specialists fails preflight on a pack the review never
   * launches. No repository path is taken — packs never come from the project under review.
   */
  fun routedSpecialists(changedFiles: List<ReviewRoutingChangedFile>): List<String>

  companion object {
    val NONE = DeclaredReviewSpecialistsPort { _ -> emptyList() }
  }
}
