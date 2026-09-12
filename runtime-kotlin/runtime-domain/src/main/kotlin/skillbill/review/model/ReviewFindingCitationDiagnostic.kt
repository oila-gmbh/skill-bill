package skillbill.review.model

object ReviewFindingCitationDiagnosticKeys {
  const val CITATION_INDEX = "citation_index"
  const val RAW_LINE = "raw_line"
}

data class ReviewFindingCitationDiagnostic(
  val citationIndex: Int,
  val path: String?,
  val rawLine: String?,
  val reason: String,
) {
  fun withFindingRef(findingRef: String?): ReviewFindingCitationDiagnosticWithFinding =
    ReviewFindingCitationDiagnosticWithFinding(findingRef, this)
}

data class ReviewFindingCitationDiagnosticWithFinding(
  val findingRef: String?,
  val diagnostic: ReviewFindingCitationDiagnostic,
)

data class ReviewFindingCitationsDecode(
  val citations: List<ReviewFindingCitation>,
  val diagnostics: List<ReviewFindingCitationDiagnostic>,
)
