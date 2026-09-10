package skillbill.infrastructure.fs
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.ports.review.model.ReviewEvidenceOwner
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.review.context.model.ReviewEvidenceLimits
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewOperationKind
import skillbill.review.context.model.ReviewOperationPolicy
import skillbill.review.context.model.ReviewRequestedOperation
import skillbill.review.context.model.requireRepositoryRelativePath
internal fun FileSystemReviewEvidenceBrokerContext.authorizeMeasuredExpansion(
  request: ReviewExpansionAuthorizationRequest,
): ReviewExpansionRecord {
  val source = sources.distinctBy { it.assignment.digest }.singleOrNull { it.assignment.lane == request.lane }
    ?: throw IllegalArgumentException("Expansion lane does not own a source assignment.")
  require(readState.terminalOutcome == null) { "Evidence request budget is exhausted." }
  require(request.reachabilityReason.isNotBlank()) { "Expansion reachability reason must not be blank." }
  requireRepositoryRelativePath(request.path)
  val sourcePolicy = ReviewOperationPolicy(source.assignment, source.rubricId, source.namedDependencies)
  require(
    sourcePolicy.isReachable(request.path) && sourcePolicy.classify(
      ReviewRequestedOperation(ReviewOperationKind.FILE_READ, request.path, request.reachabilityReason),
    ) == null,
  ) { "Expansion path is outside the permitted whole-file evidence surface." }
  validateRepositoryMapping(root, request.path)
  val existing = authorizedExpansionLedger.firstOrNull {
    it.requestedPath == request.path && it.reachabilityReason == request.reachabilityReason &&
      catalog.entry(
        it.expansionId,
      )?.owners?.any { owner -> owner.assignmentDigest == source.assignment.digest } == true
  }
  val coordinates = existing?.let { expansionCoordinates[it.expansionId] }
    ?: bindReviewCoordinates(root, source.coordinates)
  validateReviewCoordinateFile(readState, coordinates, request.path)
  if (existing != null) return existing
  val sequence = (authorizedExpansionLedger.maxOfOrNull { it.sequence } ?: -1) + 1
  val expansion = ReviewExpansionRecord(
    expansionId = stableReviewExpansionId(source.assignment.digest, request.path, request.reachabilityReason),
    assignmentDigest = assignment.digest,
    requestedPath = request.path,
    reachabilityReason = request.reachabilityReason,
    authorized = true,
    sequence = sequence,
  )
  admitExpansionMetadata(expansion)
  expansionCoordinates[expansion.expansionId] = coordinates
  authorizedExpansionLedger += expansion
  sources.filter { it.assignment.digest == source.assignment.digest }.forEach { owner ->
    catalog.addExpansion(
      expansion,
      ReviewEvidenceOwner(
        owner.assignment.lane,
        owner.assignment.digest,
        owner.rubricId,
        expansion.expansionId,
      ),
    )
  }
  return expansion
}

internal fun FileSystemReviewEvidenceBrokerContext.admitExpansionMetadata(record: ReviewExpansionRecord) {
  val observed = expansionLedgerBytes(authorizedExpansionLedger + record)
  if (observed > ReviewEvidenceLimits.EXPANSION_LEDGER_BYTES) {
    exceededEvidence(
      readState,
      "expansion_metadata_bytes",
      ReviewEvidenceLimits.EXPANSION_LEDGER_BYTES.toLong(),
      observed.toLong(),
    )
    throw InvalidReviewContextSchemaError("review-expansion", "Expansion metadata limit exceeded.")
  }
  if (authorizedExpansionLedger.size >= budget.maxAssignmentExpansions) {
    exceededEvidence(
      readState,
      "assignment_expansions",
      budget.maxAssignmentExpansions.toLong(),
      authorizedExpansionLedger.size.toLong() + 1,
    )
    throw InvalidReviewContextSchemaError("review-expansion", "Expansion authorization limit exceeded.")
  }
}
