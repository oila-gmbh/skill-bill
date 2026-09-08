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
import java.nio.file.Files
import java.nio.file.Path

internal class FileSystemReviewEvidenceBrokerReads(
  private val state: FileSystemReviewEvidenceBrokerReadState,
) {
  fun readOne(request: ReviewEvidenceRequest): ReviewEvidenceResult = readOneEvidence(state, request)
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
  val locatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort = init.locatorReader
  val bodyExtractor: ReviewStoredHunkBodyExtractor = init.bodyExtractor
  val completeFileCheckpoint: Map<String, String?> = init.completeFileCheckpoint
  val hunkCommitById: Map<String, String> = init.hunkCommitById
  var cumulativeBytes: Long = 0L
  var authorizedReadCount: Int = 0
  var terminalOutcome: ReviewBudgetOutcome? = null
  val expansionLedger = mutableListOf<ReviewExpansionRecord>()
  val admittedEvidenceTargets = mutableSetOf<String>()
  val deniedUnits = mutableListOf<String>()
}

private fun readOneEvidence(
  state: FileSystemReviewEvidenceBrokerReadState,
  request: ReviewEvidenceRequest,
): ReviewEvidenceResult {
  val exactPath = request.path
  if (!exactPath.startsWith('/') && !exactPath.startsWith('\\')) {
    requireRepositoryRelativePath(exactPath)
  }
  val operation = ReviewRequestedOperation(ReviewOperationKind.FILE_READ, exactPath, request.reachabilityReason)
  state.policy.classify(operation)?.let { return refusedEvidence(state, it) }
  requireRepositoryRelativePath(exactPath)
  val normalizedTarget = normalizeEvidenceIdentity(exactPath)
  if (!state.admittedEvidenceTargets.add(normalizedTarget)) {
    return refusedEvidence(
      state,
      ForbiddenReviewOperation(
        "repeated_evidence_read",
        exactPath,
        "The normalized evidence target was already read by this lane.",
      ),
    )
  }
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
    require(expansion !in state.expansionLedger) { "Expansion '${expansion.expansionId}' was already admitted." }
    require(expansion in state.authorizedExpansionLedger) {
      "Expansion '${expansion.expansionId}' was not authorized by this assignment's measured broker."
    }
    state.expansionLedger += expansion
    if (state.expansionLedger.size > state.budget.maxAssignmentExpansions) {
      return exceededEvidence(
        state,
        "assignment_expansions",
        state.budget.maxAssignmentExpansions.toLong(),
        state.expansionLedger.size.toLong(),
      )
    }
  }
  return readAdmittedFile(state, exactPath, assigned, expansion != null)
}

private fun readAdmittedFile(
  state: FileSystemReviewEvidenceBrokerReadState,
  normalized: String,
  assigned: Boolean,
  completeFileAuthorized: Boolean,
): ReviewEvidenceResult {
  state.authorizedReadCount += 1
  return if (assigned && !completeFileAuthorized) {
    readProjectedHunks(state, normalized)
  } else {
    readCompleteFile(state, normalized, assigned)
  }
}

private fun readProjectedHunks(state: FileSystemReviewEvidenceBrokerReadState, path: String): ReviewEvidenceResult {
  val hunks = state.projectedHunks
    .filter { it.path == path }
    .sortedWith(compareBy({ it.newStart }, { it.oldStart }, { it.hunkId }))
  val delivered = mutableListOf<String>()
  for (hunk in hunks) {
    val body = materializeAssignedHunk(state, hunk)
    val bytes = body.toByteArray(StandardCharsets.UTF_8).size.toLong()
    assignedHunkBudgetOutcome(state, bytes, unitForHunk(state, hunk))?.let { exceeded ->
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
        )
      }
    }
    state.cumulativeBytes += bytes
    delivered += body
  }
  val content = delivered.joinToString("\n")
  return ReviewEvidenceResult(
    content,
    content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
    state.cumulativeBytes,
    state.expansionLedger.size,
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
  assigned: Boolean,
): ReviewEvidenceResult {
  val real = resolveRepositoryFile(state.root, path)
  val expectedDigest = state.completeFileCheckpoint.getValue(path)
  if (real == null) {
    if (expectedDigest != null) rejectCheckpointDrift(state, path)
    require(assigned) { "Expanded evidence path must be a repository file." }
    return unavailableResult(state.cumulativeBytes, state.expansionLedger.size)
  }
  val contentBytes = Files.readAllBytes(real)
  if (expectedDigest == null || digest(contentBytes) != expectedDigest) {
    rejectCheckpointDrift(state, path)
  }
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
