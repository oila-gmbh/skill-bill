package skillbill.application.featuretask.model

/**
 * SKILL-150: what a checkpoint is allowed to do with the tree it finds.
 *
 * A checkpoint owns an explicit inventory, and everything outside it belongs to someone else — the
 * user's own work in progress, a sibling workflow, or a concurrently prepared issue. This decision is
 * pure: it takes the inventories as values and returns stage, skip, or block, so the whole policy is
 * testable without a repository and the run loop keeps no branching of its own.
 *
 * Block is reserved for ownership violations. A working tree that simply diverged from what the run
 * remembers — a foreign staging, a concurrent edit — never blocks: the run is on its own branch, and
 * a checkpoint that refuses there strands a durable run only a human can restart.
 */
sealed interface FeatureTaskRuntimeCheckpointDecision {
  /**
   * Stage exactly [ownedPaths] and commit. [adoptedPaths] is the subset whose index or working-tree
   * content diverged from what this run wrote and was adopted anyway; it is reported, never refused.
   */
  data class Stage(
    val ownedPaths: List<String>,
    val adoptedPaths: List<String> = emptyList(),
  ) : FeatureTaskRuntimeCheckpointDecision

  /** The owned delta is empty, so there is nothing to checkpoint. Foreign dirt alone never commits. */
  data object Skip : FeatureTaskRuntimeCheckpointDecision

  /** Refuse: committing would either overwrite or misattribute work this workflow does not own. */
  data class Block(val reason: String) : FeatureTaskRuntimeCheckpointDecision
}

/**
 * @param issueKey the authority boundary; only this issue's governed spec paths may be committed.
 * @param ownedPaths the durable workflow-owned inventory: the SOLE staging authority. A path is
 * never staged by virtue of being dirty, only by being in this inventory.
 * @param phaseIntroducedPaths paths the active phase itself wrote, from its own before/after file
 * manifest. Used to police the inventory boundary, never to widen it.
 * @param worktreeDeltaPaths paths currently differing from the ownership baseline. Used only to
 * decide whether the owned inventory has anything left to stage.
 * @param foreignStagedPaths paths already staged by someone else before this checkpoint ran.
 * @param concurrentlyModifiedOwnedPaths owned paths whose working-tree content changed after the
 * active phase finished writing them, so the change cannot be attributed to this workflow.
 */
data class FeatureTaskRuntimeCheckpointScopeInput(
  val issueKey: String,
  val ownedPaths: List<String>,
  val phaseIntroducedPaths: List<String>,
  val worktreeDeltaPaths: List<String>,
  val foreignStagedPaths: List<String> = emptyList(),
  val concurrentlyModifiedOwnedPaths: List<String> = emptyList(),
)
