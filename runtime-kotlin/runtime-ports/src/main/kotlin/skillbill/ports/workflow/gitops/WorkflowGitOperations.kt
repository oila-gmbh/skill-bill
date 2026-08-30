package skillbill.ports.workflow.gitops

internal const val HASH_RADIX_HEX: Int = 16
internal const val NOOP_REVIEW_BASE_SHA_LENGTH: Int = 40

interface WorkflowGitOperations :
  WorkflowGitBranchOperations,
  WorkflowGitRemoteOperations,
  WorkflowGitCommitHistoryOperations,
  WorkflowGitWorktreeOperations
