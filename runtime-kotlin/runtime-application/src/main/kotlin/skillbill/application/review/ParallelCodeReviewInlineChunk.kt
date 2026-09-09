package skillbill.application.review

import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.review.context.model.ReviewChangedHunk
import java.nio.charset.StandardCharsets

internal data class ParallelCodeReviewInlineChunk(
  val id: String,
  val index: Int,
  val total: Int,
  val hunkIds: Set<String>,
  val targetPaths: Set<String>,
)

private const val INLINE_REVIEW_CHUNK_MAX_EVIDENCE_BYTES = 48 * 1024L
private const val INLINE_REVIEW_CHUNK_MAX_HUNKS = 32

internal fun inlineReviewChunks(selected: List<ReviewSpecialistLaunchRequest>): List<ParallelCodeReviewInlineChunk> {
  val packet = selected.first().packet
  val assignedHunks = selected.flatMap { it.assignment.assignedHunks }.toSet()
  val hunks = packet.changedHunks.filter { it.hunkId in assignedHunks }
  val groups = mutableListOf<List<ReviewChangedHunk>>()
  var current = mutableListOf<ReviewChangedHunk>()
  var currentBytes = 0L
  hunks.forEach { hunk ->
    val hunkBytes = hunk.content.toByteArray(StandardCharsets.UTF_8).size.toLong()
    if (
      current.isNotEmpty() &&
      (
        current.size >= INLINE_REVIEW_CHUNK_MAX_HUNKS ||
          currentBytes + hunkBytes > INLINE_REVIEW_CHUNK_MAX_EVIDENCE_BYTES
        )
    ) {
      groups.add(current.toList())
      current = mutableListOf()
      currentBytes = 0
    }
    current += hunk
    currentBytes += hunkBytes
  }
  if (current.isNotEmpty()) groups.add(current.toList())
  if (groups.isEmpty()) groups.add(emptyList())
  val total = groups.size
  return groups.mapIndexed { index, group ->
    val hunkIds = group.map { it.hunkId }.toSet()
    val paths = group.map { it.path }.toSet()
    val allHunkPaths = hunks.map { it.path }.toSet()
    val expansionPaths = selected.flatMap { it.prelaunchExpansions }
      .map { it.path }
      .filter { it in paths || (index == 0 && it !in allHunkPaths) }
      .toSet()
    val targetPaths = selected.flatMap { launch ->
      launch.assignment.evidenceTargets
        .filter { target ->
          target.path in paths &&
            (target.hunkIds.isEmpty() || target.hunkIds.any { it in hunkIds })
        }
        .map { it.path }
    }.toSet() + expansionPaths + if (hunkIds.isEmpty()) {
      selected.flatMap { it.assignment.assignedPaths }.toSet()
    } else {
      paths
    }
    ParallelCodeReviewInlineChunk(
      id = "inline-${index + 1}",
      index = index,
      total = total,
      hunkIds = hunkIds,
      targetPaths = targetPaths,
    )
  }
}
