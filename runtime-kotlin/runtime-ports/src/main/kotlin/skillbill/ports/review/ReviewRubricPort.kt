package skillbill.ports.review

import skillbill.ports.review.model.ReviewSpecialistRubric

fun interface ReviewRubricPort {
  fun loadSpecialistRubrics(stackSlug: String): List<ReviewSpecialistRubric>
}
