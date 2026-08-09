package skillbill.application.featuretask.model

/**
 * SKILL-150: what a checkpoint does with the tree it finds.
 *
 * A checkpoint commits the worktree as it stands. It cannot refuse: a human works in the tree
 * alongside the run, and any file they add mid-execution is meant to land in the same commit. There
 * is no ownership question left to answer, so the decision is only stage-or-nothing-to-do. It stays
 * pure — inventories in, decision out — so the policy is testable without a repository and the run
 * loop keeps no branching of its own.
 */
sealed interface FeatureTaskRuntimeCheckpointDecision {
  /** Stage exactly [stagedPaths] and commit. */
  data class Stage(val stagedPaths: List<String>) : FeatureTaskRuntimeCheckpointDecision

  /** The worktree delta is empty, so there is nothing to checkpoint. */
  data object Skip : FeatureTaskRuntimeCheckpointDecision
}

/**
 * @param issueKey the workflow's issue key, carried for message and telemetry context only.
 * @param ownedPaths the durable workflow-owned inventory. Retained as run bookkeeping; it no longer
 * bounds what a checkpoint may stage.
 * @param worktreeDeltaPaths paths currently differing from the ownership baseline. This is the
 * staging set: everything dirty is committed.
 * @param foreignStagedPaths paths already staged by someone else before this checkpoint ran.
 * @param concurrentlyModifiedOwnedPaths owned paths whose working-tree content changed after the
 * active phase finished writing them.
 */
data class FeatureTaskRuntimeCheckpointScopeInput(
  val issueKey: String,
  val ownedPaths: List<String>,
  val worktreeDeltaPaths: List<String>,
  val foreignStagedPaths: List<String> = emptyList(),
  val concurrentlyModifiedOwnedPaths: List<String> = emptyList(),
)
