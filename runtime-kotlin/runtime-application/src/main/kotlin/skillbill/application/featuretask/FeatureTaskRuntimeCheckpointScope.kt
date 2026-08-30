package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import java.util.Locale

private const val RUNTIME_PRIVATE_ROOT = ".skill-bill/"
private const val RUNTIME_TRACKABLE_CONFIG = ".skill-bill/config.yaml"
private const val SUBTASK_TRAILER_KEY = "Skill-Bill-Subtask"
private const val MAX_REPORTED_PATHS = 10

internal object FeatureTaskRuntimeCheckpointScope {
  fun decide(input: FeatureTaskRuntimeCheckpointScopeInput): FeatureTaskRuntimeCheckpointDecision {
    val deleted = input.deletedPaths.filter(String::isNotBlank)
      .filterNot(::isRuntimePrivatePath)
      .distinct()
      .sorted()
    val declared = (input.ownedPaths + deleted).filter(String::isNotBlank).distinct().sorted()
    val owned = declared.filterNot(::isRuntimePrivatePath)
    val ownedAliases = owned.associateBy(::normalizeForAliasComparison)

    val deltaAliases = input.worktreeDeltaPaths.filter(String::isNotBlank)
      .filterNot(::isRuntimePrivatePath)
      .map(::normalizeForAliasComparison)
      .toSet()
    val introducedStageable = input.phaseIntroducedPaths.filter(String::isNotBlank)
      .filterNot(::isRuntimePrivatePath)
      .filter { normalizeForAliasComparison(it) in deltaAliases }
    val divergentAdopted = adoptedDivergentPaths(input, ownedAliases)
    val ownedSpelling = input.ownedPaths.map(::normalizeForAliasComparison).toSet()
    val deletedAdopted = deleted.filterNot { normalizeForAliasComparison(it) in ownedSpelling }
    val adopted = (divergentAdopted + deletedAdopted).distinct().sorted()
    val stageable = (
      owned.filter { normalizeForAliasComparison(it) in deltaAliases } +
        introducedStageable +
        divergentAdopted +
        deleted
      ).distinct().sorted()
    return if (stageable.isEmpty()) {
      FeatureTaskRuntimeCheckpointDecision.Skip
    } else {
      FeatureTaskRuntimeCheckpointDecision.Stage(stageable, adopted)
    }
  }

  private fun adoptedDivergentPaths(
    input: FeatureTaskRuntimeCheckpointScopeInput,
    ownedAliases: Map<String, String>,
  ): List<String> = (input.foreignStagedPaths + input.concurrentlyModifiedOwnedPaths)
    .filter(String::isNotBlank)
    .mapNotNull { diverged -> ownedAliases[normalizeForAliasComparison(diverged)] }
    .distinct()
    .sorted()
}

internal fun isRuntimePrivatePath(path: String): Boolean {
  val normalized = normalizeForAliasComparison(path)
  if (normalized == RUNTIME_TRACKABLE_CONFIG) return false
  return normalized == RUNTIME_PRIVATE_ROOT.trimEnd('/') ||
    normalized.startsWith(RUNTIME_PRIVATE_ROOT)
}

internal fun phaseWrittenPaths(worktreeDeltaPaths: List<String>, phaseManifestPaths: List<String>): List<String> {
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

internal fun reviewUntrackedExclusions(
  baselineUntrackedPaths: List<String>,
  currentUntrackedPaths: List<String>,
  ownedPaths: List<String>,
): List<String> {
  val ownedAliases = ownedPaths.map(::normalizeForAliasComparison).toSet()
  val foreign = currentUntrackedPaths.filter(String::isNotBlank)
    .filterNot { normalizeForAliasComparison(it) in ownedAliases }
  return (baselineUntrackedPaths + foreign).filter(String::isNotBlank).distinct().sorted()
}

internal fun adoptionWarning(branch: String, paths: List<String>): String =
  "Feature-task-runtime checkpoint adopted owned path(s) ${formatCheckpointPaths(paths)} whose index or " +
    "working-tree content diverged from what this run wrote. The working-tree content is committed " +
    "to '$branch' as this workflow's work rather than blocking the run."

internal fun normalizeForAliasComparison(path: String): String = path.trim().trimEnd('/').lowercase(Locale.ROOT)

private fun formatCheckpointPaths(paths: List<String>): String {
  val reported = paths.take(MAX_REPORTED_PATHS).joinToString(", ") { "'$it'" }
  val overflow = paths.size - MAX_REPORTED_PATHS
  return if (overflow > 0) "$reported (+$overflow more)" else reported
}

internal class FeatureTaskRuntimeCheckpointMetadata(
  val phaseId: String,
  val loopId: String?,
  val generation: Int,
  val branch: String,
  val intent: String,
) {
  override fun toString(): String = buildList {
    add("phase=$phaseId")
    loopId?.takeIf(String::isNotBlank)?.let { add("loop=$it") }
    add("generation=$generation")
  }.joinToString(" ")
}

internal data class FeatureTaskRuntimeSubtaskCommitIdentity(val issueKey: String, val subtaskId: String) {
  init {
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeSubtaskCommitIdentity.issueKey must be non-blank." }
    require(subtaskId.isNotBlank()) { "FeatureTaskRuntimeSubtaskCommitIdentity.subtaskId must be non-blank." }
  }

  val trailer: String get() = "$SUBTASK_TRAILER_KEY: $issueKey/$subtaskId"

  fun checkpointRefName(sequenceNumber: Int): String =
    skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, sequenceNumber)

  fun matches(commitMessage: String): Boolean = parse(commitMessage) == this

  companion object {
    fun parse(commitMessage: String): FeatureTaskRuntimeSubtaskCommitIdentity? = commitMessage.lineSequence()
      .map(String::trim)
      .filter { it.startsWith("$SUBTASK_TRAILER_KEY:") }
      .mapNotNull { line -> identityFrom(line.removePrefix("$SUBTASK_TRAILER_KEY:").trim()) }
      .lastOrNull()

    private fun identityFrom(value: String): FeatureTaskRuntimeSubtaskCommitIdentity? {
      val segments = value.split('/')
      if (segments.size != 2) return null
      val (issueKey, subtaskId) = segments
      if (issueKey.isBlank() || subtaskId.isBlank()) return null
      return FeatureTaskRuntimeSubtaskCommitIdentity(issueKey, subtaskId)
    }
  }
}

internal object FeatureTaskRuntimeCheckpointMessage {
  fun build(
    issueKey: String,
    subtaskName: String?,
    metadata: FeatureTaskRuntimeCheckpointMetadata,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): String {
    val subject = subtaskName?.trim()?.takeIf(String::isNotBlank)
      ?.let { "$issueKey: $it" }
      ?: fallbackSubject(issueKey, identity.subtaskId)
    return compose(subject, metadata, identity)
  }

  fun finalise(
    subject: String,
    metadata: FeatureTaskRuntimeCheckpointMetadata,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): String = compose(subject.trim(), metadata, identity)

  private fun compose(
    subject: String,
    metadata: FeatureTaskRuntimeCheckpointMetadata,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): String {
    val body = "${metadata.intent} checkpoint on '${metadata.branch}'"
    return "$subject\n\n$body\n$metadata\n\n${identity.trailer}\n"
  }

  fun fallbackSubject(issueKey: String, subtaskId: String): String = "$issueKey: subtask $subtaskId"

  fun missingSubtaskNameRecord(issueKey: String, subtaskId: String): String =
    "seam=FeatureTaskRuntimeCheckpointMessage.build value_used='${fallbackSubject(issueKey, subtaskId)}' " +
      "value_expected=manifest subtask name for '$issueKey' subtask '$subtaskId' " +
      "cause=the durable goal-continuation row carried no subtask name; the checkpoint subject " +
      "degrades to the issue key until finalisation rewrites it"

  const val INTENT_AUDITED_IMPLEMENTATION: String = "audited implementation"
  const val INTENT_REMEDIATION: String = "remediation"
  const val INTENT_FINALISED_SUBTASK: String = "finalised subtask"
}
