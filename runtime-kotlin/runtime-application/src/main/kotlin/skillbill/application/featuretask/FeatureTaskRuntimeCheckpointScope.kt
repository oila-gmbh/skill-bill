package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import java.util.Locale

private const val GOVERNED_SPEC_ROOT = ".feature-specs/"
private const val RUNTIME_PRIVATE_ROOT = ".skill-bill/"
private const val RUNTIME_TRACKABLE_CONFIG = ".skill-bill/config.yaml"

/** Bounds a block message so one pathological inventory cannot flood a durable blocked reason. */
private const val MAX_REPORTED_PATHS = 10

@Suppress("TooManyFunctions") // single cohesive decision surface for the checkpoint scope guards and messages
internal object FeatureTaskRuntimeCheckpointScope {
  fun decide(input: FeatureTaskRuntimeCheckpointScopeInput): FeatureTaskRuntimeCheckpointDecision {
    val declared = input.ownedPaths.filter(String::isNotBlank).distinct().sorted()
    val declaredAliases = declared.map(::normalizeForAliasComparison).toSet()
    // A foreign issue's governed spec is never staged, even when it reached the durable inventory:
    // ownership is not a licence to commit another workflow's authority. Evicting it here also keeps
    // an inventory that already carries one from blocking every later checkpoint of this run.
    val owned = declared.filterNot { isForeignGovernedSpecPath(it, input.issueKey) }
      .filterNot(::isRuntimePrivatePath)
    val ownedAliases = owned.associateBy(::normalizeForAliasComparison)

    blockingDecision(input, ownedAliases, declaredAliases)?.let { return it }

    val deltaAliases = input.worktreeDeltaPaths.filter(String::isNotBlank)
      .filterNot(::isRuntimePrivatePath)
      .map(::normalizeForAliasComparison)
      .toSet()
    val adopted = adoptedDivergentPaths(input, ownedAliases)
    val stageable = (owned.filter { normalizeForAliasComparison(it) in deltaAliases } + adopted).distinct().sorted()
    return if (stageable.isEmpty()) {
      FeatureTaskRuntimeCheckpointDecision.Skip
    } else {
      FeatureTaskRuntimeCheckpointDecision.Stage(stageable, adopted)
    }
  }

  /**
   * Owned paths whose index or working-tree content diverged from what this phase left behind:
   * someone staged them outside the workflow, or edited them while the run was between phases.
   *
   * The checkpoint adopts them rather than refusing. A checkpoint that blocks strands a durable run
   * that nothing but a human can restart, which costs more than the misattribution it prevents — and
   * the run is already known to be on its own branch by the time this decision is reached, so the
   * blast radius of adopting is that branch alone. The working tree is the authority: staging it
   * overwrites a foreign index entry with the content actually on disk, and the previous index
   * version stays recoverable through git's own reflog and object store.
   */
  private fun adoptedDivergentPaths(
    input: FeatureTaskRuntimeCheckpointScopeInput,
    ownedAliases: Map<String, String>,
  ): List<String> = (input.foreignStagedPaths + input.concurrentlyModifiedOwnedPaths)
    .filter(String::isNotBlank)
    .mapNotNull { diverged -> ownedAliases[normalizeForAliasComparison(diverged)] }
    .distinct()
    .sorted()

  /**
   * The guards that block a checkpoint before anything may be staged: a foreign governed spec is the
   * broadest violation, then phase-introduced paths outside the owned inventory. Both are ownership
   * questions — the checkpoint would commit a path belonging to another issue or to nobody here.
   *
   * A worktree that merely diverged from what the run remembers is NOT one of these. That is handled
   * by [adoptedDivergentPaths], which continues instead of blocking. Returns null when neither guard
   * applies, so [decide] may fall through to the stageable set.
   */
  private fun blockingDecision(
    input: FeatureTaskRuntimeCheckpointScopeInput,
    ownedAliases: Map<String, String>,
    declaredAliases: Set<String>,
  ): FeatureTaskRuntimeCheckpointDecision.Block? {
    val foreign = input.phaseIntroducedPaths.filter { isForeignGovernedSpecPath(it, input.issueKey) }
      // Already in the durable inventory means a previous checkpoint recorded it; it is evicted from
      // the stageable set instead, so the run self-heals rather than needing a human to move files.
      .filterNot { normalizeForAliasComparison(it) in declaredAliases }
      .distinct().sorted().takeIf { it.isNotEmpty() }
      ?.let { blockForeignGovernedSpec(input.issueKey, it) }
    val outside = input.phaseIntroducedPaths.filter(String::isNotBlank).distinct()
      .filterNot { isForeignGovernedSpecPath(it, input.issueKey) }
      // Runtime-private evidence under `.skill-bill/` is written by the runtime itself; treating it as
      // phase-introduced feature work is a self-conflict (shared review evidence is the usual case).
      .filterNot(::isRuntimePrivatePath)
      .filterNot { path -> normalizeForAliasComparison(path) in ownedAliases }
      .sorted().takeIf { it.isNotEmpty() }
      ?.let { blockOutsideInventory(input.issueKey, it) }
    return listOfNotNull(foreign, outside).firstOrNull()
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
    val manifest = phaseManifestPaths.filter(String::isNotBlank)
      .filterNot(::isRuntimePrivatePath)
      .map(::normalizeForAliasComparison)
    if (manifest.isEmpty()) return emptyList()
    return worktreeDeltaPaths.filter(String::isNotBlank)
      .filterNot(::isRuntimePrivatePath)
      .filter { path ->
        val normalized = normalizeForAliasComparison(path)
        manifest.any { entry -> normalized == entry || normalized.startsWith("$entry/") }
      }.distinct().sorted()
  }

  /**
   * Runtime-private paths the install-time ignore rule is supposed to hide. Ownership and review
   * still exclude them explicitly: consumer repos may lack that ignore, and the runtime writes
   * shared evidence mid-phase regardless.
   *
   * `.skill-bill/config.yaml` stays trackable and is not private.
   */
  fun isRuntimePrivatePath(path: String): Boolean {
    val normalized = normalizeForAliasComparison(path)
    if (normalized == RUNTIME_TRACKABLE_CONFIG) return false
    return normalized == RUNTIME_PRIVATE_ROOT.trimEnd('/') ||
      normalized.startsWith(RUNTIME_PRIVATE_ROOT)
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

  /** Announces an adoption so the divergence is visible in the run log instead of silent. */
  fun adoptionWarning(branch: String, paths: List<String>): String =
    "Feature-task-runtime checkpoint adopted owned path(s) ${formatPaths(paths)} whose index or " +
      "working-tree content diverged from what this run wrote. The working-tree content is committed " +
      "to '$branch' as this workflow's work rather than blocking the run."

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
