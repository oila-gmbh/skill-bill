package skillbill.infrastructure.fs

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import skillbill.ports.review.model.REVIEW_DISCOVERY_MAX_BYTES
import skillbill.ports.review.model.REVIEW_DISCOVERY_PAGE_SIZE
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceCatalogEntry
import skillbill.ports.review.model.ReviewEvidenceDiscoveryPage
import skillbill.ports.review.model.ReviewEvidenceDiscoveryRequest
import skillbill.ports.review.model.ReviewEvidenceOwner
import skillbill.review.context.model.ReviewEvidenceTarget
import skillbill.review.context.model.ReviewExpansionRecord
import java.util.UUID

internal class FileSystemReviewEvidenceCatalog(private val binding: ReviewEvidenceBrokerBinding) {
  private var generation = UUID.randomUUID().toString()
  private val cursors = mutableMapOf<String, Int>()
  private val delivered = mutableSetOf<String>()
  private val pending = mutableMapOf<String, Set<String>>()
  private val confirmed = mutableSetOf<String>()
  private val entries = linkedMapOf<String, ReviewEvidenceCatalogEntry>()

  init {
    binding.sources.forEach { source ->
      val assignment = source.assignment
      val commits = assignment.assignedBundle.entries
        .flatMap { unit -> unit.hunkIds.map { it to unit.commitSha } }.toMap()
      assignment.assignedHunks.filter { it in binding.visibleHunkIds }.forEach { id ->
        val hunk = binding.projectedHunks.single { it.hunkId == id }
        val selector = "hunk:${commits[id] ?: assignment.headRevision}:$id"
        val owner = ReviewEvidenceOwner(assignment.lane, assignment.digest, source.rubricId, selector)
        val previous = entries[selector]
        entries[selector] = ReviewEvidenceCatalogEntry(selector, hunk.path, previous?.owners.orEmpty() + owner)
      }
      val pathTargets = assignment.assignedPaths.filter { path ->
        path in binding.visibleTargetPaths &&
          binding.projectedHunks.none { it.path == path && it.hunkId in binding.visibleHunkIds } &&
          assignment.evidenceTargets.none { it.path == path }
      }.map { ReviewEvidenceTarget(it, it, emptyList()) }
      (
        assignment.evidenceTargets.filter {
          it.hunkIds.isEmpty() && it.path in binding.visibleTargetPaths
        } + pathTargets
        )
        .distinctBy { it.targetId }.forEach { target ->
          val selector = "target:${assignment.baseRevision}:${assignment.headRevision}:${target.path}"
          val owner = ReviewEvidenceOwner(
            assignment.lane,
            assignment.digest,
            source.rubricId,
            "target:${assignment.baseRevision}:${assignment.headRevision}:${target.targetId}",
          )
          val previous = entries[selector]
          entries[selector] = ReviewEvidenceCatalogEntry(selector, target.path, previous?.owners.orEmpty() + owner)
        }
    }
  }

  fun addExpansion(record: ReviewExpansionRecord, owner: ReviewEvidenceOwner) {
    val previous = entries[record.expansionId]
    if (previous != null && (
        previous.path != record.requestedPath ||
          previous.owners.any { it.assignmentDigest != owner.assignmentDigest }
        )
    ) {
      invalid("Expansion identity is shared by different source assignments.")
    }
    val updated = ReviewEvidenceCatalogEntry(
      record.expansionId,
      record.requestedPath,
      (previous?.owners.orEmpty() + owner).distinct(),
      record.expansionId,
    )
    if (updated != previous) {
      entries[record.expansionId] = updated
      generation = UUID.randomUUID().toString()
      cursors.clear()
    }
  }

  fun discover(request: ReviewEvidenceDiscoveryRequest): ReviewEvidenceDiscoveryPage {
    if (request.pageSize !in 1..REVIEW_DISCOVERY_PAGE_SIZE) invalid("Discovery page size is outside its limit.")
    val start = request.cursor?.let { cursors[it] ?: invalid("Foreign or stale discovery cursor.") } ?: 0
    val selected = entries.values.drop(start).take(request.pageSize)
    val next = (start + selected.size).takeIf { it < entries.size }?.let { offset ->
      val cursor = "$generation:$offset"
      cursors[cursor] = offset
      cursor
    }
    val page = ReviewEvidenceDiscoveryPage(binding.assignment.digest, selected, next)
    val pageBytes = JsonSupport.mapToJsonString(GovernedReviewEvidenceCodec.payload(page)).toByteArray().size
    if (pageBytes > REVIEW_DISCOVERY_MAX_BYTES) {
      invalid("Discovery page exceeds its metadata limit; request a smaller page.")
    }
    return page
  }

  fun entry(selector: String): ReviewEvidenceCatalogEntry? = entries[selector]

  fun alreadyDelivered(selector: String?, path: String): Boolean {
    if (selector != null) return selector in delivered
    val assigned = entries.values.filter { it.path == path && it.expansionId == null }
    return assigned.isNotEmpty() && assigned.all { it.selector in delivered }
  }

  fun receipt(selectors: Set<String>): String? {
    if (selectors.isEmpty()) return null
    val receipt = UUID.randomUUID().toString()
    pending[receipt] = selectors
    return receipt
  }

  fun confirm(receipt: String) {
    if (receipt in confirmed) return
    val selectors = pending.remove(receipt) ?: invalid("Unknown delivery receipt.")
    delivered += selectors
    confirmed += receipt
  }

  fun remaining(): List<ReviewEvidenceOwner> = entries.filterKeys { it !in delivered }.values.flatMap { it.owners }
  fun unconfirmedCount(): Int = pending.size
  fun requiredCount(): Int = entries.values.sumOf { it.owners.size }
  fun deliveredCount(): Int = entries.filterKeys { it in delivered }.values.sumOf { it.owners.size }
}

private fun invalid(reason: String): Nothing = throw InvalidReviewContextSchemaError("review-discovery", reason)
