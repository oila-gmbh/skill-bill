package skillbill.infrastructure.fs
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceCoordinates
import skillbill.ports.review.model.ReviewEvidenceOwner
import skillbill.ports.review.model.ReviewRefusedOperationRecord
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewLaneIdentity
import skillbill.review.context.model.ReviewOperationKind
import skillbill.review.context.model.ReviewOperationPolicy
import skillbill.review.context.model.ReviewRequestedOperation
import java.nio.file.Path
internal class FileSystemReviewEvidenceBrokerContext(binding: ReviewEvidenceBrokerBinding) {
  val sources = binding.sources
  val catalog = FileSystemReviewEvidenceCatalog(binding)
  var evidenceRequests = 0
  val root: Path = binding.repoRoot.toRealPath()
  val assignment = binding.assignment
  val budget = binding.budget
  val identity = ReviewLaneIdentity.of(assignment)
  val policy = ReviewOperationPolicy(assignment, binding.laneRubricId, binding.namedDependencies)
  val authorizedExpansionLedger = mutableListOf<ReviewExpansionRecord>()
  val expansionCoordinates = mutableMapOf<String, ReviewEvidenceCoordinates>()
  val projectedHunks = binding.projectedHunks.filter { it.hunkId in binding.visibleHunkIds }
  val hunkCommitById = assignment.assignedBundle.entries
    .flatMap { entry -> entry.hunkIds.map { it to entry.commitSha } }
    .toMap()
  val readState = FileSystemReviewEvidenceBrokerReadState(
    FileSystemReviewEvidenceBrokerReadStateInit(
      root = root,
      assignment = assignment,
      budget = budget,
      identity = identity,
      policy = policy,
      authorizedExpansionLedger = authorizedExpansionLedger,
      projectedHunks = projectedHunks,
      visibleTargetPaths = binding.visibleTargetPaths,
      locatorReader = binding.locatorReader,
      bodyExtractor = binding.bodyExtractor,
      hunkCommitById = hunkCommitById,
      expansionCoordinates = expansionCoordinates,
    ),
  )
  val reads = FileSystemReviewEvidenceBrokerReads(readState)
  var refusedOperationCount = 0
  var resultBytes = 0L
  var laneResultObserved = false
  var toolCalls = 0
  var modelTurns = 0
  val refusalLedger = mutableListOf<ReviewRefusedOperationRecord>()
  init {
    val admitted = assignment.assignedPaths + assignment.dependencyAllowlist.normalized +
      assignment.evidenceTargets.map { it.path } + assignment.expansions.map { it.requestedPath }
    admitted.distinct().forEach { validateRepositoryMapping(root, it) }
    sources.forEach { source ->
      (
        source.assignment.expansions +
          binding.trustedExpansionLedger.filter {
            it.assignmentDigest == source.assignment.digest
          }
        ).distinctBy { it.expansionId }.forEach { record ->
        require(
          record.authorized && ReviewOperationPolicy(
            source.assignment,
            source.rubricId,
            source.namedDependencies,
          ).isReachable(record.requestedPath),
        ) { "Trusted expansion exceeds its source assignment." }
        require(
          ReviewOperationPolicy(source.assignment, source.rubricId, source.namedDependencies).classify(
            ReviewRequestedOperation(ReviewOperationKind.FILE_READ, record.requestedPath, record.reachabilityReason),
          ) == null,
        ) { "Trusted expansion exceeds permitted whole-file evidence." }
        validateRepositoryMapping(root, record.requestedPath)
        val coordinates = bindReviewCoordinates(root, source.coordinates)
        validateReviewCoordinateFile(readState, coordinates, record.requestedPath)
        val sequence = if (source.assignment.digest == assignment.digest) {
          record.sequence
        } else {
          authorizedExpansionLedger.size
        }
        val bound = record.copy(assignmentDigest = assignment.digest, sequence = sequence)
        if (authorizedExpansionLedger.none { it.expansionId == bound.expansionId }) {
          admitExpansionMetadata(bound)
          authorizedExpansionLedger += bound
        }
        expansionCoordinates[bound.expansionId] = coordinates
        catalog.addExpansion(
          bound,
          ReviewEvidenceOwner(
            source.assignment.lane,
            source.assignment.digest,
            source.rubricId,
            bound.expansionId,
          ),
        )
      }
    }
  }
}
