package skillbill.engine.featuretask.model

/**
 * SKILL-150: what a checkpoint is allowed to do with the tree it finds.
 *
 * A checkpoint stages every non-runtime-private dirty path. The active subtask owns those changes,
 * including files another process wrote. This decision is pure: it takes the inventories as values
 * and returns stage or skip, so the whole policy is testable without a repository.
 *
 * A working tree that simply diverged from what the run remembers never blocks. [Block] remains for
 * git-read and preservation failures the run loop raises outside this pure decision.
 */
sealed interface FeatureTaskRuntimeCheckpointDecision {
  /**
   * Stage [ownedPaths] and commit. [adoptedPaths] is the subset that was already staged or modified
   * outside the remembered inventory; it is reported, never refused.
   */
  data class Stage(
    val ownedPaths: List<String>,
    val adoptedPaths: List<String> = emptyList(),
  ) : FeatureTaskRuntimeCheckpointDecision

  /** The dirty tree is empty, so there is nothing to checkpoint. */
  data object Skip : FeatureTaskRuntimeCheckpointDecision

  /** Refuse: a git read or preservation step failed before staging could proceed safely. */
  data class Block(val reason: String) : FeatureTaskRuntimeCheckpointDecision
}

/**
 * @param issueKey the authority boundary for diagnostics and inventory reconciliation.
 * @param ownedPaths the durable workflow-owned inventory. Used to preserve known spellings when
 * dirty paths alias an owned path.
 * @param phaseIntroducedPaths paths the active phase itself wrote, from its own before/after file
 * manifest. Staged with the rest of the dirty tree.
 * @param worktreeDeltaPaths paths currently differing from HEAD.
 * @param foreignStagedPaths paths already staged before this checkpoint ran. Staged with the rest
 * of this subtask's work.
 * @param concurrentlyModifiedOwnedPaths owned paths whose working-tree content changed after the
 * active phase finished writing them.
 * @param deletedPaths tracked paths removed from the worktree (or rename sources). Removals are
 * staged so a package move can commit both halves.
 */
data class FeatureTaskRuntimeCheckpointScopeInput(
  val issueKey: String,
  val ownedPaths: List<String>,
  val phaseIntroducedPaths: List<String>,
  val worktreeDeltaPaths: List<String>,
  val foreignStagedPaths: List<String> = emptyList(),
  val concurrentlyModifiedOwnedPaths: List<String> = emptyList(),
  val deletedPaths: List<String> = emptyList(),
)
