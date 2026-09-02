package skillbill.application.reviewevidence.model

enum class ParallelReviewScope {
  STAGED,
  UNSTAGED,
  BRANCH,
  PR,
  WORKTREE_FROM_BASE,
}

class DiffResolutionException(message: String) : RuntimeException(message)
