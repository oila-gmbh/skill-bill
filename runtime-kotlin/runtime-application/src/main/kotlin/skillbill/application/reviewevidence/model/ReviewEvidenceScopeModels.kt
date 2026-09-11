package skillbill.application.reviewevidence.model

enum class ParallelReviewScope {
  STAGED,
  UNSTAGED,
  UNCOMMITTED,
  BRANCH,
  PR,
  WORKTREE_FROM_BASE,
}

class DiffResolutionException(message: String) : RuntimeException(message)
