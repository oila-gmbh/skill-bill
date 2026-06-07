package skillbill.review

import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity

object ParallelReviewMerger {
  fun merge(lane1: ParallelReviewLaneResult, lane2: ParallelReviewLaneResult): ParallelReviewMergeResult {
    val findings1 = ParallelReviewFindingParser.parse(lane1.rawOutput)
    val findings2 = ParallelReviewFindingParser.parse(lane2.rawOutput)

    val allEntries = mutableListOf<FindingEntry>()
    findings1.forEachIndexed { i, f -> allEntries += FindingEntry(f, lane1.agentId, i) }
    findings2.forEachIndexed { i, f -> allEntries += FindingEntry(f, lane2.agentId, i) }

    // Deterministic greedy single pass in insertion order (lane1 entries first, then lane2).
    // Each entry joins the first existing cluster whose first-inserted representative shares the
    // same file path AND clears the Jaccard token-overlap threshold; otherwise it opens a new
    // cluster. First-inserted representative + insertion order make the result independent of map
    // iteration order.
    val clusters = mutableListOf<MutableList<FindingEntry>>()
    allEntries.forEach { entry ->
      val cluster = clusters.firstOrNull { existing ->
        val representative = existing.first().finding
        filePathOf(representative.location) == filePathOf(entry.finding.location) &&
          jaccard(tokens(representative.description), tokens(entry.finding.description)) > FUZZY_DEDUP_THRESHOLD
      }
      if (cluster != null) cluster += entry else clusters += mutableListOf(entry)
    }

    val candidates = clusters.map { entries ->
      val coalesced = entries.map { it.agentId }.distinct().size > 1
      val highestSeverity = entries.minByOrNull { it.finding.severity.ordinal }!!.finding.severity
      val firstEntry = entries.minByOrNull { it.appearanceOrder }!!
      MergedCandidate(
        agentIds = entries.map { it.agentId }.distinct(),
        severity = highestSeverity,
        confidence = firstEntry.finding.confidence,
        location = firstEntry.finding.location,
        description = firstEntry.finding.description,
        isCoalesced = coalesced,
        firstAppearance = firstEntry.appearanceOrder,
      )
    }

    val sorted = candidates.sortedWith(
      compareBy<MergedCandidate> { it.severity.ordinal }
        .thenBy { if (it.isCoalesced) 0 else 1 }
        .thenBy { it.firstAppearance },
    )

    val mergedFindings = sorted.mapIndexed { index, candidate ->
      ParallelReviewMergedFinding(
        fNumber = "F-%03d".format(index + 1),
        agentIds = candidate.agentIds,
        severity = candidate.severity,
        confidence = candidate.confidence,
        location = candidate.location,
        description = candidate.description,
      )
    }

    val formattedOutput = mergedFindings.joinToString("\n") { f ->
      val agentLabel = f.agentIds.joinToString(", ")
      "- [${f.fNumber}] [$agentLabel] ${f.severity.displayName} | ${f.confidence} | ${f.location} | ${f.description}"
    }

    return ParallelReviewMergeResult(
      findings = mergedFindings,
      formattedOutput = formattedOutput,
    )
  }

  // Jaccard token-overlap floor for coalescing two same-file findings. Comparison is strict `>`:
  // a pair coalesces only when its overlap ratio is strictly above this value. Update here to
  // retune fuzzy dedup sensitivity — no other code depends on the literal.
  private const val FUZZY_DEDUP_THRESHOLD = 0.6

  // File-path portion of a location field ("file:line" -> "file"). Kotlin's substringBeforeLast
  // returns the whole string when there is no colon, so colon-less locations fall back to
  // themselves. Lower-cased so path comparison is case-insensitive.
  private fun filePathOf(location: String): String = location.substringBeforeLast(":").trim().lowercase()

  // Word set of a description: lower-cased, split on any non-alphanumeric run, empties dropped.
  private fun tokens(description: String): Set<String> =
    description.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

  // Jaccard similarity = |intersection| / |union|. Empty union means both sets are empty, which
  // returns 1.0 so identical/empty descriptions on the same file still coalesce (preserving
  // exact-match behaviour); disjoint non-empty sets yield 0.0 from the ratio.
  private fun jaccard(a: Set<String>, b: Set<String>): Double {
    val union = a union b
    if (union.isEmpty()) return 1.0
    return (a intersect b).size.toDouble() / union.size.toDouble()
  }

  private data class FindingEntry(
    val finding: ParallelReviewRawFinding,
    val agentId: String,
    val appearanceOrder: Int,
  )

  private data class MergedCandidate(
    val agentIds: List<String>,
    val severity: ParallelReviewSeverity,
    val confidence: String,
    val location: String,
    val description: String,
    val isCoalesced: Boolean,
    val firstAppearance: Int,
  )
}
