package skillbill.review

import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity

object ParallelReviewMerger {
  fun merge(lane1: ParallelReviewLaneResult, lane2: ParallelReviewLaneResult): ParallelReviewMergeResult {
    // Findings are the single source of truth: callers gate them on lane success, so a failed lane
    // contributes an empty list here and never leaks into the merged register.
    val allEntries = mutableListOf<FindingEntry>()
    var appearanceOrder = 0
    lane1.findings.forEach { f -> allEntries += FindingEntry(f, lane1.agentId, appearanceOrder++) }
    lane2.findings.forEach { f -> allEntries += FindingEntry(f, lane2.agentId, appearanceOrder++) }

    // Deterministic greedy single pass in insertion order (lane1 entries first, then lane2).
    // Each entry joins the first existing cluster whose first-inserted representative shares the
    // same file path AND clears the Jaccard token-overlap threshold; otherwise it opens a new
    // cluster. The representative's file path and tokens are cached in ClusterHead to avoid
    // O(N²) recomputation of tokens() on each probe.
    val clusters = mutableListOf<ClusterHead>()
    allEntries.forEach { entry ->
      val entryFilePath = filePathOf(entry.finding.location)
      val entryTokens = tokens(entry.finding.description)
      val cluster = clusters.firstOrNull { head ->
        head.representativeFilePath == entryFilePath &&
          jaccard(head.representativeTokens, entryTokens) > FUZZY_DEDUP_THRESHOLD
      }
      if (cluster != null) {
        cluster.entries += entry
      } else {
        clusters += ClusterHead(mutableListOf(entry), entryFilePath, entryTokens)
      }
    }

    val candidates = clusters.map(::toCandidate)

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

  private fun toCandidate(head: ClusterHead): MergedCandidate {
    val entries = head.entries
    val coalesced = entries.map { it.agentId }.distinct().size > 1
    // Severity and confidence travel together: both come from the most-severe assessment (ties
    // broken by earliest appearance) so the reported confidence describes the reported severity,
    // never a severity from one finding paired with the confidence of a lower-severity one.
    val primary = entries.minWith(
      compareBy({ it.finding.severity.ordinal }, { it.appearanceOrder }),
    )
    val firstEntry = entries.minByOrNull { it.appearanceOrder }!!
    return MergedCandidate(
      agentIds = entries.map { it.agentId }.distinct(),
      severity = primary.finding.severity,
      confidence = primary.finding.confidence,
      location = firstEntry.finding.location,
      description = firstEntry.finding.description,
      isCoalesced = coalesced,
      firstAppearance = firstEntry.appearanceOrder,
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

  // Splits a description into word tokens on any non-alphanumeric run. Hoisted to a constant so the
  // pattern is compiled once, not per pairwise comparison during clustering.
  private val TOKEN_DELIMITER = Regex("[^a-z0-9]+")

  // Word set of a description: lower-cased, split on any non-alphanumeric run, empties dropped.
  private fun tokens(description: String): Set<String> =
    description.lowercase().split(TOKEN_DELIMITER).filter { it.isNotEmpty() }.toSet()

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

  private data class ClusterHead(
    val entries: MutableList<FindingEntry>,
    val representativeFilePath: String,
    val representativeTokens: Set<String>,
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
