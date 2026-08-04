package skillbill.application.featuretask

import java.util.Locale

private const val GOVERNED_SPEC_ROOT = ".feature-specs/"

/** Bounds a block message so one pathological inventory cannot flood a durable blocked reason. */
private const val MAX_REPORTED_PATHS = 10

/**
 * SKILL-150: what a checkpoint is allowed to do with the tree it finds.
 *
 * A checkpoint owns an explicit inventory, and everything outside it belongs to someone else — the
 * user's own work in progress, a sibling workflow, or a concurrently prepared issue. This decision is
 * pure: it takes the inventories as values and returns stage, skip, or block, so the whole policy is
 * testable without a repository and the run loop keeps no branching of its own.
 */
internal sealed interface FeatureTaskRuntimeCheckpointDecision {
  /** Stage exactly [ownedPaths] and commit. */
  data class Stage(val ownedPaths: List<String>) : FeatureTaskRuntimeCheckpointDecision

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
internal data class FeatureTaskRuntimeCheckpointScopeInput(
  val issueKey: String,
  val ownedPaths: List<String>,
  val phaseIntroducedPaths: List<String>,
  val worktreeDeltaPaths: List<String>,
  val foreignStagedPaths: List<String> = emptyList(),
  val concurrentlyModifiedOwnedPaths: List<String> = emptyList(),
)

@Suppress("TooManyFunctions") // single cohesive decision surface for the checkpoint scope guards and messages
internal object FeatureTaskRuntimeCheckpointScope {
  fun decide(input: FeatureTaskRuntimeCheckpointScopeInput): FeatureTaskRuntimeCheckpointDecision {
    val owned = input.ownedPaths.filter(String::isNotBlank).distinct().sorted()
    val ownedAliases = owned.associateBy(::normalizeForAliasComparison)

    blockingDecision(input, ownedAliases)?.let { return it }

    val deltaAliases = input.worktreeDeltaPaths.filter(String::isNotBlank)
      .map(::normalizeForAliasComparison)
      .toSet()
    val stageable = owned.filter { normalizeForAliasComparison(it) in deltaAliases }
    return if (stageable.isEmpty()) {
      FeatureTaskRuntimeCheckpointDecision.Skip
    } else {
      FeatureTaskRuntimeCheckpointDecision.Stage(stageable)
    }
  }

  /**
   * The guards that block a checkpoint before anything may be staged. Ordering is structural: a
   * foreign governed spec is the broadest violation, then phase-introduced paths outside the owned
   * inventory, then staged/concurrent overlaps with owned paths. Returns null when none apply, so
   * [decide] may fall through to the stageable set.
   */
  private fun blockingDecision(
    input: FeatureTaskRuntimeCheckpointScopeInput,
    ownedAliases: Map<String, String>,
  ): FeatureTaskRuntimeCheckpointDecision.Block? {
    val foreign = input.phaseIntroducedPaths.filter { isForeignGovernedSpecPath(it, input.issueKey) }
      .distinct().sorted().takeIf { it.isNotEmpty() }
      ?.let { blockForeignGovernedSpec(input.issueKey, it) }
    val outside = input.phaseIntroducedPaths.filter(String::isNotBlank).distinct()
      .filterNot { path -> normalizeForAliasComparison(path) in ownedAliases }
      .sorted().takeIf { it.isNotEmpty() }
      ?.let { blockOutsideInventory(input.issueKey, it) }
    // An owned path that someone else already staged is genuinely ambiguous: staging over it would
    // commit their index content as this workflow's work, and restoring it would discard their
    // staging. Neither is recoverable from the outside, so the only permitted outcome is a block.
    val overlapping = input.foreignStagedPaths.filter(String::isNotBlank).distinct()
      .mapNotNull { overlappingPath -> ownedAliases[normalizeForAliasComparison(overlappingPath)] }
      .distinct().sorted().takeIf { it.isNotEmpty() }
      ?.let { blockStagedOverlap(it) }
    // The unstaged half of the same ambiguity: nobody staged the file, but its content is no longer
    // the content this phase left behind, so committing it would attribute a stranger's edit here.
    val concurrent = input.concurrentlyModifiedOwnedPaths.filter(String::isNotBlank).distinct()
      .mapNotNull { modified -> ownedAliases[normalizeForAliasComparison(modified)] }
      .distinct().sorted().takeIf { it.isNotEmpty() }
      ?.let { blockConcurrentModification(it) }
    return listOfNotNull(foreign, outside, overlapping, concurrent).firstOrNull()
  }

  /**
   * The subset of [worktreeDeltaPaths] the active phase actually wrote.
   *
   * The delta is plumbing output; the phase file manifest is porcelain, which collapses a wholly
   * untracked directory to a single `dir/` entry. Matching a delta path against a manifest directory
   * prefix keeps the two representations comparable without widening the phase's write set to
   * everything dirty in the tree.
   */
  fun phaseWrittenPaths(worktreeDeltaPaths: List<String>, phaseManifestPaths: List<String>): List<String> {
    val manifest = phaseManifestPaths.filter(String::isNotBlank).map(::normalizeForAliasComparison)
    if (manifest.isEmpty()) return emptyList()
    return worktreeDeltaPaths.filter(String::isNotBlank).filter { path ->
      val normalized = normalizeForAliasComparison(path)
      manifest.any { entry -> normalized == entry || normalized.startsWith("$entry/") }
    }.distinct().sorted()
  }

  /**
   * A governed spec path belonging to any issue other than the active one. `.feature-specs/` is the
   * one tree where a concurrently prepared issue is both likely and unmistakably foreign: it is
   * governed, it is another workflow's authority, and attributing it here would credit this run with
   * work it never did.
   */
  fun isForeignGovernedSpecPath(path: String, issueKey: String): Boolean {
    val normalized = path.trim()
    if (!normalized.startsWith(GOVERNED_SPEC_ROOT)) return false
    val issueDirectory = normalized.removePrefix(GOVERNED_SPEC_ROOT).substringBefore('/')
    if (issueDirectory.isBlank()) return false
    val key = issueKey.trim()
    return issueDirectory != key && !issueDirectory.startsWith("$key-")
  }

  /**
   * The untracked-path exclusion list a review pass must carry so foreign dirt cannot reach it.
   *
   * Review input already excludes the baseline untracked inventory. That bounds what existed BEFORE
   * the run, but not what appeared beside it since — a sibling workflow's new file, or a
   * concurrently prepared `.feature-specs/` spec, is neither in the baseline nor owned here, and
   * would otherwise be materialized into the input as this run's own change and shift its semantic
   * delta digest. Widening the exclusion list to every currently-untracked path this run does not own
   * composes with the existing baseline and remediation-base semantics instead of replacing them.
   */
  fun reviewUntrackedExclusions(
    baselineUntrackedPaths: List<String>,
    currentUntrackedPaths: List<String>,
    ownedPaths: List<String>,
  ): List<String> {
    val ownedAliases = ownedPaths.map(::normalizeForAliasComparison).toSet()
    val foreign = currentUntrackedPaths.filter(String::isNotBlank)
      .filterNot { normalizeForAliasComparison(it) in ownedAliases }
    return (baselineUntrackedPaths + foreign).filter(String::isNotBlank).distinct().sorted()
  }

  /**
   * Case-folds and strips a trailing separator so two spellings of one path cannot be read as two
   * distinct paths. On a case-insensitive filesystem `Src/A.kt` and `src/a.kt` ARE the same file, and
   * treating them as distinct would let a foreign staged entry slip past the overlap check and be
   * silently overwritten by the checkpoint's own staging.
   */
  private fun normalizeForAliasComparison(path: String): String = path.trim().trimEnd('/').lowercase(Locale.ROOT)

  private fun blockForeignGovernedSpec(issueKey: String, paths: List<String>) =
    FeatureTaskRuntimeCheckpointDecision.Block(
      "needs_human: the active phase touched governed feature-spec path(s) belonging to another " +
        "issue: ${formatPaths(paths)}. This workflow's authority boundary is '$issueKey'; another " +
        "issue's spec is never staged, committed, or reviewed here. Move or revert those paths, then " +
        "resume.",
    )

  private fun blockOutsideInventory(issueKey: String, paths: List<String>) = FeatureTaskRuntimeCheckpointDecision.Block(
    "needs_human: the active phase introduced path(s) outside its owned inventory: " +
      "${formatPaths(paths)}. This workflow's authority boundary is '$issueKey' and its durable " +
      "owned-path inventory; a checkpoint never commits a path it does not own. Bring those paths " +
      "into the run's scope or revert them, then resume.",
  )

  private fun blockStagedOverlap(paths: List<String>) = FeatureTaskRuntimeCheckpointDecision.Block(
    "git: owned path(s) ${formatPaths(paths)} are already staged outside this workflow, so the " +
      "checkpoint cannot tell which content to commit. The runtime will not resolve this for you: " +
      "either commit or unstage those paths yourself (git restore --staged -- <path>) and then " +
      "resume. Both the index and worktree versions have been left untouched.",
  )

  private fun blockConcurrentModification(paths: List<String>) = FeatureTaskRuntimeCheckpointDecision.Block(
    "git: owned path(s) ${formatPaths(paths)} were modified in the working tree after this phase " +
      "finished writing them, so the checkpoint cannot tell which content is this workflow's work. " +
      "The runtime will not resolve this for you: review those paths, keep or revert the concurrent " +
      "edit yourself (git diff -- <path>), and then resume. Both the index and worktree versions " +
      "have been left untouched.",
  )

  private fun formatPaths(paths: List<String>): String {
    val reported = paths.take(MAX_REPORTED_PATHS).joinToString(", ") { "'$it'" }
    val overflow = paths.size - MAX_REPORTED_PATHS
    return if (overflow > 0) "$reported (+$overflow more)" else reported
  }
}

/**
 * Checkpoint commit messages. Git history alone has to answer which boundary produced a commit and
 * which loop generation it belongs to — otherwise initial implementation, audit repair, and review
 * remediation checkpoints on one branch are indistinguishable after the fact.
 */
internal class FeatureTaskRuntimeCheckpointIdentity(
  val phaseId: String,
  val loopId: String?,
  val generation: Int,
) {
  override fun toString(): String = buildList {
    add("phase=$phaseId")
    loopId?.takeIf(String::isNotBlank)?.let { add("loop=$it") }
    add("generation=$generation")
  }.joinToString(" ")
}

internal object FeatureTaskRuntimeCheckpointMessage {
  fun build(issueKey: String, branch: String, identity: FeatureTaskRuntimeCheckpointIdentity, intent: String): String =
    "chore($issueKey): $intent checkpoint on '$branch' [$identity]"

  const val INTENT_AUDITED_IMPLEMENTATION: String = "audited implementation"
  const val INTENT_REMEDIATION: String = "remediation"
}
