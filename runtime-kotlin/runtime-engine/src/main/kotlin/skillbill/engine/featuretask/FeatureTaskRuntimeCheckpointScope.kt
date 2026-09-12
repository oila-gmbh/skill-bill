package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.engine.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import java.util.Locale

private const val RUNTIME_PRIVATE_ROOT = ".skill-bill/"
private const val RUNTIME_TRACKABLE_CONFIG = ".skill-bill/config.yaml"
private const val MAX_REPORTED_PATHS = 10

object FeatureTaskRuntimeCheckpointScope {
  fun decide(input: FeatureTaskRuntimeCheckpointScopeInput): FeatureTaskRuntimeCheckpointDecision {
    val deleted = sanitized(input.deletedPaths)
    val implementationPaths = sanitized(
      input.ownedPaths +
        input.phaseIntroducedPaths +
        input.concurrentlyModifiedOwnedPaths +
        deleted,
    ).filterNot { isFeatureSpecPathForIssue(it, input.issueKey) }
    val implementationAliases = implementationPaths
      .groupBy(::normalizeForAliasComparison)
      .mapValues { (_, paths) -> paths.first() }
    val stageable = sanitized(
      input.worktreeDeltaPaths +
        input.phaseIntroducedPaths +
        input.foreignStagedPaths +
        input.concurrentlyModifiedOwnedPaths +
        deleted,
    ).filterNot { isFeatureSpecPathForIssue(it, input.issueKey) }
      .mapNotNull { path ->
        implementationAliases[normalizeForAliasComparison(path)]
      }.distinct().sorted()
    val adopted = sanitized(
      input.foreignStagedPaths +
        input.concurrentlyModifiedOwnedPaths +
        deleted,
    ).filterNot { isFeatureSpecPathForIssue(it, input.issueKey) }
      .mapNotNull { path ->
        implementationAliases[normalizeForAliasComparison(path)]
      }.distinct().sorted()
    return if (stageable.isEmpty()) {
      FeatureTaskRuntimeCheckpointDecision.Skip
    } else {
      FeatureTaskRuntimeCheckpointDecision.Stage(stageable, adopted)
    }
  }
}

private fun sanitized(paths: Collection<String>): List<String> =
  paths.filter(String::isNotBlank).filterNot(::isRuntimePrivatePath)

fun isRuntimePrivatePath(path: String): Boolean {
  val normalized = normalizeForAliasComparison(path)
  if (normalized == RUNTIME_TRACKABLE_CONFIG) return false
  return normalized == RUNTIME_PRIVATE_ROOT.trimEnd('/') ||
    normalized.startsWith(RUNTIME_PRIVATE_ROOT)
}

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

fun adoptionWarning(branch: String, paths: List<String>): String =
  "Feature-task-runtime checkpoint adopted owned path(s) ${formatCheckpointPaths(paths)} whose index or " +
    "working-tree content diverged from what this run wrote. The working-tree content is committed " +
    "to '$branch' as this workflow's work rather than blocking the run."

fun normalizeForAliasComparison(path: String): String = path.trim().trimEnd('/').lowercase(Locale.ROOT)

private fun formatCheckpointPaths(paths: List<String>): String {
  val reported = paths.take(MAX_REPORTED_PATHS).joinToString(", ") { "'$it'" }
  val overflow = paths.size - MAX_REPORTED_PATHS
  return if (overflow > 0) "$reported (+$overflow more)" else reported
}

class FeatureTaskRuntimeCheckpointMetadata(
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

object FeatureTaskRuntimeCheckpointMessage {
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
