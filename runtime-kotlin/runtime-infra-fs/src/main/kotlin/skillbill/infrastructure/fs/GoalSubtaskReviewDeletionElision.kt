package skillbill.infrastructure.fs

import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import java.nio.file.Path

internal fun goalReviewDiffArguments(baseline: GoalSubtaskReviewBaseline, vararg options: String): List<String> =
  listOf("diff", "--binary", *options, baseline.reviewBaseSha) + baseline.ownedPathspecArguments()

private fun goalReviewNumstatArguments(baseline: GoalSubtaskReviewBaseline): List<String> =
  listOf("diff", "--numstat", "--diff-filter=D", baseline.reviewBaseSha) + baseline.ownedPathspecArguments()

private fun GoalSubtaskReviewBaseline.ownedPathspecArguments(): List<String> =
  ownedPathspec.takeIf { spec -> spec.isNotEmpty() }?.let { spec -> listOf("--") + spec }.orEmpty()

/**
 * The delta as review will read it: the complete one when it fits, otherwise the same delta with
 * every deleted file's body replaced by a manifest line naming the path and the lines it lost.
 *
 * A subtask that retires a module spends most of its delta restating source that no longer exists —
 * on WE-4860 subtask 3, 1.1MB of the 1.7MB total was the bodies of 170 deleted files. Blocking there
 * refuses to review the additions and modifications too, which is strictly worse than reviewing them
 * without the removed bodies inline. Nothing is hidden: the manifest names every deleted path, and a
 * lane that needs one of those bodies reaches it through `request_expansion`, which is already how
 * this review reads anything past its assigned hunks.
 *
 * Elision is conditional rather than unconditional, so an ordinary subtask's review input stays
 * byte-for-byte what it was. When it does fire, the manifest header says so, so a reduced input is
 * never mistaken for a complete one. A delta still over the bound with every deletion elided keeps
 * its full text and fails the bound check, which then reports the real size rather than a reduced
 * one.
 */
internal fun withinReviewInputBound(
  repoRoot: Path,
  baseline: GoalSubtaskReviewBaseline,
  fullDelta: String,
  untrackedBytes: Int,
): String {
  if (fitsReviewInputBound(fullDelta, untrackedBytes)) return fullDelta
  val elided = deletionElidedDelta(repoRoot, baseline) ?: return fullDelta
  return if (fitsReviewInputBound(elided, untrackedBytes)) elided else fullDelta
}

private fun fitsReviewInputBound(delta: String, untrackedBytes: Int): Boolean =
  delta.toByteArray().size + untrackedBytes <= GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES

private fun deletionElidedDelta(repoRoot: Path, baseline: GoalSubtaskReviewBaseline): String? {
  val kept = goalReviewGitValue(repoRoot, goalReviewDiffArguments(baseline, "--diff-filter=d")) ?: return null
  val removed = goalReviewGitValue(repoRoot, goalReviewNumstatArguments(baseline)) ?: return null
  return deletionManifest(removed)?.plus(kept)
}

private fun deletionManifest(numstat: String): String? {
  val entries = numstat.lineSequence().mapNotNull(::deletionManifestEntry).toList()
  if (entries.isEmpty()) return null
  return buildString {
    appendLine("### ${entries.size} deleted files, bodies elided from this review input")
    appendLine("### The complete delta exceeds the $GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES-byte bound.")
    appendLine("### Every added, modified, and renamed file below is present in full.")
    appendLine("### Read a removed file's pre-deletion content with request_expansion.")
    entries.forEach(::appendLine)
    appendLine("### end of elided deletions")
    appendLine()
  }
}

// `git diff --numstat` writes "<added>\t<deleted>\t<path>", and "-\t-\t<path>" for a binary file.
private fun deletionManifestEntry(line: String): String? {
  val fields = line.split('\t')
  if (fields.size < NUMSTAT_FIELD_COUNT) return null
  val path = fields[NUMSTAT_FIELD_COUNT - 1].takeIf(String::isNotBlank) ?: return null
  val lost = fields[1].takeIf { it != "-" }?.let { "-$it lines" } ?: "binary"
  return "###   $path ($lost)"
}

private const val NUMSTAT_FIELD_COUNT: Int = 3
