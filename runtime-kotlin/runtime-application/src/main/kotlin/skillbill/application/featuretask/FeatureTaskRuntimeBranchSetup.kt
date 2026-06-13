package skillbill.application.featuretask

import skillbill.application.decomposition.defaultFeatureBranch
import skillbill.application.decomposition.issueAndFeature
import java.nio.file.Path

/**
 * Pure branch-resolution helper for the runtime run-setup step. It is effect-free: it does no
 * git/process/file IO and only computes the target feature branch and the setup decision from
 * inert inputs (issue key, spec reference, and the branch the run currently sits on). The caller
 * (the runner) performs the git side effects through the injected `WorkflowGitOperations` port.
 *
 * The target branch follows the `bill-feature-task` convention `feat/{ISSUE_KEY}-{feature-name}`,
 * deriving BOTH the issue key and the feature name from the spec's parent directory through the
 * same `issueAndFeature` seam as `DecompositionManifestWriterPlan.defaultFeatureBranch`, so the
 * runtime can never compute a branch that diverges from the canonical one. The protected-branch
 * guard mirrors `GoalRunner` (`PROTECTED_GOAL_BRANCHES` + `protectedBranchName`) so a run never
 * proceeds on `main`/`master`/`trunk`.
 */
internal object FeatureTaskRuntimeBranchSetup {
  private val PROTECTED_BRANCHES: Set<String> = setOf("main", "master", "trunk")
  private const val DEFAULT_BASE_BRANCH: String = "main"

  /**
   * Derives the target feature branch `feat/{ISSUE_KEY}-{feature-name}` from the spec reference's
   * parent directory. Both segments come from the single `issueAndFeature` seam; the request-supplied
   * [issueKey] is validated against the parsed issue key rather than mixed into the branch name, so a
   * caller-supplied key can never produce a branch that diverges from `defaultFeatureBranch`.
   */
  fun targetBranch(issueKey: String, specReference: String): FeatureTaskRuntimeTargetBranch {
    val parentName = Path.of(specReference).parent?.fileName?.toString().orEmpty()
    if (parentName.isBlank()) {
      return FeatureTaskRuntimeTargetBranch.invalid(
        "FeatureTaskRuntimeBranchSetup cannot derive a feature branch: spec reference " +
          "'$specReference' has no parent directory to parse 'feat/{ISSUE_KEY}-{feature-name}' from.",
      )
    }
    val (parsedIssueKey, _) = issueAndFeature(parentName)
    return if (issueKey != parsedIssueKey) {
      FeatureTaskRuntimeTargetBranch.invalid(
        "FeatureTaskRuntimeBranchSetup issue-key mismatch: request issue key '$issueKey' does not " +
          "match the issue key '$parsedIssueKey' parsed from spec parent directory '$parentName'; " +
          "refusing to create a divergent feature branch.",
      )
    } else {
      FeatureTaskRuntimeTargetBranch.resolved(defaultFeatureBranch(Path.of(specReference).parent.resolve("spec.md")))
    }
  }

  /**
   * Decides how to establish the feature branch given the branch the run currently sits on.
   * - On a default/protected branch (or an unknown/blank current branch): [Create] the target
   *   branch and switch to it from [DEFAULT_BASE_BRANCH].
   * - On any other (non-protected) branch: [Reuse] it as-is — this is the seam goal-driven runs
   *   rely on to hand the runtime a pre-created branch (subtask 7).
   * - When the target branch cannot be derived (malformed spec reference or issue-key mismatch),
   *   returns an invalid decision so the runner can block loudly.
   */
  fun decide(issueKey: String, specReference: String, currentBranch: String): FeatureTaskRuntimeBranchDecision {
    val normalizedCurrent = currentBranch.trim()
    val mustCreate = normalizedCurrent.isBlank() || protectedBranchName(normalizedCurrent) != null
    if (!mustCreate) {
      return FeatureTaskRuntimeBranchDecision.resolved(branch = normalizedCurrent, baseBranch = null, create = false)
    }
    val target = targetBranch(issueKey, specReference)
    return target.resolvedBranch?.let { resolvedBranch ->
      FeatureTaskRuntimeBranchDecision.resolved(
        branch = resolvedBranch,
        baseBranch = DEFAULT_BASE_BRANCH,
        create = true,
      )
    } ?: FeatureTaskRuntimeBranchDecision.invalid(requireNotNull(target.invalidReason))
  }

  fun goalContinuationDecision(goalBranch: String): FeatureTaskRuntimeBranchDecision {
    val normalized = goalBranch.trim()
    val protected = protectedBranchName(normalized)
    return when {
      normalized.isBlank() -> FeatureTaskRuntimeBranchDecision.invalid(
        "Goal-continuation branch is blank; refusing to run file-mutating phases.",
      )
      protected != null -> FeatureTaskRuntimeBranchDecision.invalid(
        "Goal-continuation branch '$protected' is protected; refusing to run file-mutating phases.",
      )
      else -> FeatureTaskRuntimeBranchDecision.resolved(branch = normalized, baseBranch = null, create = false)
    }
  }

  /** The protected branch name when [branch] is one of the protected defaults, else null. */
  fun protectedBranchName(branch: String?): String? = branch
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.takeIf { normalized -> normalized.lowercase() in PROTECTED_BRANCHES }
}

/** The derived target feature branch, or a structured reason it could not be derived. */
internal sealed interface FeatureTaskRuntimeTargetBranch {
  val resolvedBranch: String?
  val invalidReason: String?

  companion object {
    fun resolved(branch: String): FeatureTaskRuntimeTargetBranch = FeatureTaskRuntimeTargetBranchResolved(branch)

    fun invalid(reason: String): FeatureTaskRuntimeTargetBranch = FeatureTaskRuntimeTargetBranchInvalid(reason)
  }
}

internal data class FeatureTaskRuntimeTargetBranchResolved(val branch: String) : FeatureTaskRuntimeTargetBranch {
  override val resolvedBranch: String get() = branch
  override val invalidReason: String? get() = null
}

internal data class FeatureTaskRuntimeTargetBranchInvalid(val reason: String) : FeatureTaskRuntimeTargetBranch {
  override val resolvedBranch: String? get() = null
  override val invalidReason: String get() = reason
}

/**
 * The runtime's branch-setup decision. A resolved decision carries the target branch (when [create]
 * is true the runtime must create+switch to [branch] from [baseBranch]; otherwise it reuses the
 * already-checked-out [branch] and [baseBranch] is null). An invalid decision carries a structured
 * [invalidReason] the runner must turn into a loud block.
 */
internal sealed interface FeatureTaskRuntimeBranchDecision {
  val invalidReason: String?

  companion object {
    fun resolved(branch: String, baseBranch: String?, create: Boolean): FeatureTaskRuntimeBranchDecision =
      FeatureTaskRuntimeBranchDecisionResolved(branch, baseBranch, create)

    fun invalid(reason: String): FeatureTaskRuntimeBranchDecision = FeatureTaskRuntimeBranchDecisionInvalid(reason)
  }
}

internal data class FeatureTaskRuntimeBranchDecisionResolved(
  val branch: String,
  val baseBranch: String?,
  val create: Boolean,
) : FeatureTaskRuntimeBranchDecision {
  override val invalidReason: String? get() = null
}

internal data class FeatureTaskRuntimeBranchDecisionInvalid(val reason: String) : FeatureTaskRuntimeBranchDecision {
  override val invalidReason: String get() = reason
}
