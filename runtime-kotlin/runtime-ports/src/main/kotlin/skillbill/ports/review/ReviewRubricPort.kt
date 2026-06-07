package skillbill.ports.review

fun interface ReviewRubricPort {
  fun loadSpecialistRubrics(stackSlug: String): List<ReviewSpecialistRubric>
}

data class ReviewSpecialistRubric(val skillName: String, val content: String)
