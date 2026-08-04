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

internal object FeatureTaskRuntimeCheckpointScope {
  /**
   * @param issueKey the authority boundary; only this issue's governed spec paths may be committed.
   * @param ownedPaths the durable workflow-owned inventory resolved for the active subtask and phase.
   * @param phaseIntroducedPaths paths the active phase created or modified in the working tree.
   * @param foreignStagedPaths paths already staged by someone else before this checkpoint ran.
   */
  fun decide(
    issueKey: String,
    ownedPaths: List<String>,
    phaseIntroducedPaths: List<String>,
    foreignStagedPaths: List<String>,
  ): FeatureTaskRuntimeCheckpointDecision {
    val owned = ownedPaths.filter(String::isNotBlank).distinct().sorted()
    val ownedAliases = owned.associateBy(::normalizeForAliasComparison)

    val foreignGovernedSpecs = phaseIntroducedPaths.filter { path ->
      isForeignGovernedSpecPath(path, issueKey)
    }.distinct().sorted()
    if (foreignGovernedSpecs.isNotEmpty()) {
      return blockForeignGovernedSpec(issueKey, foreignGovernedSpecs)
    }

    val outsideInventory = phaseIntroducedPaths.filter(String::isNotBlank).distinct()
      .filterNot { path -> normalizeForAliasComparison(path) in ownedAliases }
      .sorted()
    if (outsideInventory.isNotEmpty()) {
      return blockOutsideInventory(issueKey, outsideInventory)
    }

    // An owned path that someone else already staged is genuinely ambiguous: staging over it would
    // commit their index content as this workflow's work, and restoring it would discard their
    // staging. Neither is recoverable from the outside, so the only permitted outcome is a block.
    val overlapping = foreignStagedPaths.filter(String::isNotBlank).distinct()
      .mapNotNull { foreign -> ownedAliases[normalizeForAliasComparison(foreign)] }
      .distinct()
      .sorted()
    if (overlapping.isNotEmpty()) {
      return blockOverlap(overlapping)
    }

    return if (owned.isEmpty()) FeatureTaskRuntimeCheckpointDecision.Skip
    else FeatureTaskRuntimeCheckpointDecision.Stage(owned)
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
  private fun normalizeForAliasComparison(path: String): String =
    path.trim().trimEnd('/').lowercase(Locale.ROOT)

  private fun blockForeignGovernedSpec(issueKey: String, paths: List<String>) =
    FeatureTaskRuntimeCheckpointDecision.Block(
      "needs_human: the active phase touched governed feature-spec path(s) belonging to another " +
        "issue: ${formatPaths(paths)}. This workflow's authority boundary is '$issueKey'; another " +
        "issue's spec is never staged, committed, or reviewed here. Move or revert those paths, then " +
        "resume.",
    )

  private fun blockOutsideInventory(issueKey: String, paths: List<String>) =
    FeatureTaskRuntimeCheckpointDecision.Block(
      "needs_human: the active phase introduced path(s) outside its owned inventory: " +
        "${formatPaths(paths)}. This workflow's authority boundary is '$issueKey' and its durable " +
        "owned-path inventory; a checkpoint never commits a path it does not own. Bring those paths " +
        "into the run's scope or revert them, then resume.",
    )

  private fun blockOverlap(paths: List<String>) = FeatureTaskRuntimeCheckpointDecision.Block(
    "git: owned path(s) ${formatPaths(paths)} are already staged outside this workflow, so the " +
      "checkpoint cannot tell which content to commit. The runtime will not resolve this for you: " +
      "either commit or unstage those paths yourself (git restore --staged -- <path>) and then " +
      "resume. Both the index and worktree versions have been left untouched.",
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
internal object FeatureTaskRuntimeCheckpointMessage {
  fun build(
    issueKey: String,
    branch: String,
    phaseId: String,
    loopId: String?,
    generation: Int,
    intent: String,
  ): String {
    val identity = buildList {
      add("phase=$phaseId")
      loopId?.takeIf(String::isNotBlank)?.let { add("loop=$it") }
      add("generation=$generation")
    }.joinToString(" ")
    return "chore($issueKey): $intent checkpoint on '$branch' [$identity]"
  }

  const val INTENT_AUDITED_IMPLEMENTATION: String = "audited implementation"
  const val INTENT_REMEDIATION: String = "remediation"
}
