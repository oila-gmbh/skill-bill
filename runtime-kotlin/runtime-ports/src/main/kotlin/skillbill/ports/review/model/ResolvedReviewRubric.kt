package skillbill.ports.review.model

data class ResolvedReviewRubric(val rubricId: String, val body: String) {
  init {
    require(rubricId.isNotBlank())
    require(body.isNotBlank())
  }
}
