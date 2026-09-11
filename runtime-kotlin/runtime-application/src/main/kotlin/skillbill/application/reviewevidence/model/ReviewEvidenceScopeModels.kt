package skillbill.application.reviewevidence.model

enum class ParallelReviewScope {
  STAGED,
  UNSTAGED,
  UNCOMMITTED,
  BRANCH,
  PR,
}

class DiffResolutionException(message: String) : RuntimeException(message)
