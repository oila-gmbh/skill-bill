package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import java.util.Locale

internal object FeatureTaskRuntimeCheckpointScope {
  /**
   * A checkpoint stages everything dirty in the worktree. It never blocks, and it never warns.
   *
   * The run does not have exclusive use of the tree: a human works alongside it, adding files and
   * edits mid-execution that belong in the same commit. An ownership gate cannot tell that
   * contribution apart from a stray path, so it either strands a durable run over a file the human
   * meant to include, or drops that file on the floor. Committing the whole delta keeps the branch
   * matching what is actually on disk, and the run is already on its own branch by the time this
   * decision is reached, so the blast radius is that branch alone.
   *
   * A divergent index entry needs no announcement either. The working tree is the authority: staging
   * it overwrites a foreign index entry with the content actually on disk, and the previous index
   * version stays recoverable through git's own reflog and object store.
   */
  fun decide(input: FeatureTaskRuntimeCheckpointScopeInput): FeatureTaskRuntimeCheckpointDecision {
    val stageable = (
      input.worktreeDeltaPaths + input.foreignStagedPaths + input.concurrentlyModifiedOwnedPaths
      ).filter(String::isNotBlank).distinct().sorted()
    return if (stageable.isEmpty()) {
      FeatureTaskRuntimeCheckpointDecision.Skip
    } else {
      FeatureTaskRuntimeCheckpointDecision.Stage(stageable)
    }
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
