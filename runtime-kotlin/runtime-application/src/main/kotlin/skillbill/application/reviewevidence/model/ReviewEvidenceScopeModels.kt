package skillbill.application.reviewevidence.model

enum class ParallelReviewScope {
  STAGED,
  UNSTAGED,
  BRANCH,
  PR,
}

class DiffResolutionException(message: String) : RuntimeException(message)
