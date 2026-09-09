package skillbill.infrastructure.fs

import skillbill.error.ReviewHunkEvidenceIntegrityError
import skillbill.error.ReviewHunkEvidenceLocatorMissingError
import skillbill.ports.review.ReviewStoredHunkBodyExtractor
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceLocatorReadRequest
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewLaneIdentity
import skillbill.review.context.model.ReviewOperationKind
import skillbill.review.context.model.ReviewOperationPolicy
import skillbill.review.context.model.ReviewRequestedOperation
import skillbill.review.context.model.requireRepositoryRelativePath
import java.nio.charset.StandardCharsets
import java.nio.file.Path

internal class FileSystemReviewEvidenceBrokerReads(
  private val state: FileSystemReviewEvidenceBrokerReadState,
) {
  fun readOne(request: ReviewEvidenceRequest, assignedDelta: Boolean = false): ReviewEvidenceResult =
    readOneEvidence(state, request, assignedDelta)
}

internal class FileSystemReviewEvidenceBrokerReadState(
  init: FileSystemReviewEvidenceBrokerReadStateInit,
) {
  val root: Path = init.root
  val assignment: ReviewAssignment = init.assignment
  val budget: ReviewContextBudgetPolicy = init.budget
  val identity: ReviewLaneIdentity = init.identity
  val policy: ReviewOperationPolicy = init.policy
  val authorizedExpansionLedger: List<ReviewExpansionRecord> = init.authorizedExpansionLedger
  val projectedHunks: List<ReviewChangedHunk> = init.projectedHunks
  val visibleTargetPaths: Set<String> = init.visibleTargetPaths
  val locatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort = init.locatorReader
  val bodyExtractor: ReviewStoredHunkBodyExtractor = init.bodyExtractor
  val hunkCommitById: Map<String, String> = init.hunkCommitById
  val expansionCoordinates = init.expansionCoordinates
  var cumulativeBytes: Long = 0L
  var authorizedReadCount: Int = 0
  var terminalOutcome: ReviewBudgetOutcome? = null
  val expansionLedger = mutableListOf<ReviewExpansionRecord>()
  val deniedUnits = mutableListOf<String>()
}

private fun readOneEvidence(
  state: FileSystemReviewEvidenceBrokerReadState,
  request: ReviewEvidenceRequest,
  assignedDelta: Boolean,
): ReviewEvidenceResult {
  val exactPath = request.path
  if (!exactPath.startsWith('/') && !exactPath.startsWith('\\')) {
    requireRepositoryRelativePath(exactPath)
  }
  val operation = ReviewRequestedOperation(ReviewOperationKind.FILE_READ, exactPath, request.reachabilityReason)
  if (!assignedDelta) state.policy.classify(operation)?.let { return refusedEvidence(state, it) }
  requireRepositoryRelativePath(exactPath)
  val assigned = state.policy.isAssigned(exactPath)
  val expansion = request.authorizedExpansion
  if (!assigned || expansion != null) {
    val expansion = requireNotNull(request.authorizedExpansion) {
      "Unassigned evidence requires an authorized expansion record."
    }
    require(expansion.authorized) { "Expansion '${expansion.expansionId}' is not authorized." }
    require(expansion.assignmentDigest == state.assignment.digest) {
      "Expansion '${expansion.expansionId}' does not belong to this assignment."
    }
    require(expansion.requestedPath == exactPath) {
      "Expansion '${expansion.expansionId}' does not authorize '$exactPath'."
    }
    require(expansion.reachabilityReason == request.reachabilityReason) {
      "Expansion '${expansion.expansionId}' reason provenance changed before admission."
    }
    require(expansion in state.authorizedExpansionLedger) {
      "Expansion '${expansion.expansionId}' was not authorized by this assignment's measured broker."
    }
    if (expansion !in state.expansionLedger) state.expansionLedger += expansion
    if (state.expansionLedger.size > state.budget.maxAssignmentExpansions) {
      return exceededEvidence(
        state,
        "assignment_expansions",
        state.budget.maxAssignmentExpansions.toLong(),
        state.expansionLedger.size.toLong(),
      )
    }
  }
  return readAdmittedFile(state, request, assigned)
    .let { result ->
      if (expansion != null && result.hasDeliveredContent()) {
        result.copy(deliveredSelectors = listOf(expansion.expansionId))
      } else {
        result
      }
    }
}

private fun readAdmittedFile(
  state: FileSystemReviewEvidenceBrokerReadState,
  request: ReviewEvidenceRequest,
  assigned: Boolean,
): ReviewEvidenceResult {
  state.authorizedReadCount += 1
  return if (assigned && request.authorizedExpansion == null) {
    readProjectedHunks(state, request.path, request.selector)
  } else {
    readCompleteFile(state, request.path, request.authorizedExpansion?.expansionId)
  }
}

private fun readProjectedHunks(
  state: FileSystemReviewEvidenceBrokerReadState,
  path: String,
  selector: String?,
): ReviewEvidenceResult {
  val hunks = state.projectedHunks
    .filter {
      val hunkSelector = "hunk:${commitShaForHunk(state, it.hunkId)}:${it.hunkId}"
      it.path == path && (selector == null || selector == hunkSelector)
    }
    .sortedWith(compareBy({ it.newStart }, { it.oldStart }, { it.hunkId }))
  if (hunks.isEmpty()) {
    if (selector != null || path !in state.visibleTargetPaths) {
      return unavailableEvidence(state, path)
    }
    return readUnprojectedDelta(state, path, selector)
  }
  val delivered = mutableListOf<String>()
  val deliveredSelectors = mutableListOf<String>()
  for (hunk in hunks) {
    val body = materializeAssignedHunk(state, hunk)
    val bytes = body.toByteArray(StandardCharsets.UTF_8).size.toLong()
    val resultBytes = delivered.sumOf { it.toByteArray(StandardCharsets.UTF_8).size.toLong() } + bytes + delivered.size
    val resultOverflow = assignedHunkBudgetOutcome(
      state,
      bytes,
      unitForHunk(
        state,
        hunk,
      ),
    ) ?: if (resultBytes > state.budget.maxEvidenceResultBytes) {
      exceededEvidence(state, "evidence_result_bytes", state.budget.maxEvidenceResultBytes, resultBytes)
    } else {
      null
    }
    resultOverflow?.let { exceeded ->
      return if (delivered.isEmpty()) {
        exceeded
      } else {
        val content = delivered.joinToString("\n")
        ReviewEvidenceResult(
          content,
          content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
          state.cumulativeBytes,
          state.expansionLedger.size,
          budgetExceeded = exceeded.budgetExceeded,
          deliveredSelectors = deliveredSelectors.toList(),
        )
      }
    }
    state.cumulativeBytes += bytes
    delivered += body
    deliveredSelectors += "hunk:${commitShaForHunk(state, hunk.hunkId)}:${hunk.hunkId}"
  }
  val content = delivered.joinToString("\n")
  return ReviewEvidenceResult(
    content,
    content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
    state.cumulativeBytes,
    state.expansionLedger.size,
    deliveredSelectors = deliveredSelectors.toList(),
  )
}

private fun materializeAssignedHunk(state: FileSystemReviewEvidenceBrokerReadState, hunk: ReviewChangedHunk): String {
  val locator = hunk.evidenceLocator
  val body = if (state.locatorReader !== FeatureTaskRuntimeSharedEvidenceLocatorReadPort.NONE) {
    val payload = state.locatorReader.readDiffPayload(
      FeatureTaskRuntimeSharedEvidenceLocatorReadRequest(state.root, locator.storePath, locator.payloadFile),
    )
    state.bodyExtractor.extract(payload, hunk)
  } else {
    val fallback = hunk.content.replace("\r\n", "\n")
    if (fallback.isEmpty()) throw ReviewHunkEvidenceLocatorMissingError(locator.storePath)
    fallback
  }
  val normalized = body.replace("\r\n", "\n")
  val observed = ReviewChangedHunk.digestOfBody(normalized)
  if (observed != hunk.contentDigest) {
    throw ReviewHunkEvidenceIntegrityError(locator.storePath, hunk.contentDigest, observed)
  }
  return normalized
}

private fun readCompleteFile(
  state: FileSystemReviewEvidenceBrokerReadState,
  path: String,
  expansionId: String?,
): ReviewEvidenceResult {
  val coordinates = requireNotNull(state.expansionCoordinates[expansionId]) {
    "Whole-file evidence requires bound source coordinates."
  }
  val contentBytes = readReviewCoordinateFile(state, coordinates, path)
    ?: return unavailableEvidence(state, path)
  return serveEvidence(state, path, contentBytes)
}

private fun serveEvidence(
  state: FileSystemReviewEvidenceBrokerReadState,
  path: String,
  contentBytes: ByteArray,
): ReviewEvidenceResult {
  val bytes = contentBytes.size.toLong()
  evidenceBudgetOutcome(state, bytes, unitAtPath(state, path))?.let { return it }
  state.cumulativeBytes += bytes
  return ReviewEvidenceResult(
    contentBytes.toString(StandardCharsets.UTF_8),
    bytes,
    state.cumulativeBytes,
    state.expansionLedger.size,
  )
}

private fun refusedEvidence(
  state: FileSystemReviewEvidenceBrokerReadState,
  forbidden: ForbiddenReviewOperation,
): ReviewEvidenceResult = forbiddenResult(forbidden, state.cumulativeBytes, state.expansionLedger.size)

private fun ReviewEvidenceResult.hasDeliveredContent(): Boolean =
  content != null && budgetExceeded == null && forbidden == null

private fun readUnprojectedDelta(
  state: FileSystemReviewEvidenceBrokerReadState,
  path: String,
  selector: String?,
): ReviewEvidenceResult {
  val content = readImmutableReviewDelta(
    state.root,
    state.assignment.baseRevision,
    state.assignment.headRevision,
    path,
    state.budget.maxEvidenceResultBytes,

  ) ?: return unavailableEvidence(state, path)
  return serveEvidence(state, path, content).let { result ->
    if (result.content == null) {
      result
    } else {
      result.copy(
        deliveredSelectors = listOf(
          selector ?: "target:${state.assignment.baseRevision}:${state.assignment.headRevision}:$path",
        ),
      )
    }
  }
}
