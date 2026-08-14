package skillbill.review

import skillbill.review.context.model.structuredString
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewLaneFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment

object ParallelReviewMerger {
  /**
   * [integration] is the single integration pass's cross-commit register, merged through the same
   * clustering as the specialist lanes so an interaction both a lane and the integration pass
   * noticed still coalesces into one root-cause finding rather than being reported twice.
   */
  fun merge(
    lane1: ParallelReviewLaneResult,
    lane2: ParallelReviewLaneResult,
    integration: ParallelReviewLaneResult? = null,
  ): ParallelReviewMergeResult {
    // Findings are the single source of truth: callers gate them on lane success, so a failed lane
    // contributes an empty list here and never leaks into the merged register.
    val candidates = mergeCandidates(lane1, lane2, integration)

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
        specialistSkillNames = candidate.specialistSkillNames,
        originLayerChains = candidate.originLayerChains,
        repositoryPath = candidate.repositoryPath,
        line = candidate.line,
        commitShas = candidate.commitShas,
        claimVerdict = candidate.claimVerdict,
        scopeDisposition = candidate.scopeDisposition,
        citations = candidate.citations,
        severityAdjustment = candidate.severityAdjustment,
        sourceVerdicts = candidate.sourceVerdicts,
      )
    }

    return ParallelReviewMergeResult(
      findings = mergedFindings,
      formattedOutput = formattedOutput(mergedFindings),
    )
  }

  fun withRecordedVerdicts(
    result: ParallelReviewMergeResult,
    verdicts: List<ReviewFindingVerdict>,
  ): ParallelReviewMergeResult {
    if (verdicts.isEmpty()) return result
    val byRef = verdicts.groupBy(ReviewFindingVerdict::findingRef)
    val findings = result.findings.map { finding ->
      val overlay = ReviewFindingActionability.recordedFields(byRef[finding.fNumber].orEmpty())
        ?: return@map finding
      finding.copy(
        claimVerdict = overlay.claimVerdict,
        scopeDisposition = overlay.scopeDisposition,
        citations = overlay.citations,
        severityAdjustment = overlay.severityAdjustment,
      )
    }
    return ParallelReviewMergeResult(findings, formattedOutput(findings))
  }

  fun formattedOutput(findings: List<ParallelReviewMergedFinding>): String {
    if (findings.none(ParallelReviewMergedFinding::hasRecordedVerdict)) {
      return findings.joinToString("\n", transform = ::formatFinding)
    }
    val grouped = findings.groupBy { finding ->
      ReviewFindingActionability.registerOutcome(finding.claimVerdict, finding.scopeDisposition)
    }
    return buildString {
      var first = true
      ReviewFindingRegisterOutcome.entries.forEach { outcome ->
        val items = grouped[outcome].orEmpty()
        if (items.isEmpty()) return@forEach
        if (!first) append('\n')
        first = false
        append(outcome.header)
        append('\n')
        append(items.joinToString("\n", transform = ::formatFinding))
      }
    }
  }

  private fun mergeCandidates(
    lane1: ParallelReviewLaneResult,
    lane2: ParallelReviewLaneResult,
    integration: ParallelReviewLaneResult?,
  ): List<MergedCandidate> {
    val allEntries = mutableListOf<FindingEntry>()
    var appearanceOrder = 0
    lane1.findings.forEach { f -> allEntries += FindingEntry(f, lane1.agentId, appearanceOrder++) }
    lane2.findings.forEach { f -> allEntries += FindingEntry(f, lane2.agentId, appearanceOrder++) }
    // Last, so a specialist lane that saw the same root cause stays the cluster representative.
    integration?.findings?.forEach { f -> allEntries += FindingEntry(f, integration.agentId, appearanceOrder++) }

    // Deterministic greedy single pass in insertion order (lane1 entries first, then lane2).
    // Each entry joins the first existing cluster whose first-inserted representative shares the
    // same file path AND clears the Jaccard token-overlap threshold; otherwise it opens a new
    // cluster. The representative's file path and tokens are cached in ClusterHead to avoid
    // O(N²) recomputation of tokens() on each probe.
    val clusters = mutableListOf<ClusterHead>()
    allEntries.forEach { entry ->
      val entryFilePath = entry.finding.repositoryPath ?: filePathOf(entry.finding.location)
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

    return clusters.map(::toCandidate)
  }

  private fun formatFinding(finding: ParallelReviewMergedFinding): String {
    val agentLabel = finding.agentIds.joinToString(", ")
    val provenance = buildList {
      if (finding.specialistSkillNames.isNotEmpty()) {
        add("specialists=${finding.specialistSkillNames.joinToString(",")}")
      }
      if (finding.originLayerChains.isNotEmpty()) {
        add("origins=${finding.originLayerChains.joinToString(",") { it.joinToString("->") }}")
      }
    }.joinToString("; ").let { if (it.isBlank()) "" else " | $it" }
    val structuredLocation = if (finding.repositoryPath != null && finding.line != null) {
      "path=${structuredString(finding.repositoryPath)} | line=${finding.line}"
    } else {
      finding.location
    }
    val commitAttribution = if (finding.commitShas.isNotEmpty()) {
      "commits=${finding.commitShas.joinToString(",")} | "
    } else {
      ""
    }
    val claimLine = "- [${finding.fNumber}] [$agentLabel] ${finding.severity.displayName} | ${finding.confidence} | " +
      "$commitAttribution$structuredLocation | ${finding.description}$provenance"
    val structuredFields = buildList {
      finding.claimVerdict?.let { add("claim_verdict=${it.wireValue}") }
      finding.scopeDisposition?.let { add("scope_disposition=${it.wireValue}") }
      if (finding.citations.isNotEmpty()) {
        add("citations=${finding.citations.joinToString(",") { "${it.path}:${it.line}" }}")
      }
      finding.severityAdjustment?.let { adjustment ->
        add("severity_adjustment=${adjustment.direction.wireValue}: ${adjustment.justification}")
      }
    }
    return if (structuredFields.isEmpty()) claimLine else "$claimLine | ${structuredFields.joinToString(" | ")}"
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
    val sourceVerdicts = entries.mapNotNull { entry ->
      val finding = entry.finding
      if (
        finding.claimVerdict == null &&
        finding.scopeDisposition == null &&
        finding.severityAdjustment == null &&
        finding.citations.isEmpty()
      ) {
        null
      } else {
        ReviewLaneFindingVerdict(
          laneId = entry.agentId,
          claimVerdict = finding.claimVerdict,
          scopeDisposition = finding.scopeDisposition,
          citations = finding.citations,
          severityAdjustment = finding.severityAdjustment,
        )
      }
    }
    val claimVerdict = sourceVerdicts.map { it.claimVerdict }.reduceOrNull(
      ReviewFindingActionability::conservativeClaimVerdict,
    )
    val scopeDisposition = sourceVerdicts.map { it.scopeDisposition }.reduceOrNull(
      ReviewFindingActionability::conservativeScopeDisposition,
    )
    return MergedCandidate(
      agentIds = entries.map { it.agentId }.distinct(),
      severity = primary.finding.severity,
      confidence = primary.finding.confidence,
      location = firstEntry.finding.location,
      description = firstEntry.finding.description,
      isCoalesced = coalesced,
      firstAppearance = firstEntry.appearanceOrder,
      specialistSkillNames = entries.mapNotNull { it.finding.specialistSkillName }.distinct(),
      originLayerChains = entries.flatMap { it.finding.originLayerChains }.distinct(),
      repositoryPath = firstEntry.finding.repositoryPath,
      line = firstEntry.finding.line,
      commitShas = entries.sortedBy { it.appearanceOrder }.flatMap { it.finding.commitShas }.distinct(),
      claimVerdict = claimVerdict,
      scopeDisposition = scopeDisposition,
      citations = sourceVerdicts.flatMap { it.citations }.distinct(),
      severityAdjustment = sourceVerdicts.mapNotNull { it.severityAdjustment }.firstOrNull(),
      sourceVerdicts = sourceVerdicts,
    )
  }

  // Jaccard token-overlap floor for coalescing two same-file findings. Comparison is strict `>`:
  // a pair coalesces only when its overlap ratio is strictly above this value. Update here to
  // retune fuzzy dedup sensitivity — no other code depends on the literal.
  private const val FUZZY_DEDUP_THRESHOLD = 0.6

  // File-path portion of a location field ("file:line" -> "file"). Kotlin's substringBeforeLast
  // returns the whole string when there is no colon, so colon-less locations fall back to
  // themselves. Repository path identity is intentionally case-sensitive.
  private fun filePathOf(location: String): String = location.substringBeforeLast(":").trim()

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
    val specialistSkillNames: List<String>,
    val originLayerChains: List<List<String>>,
    val repositoryPath: String?,
    val line: Int?,
    val commitShas: List<String>,
    val claimVerdict: ReviewClaimVerdict?,
    val scopeDisposition: ReviewScopeDisposition?,
    val citations: List<ReviewFindingCitation>,
    val severityAdjustment: ReviewSeverityAdjustment?,
    val sourceVerdicts: List<ReviewLaneFindingVerdict>,
  )
}
