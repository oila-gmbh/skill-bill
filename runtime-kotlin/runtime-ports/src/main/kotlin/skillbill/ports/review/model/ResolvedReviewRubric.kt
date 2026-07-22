package skillbill.ports.review.model

data class ResolvedReviewRubric(
  val rubricId: String,
  val body: String,
  val area: String? = null,
  val specialists: List<ResolvedReviewRubric> = emptyList(),
  val selectedAddOns: List<String> = emptyList(),
) {
  init {
    require(rubricId.isNotBlank())
    require(body.isNotBlank())
    require(area == null || area.isNotBlank())
    require(specialists.map { it.rubricId }.distinct().size == specialists.size)
    require(selectedAddOns.distinct().size == selectedAddOns.size)
  }
}
