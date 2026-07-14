package skillbill.infrastructure.fs

import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.ReviewEvidenceRequest
import skillbill.ports.review.ReviewEvidenceResult
import skillbill.review.context.REVIEW_CONTEXT_BUDGET_EXCEEDED
import skillbill.review.context.ReviewAssignment
import skillbill.review.context.ReviewContextBudgetExceeded
import skillbill.review.context.ReviewContextBudgetPolicy
import skillbill.review.context.requireRepositoryRelativePath
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class FileSystemReviewEvidenceBroker(
  repoRoot: Path,
  private val assignment: ReviewAssignment,
  private val namedDependencies: Set<String>,
  private val budget: ReviewContextBudgetPolicy,
) : ReviewEvidenceBroker {
  private val root = repoRoot.toRealPath()
  private var cumulativeBytes = 0L
  private val expansions = linkedSetOf<String>()
  private var terminalOutcome: ReviewContextBudgetExceeded? = null

  override fun read(request: ReviewEvidenceRequest): ReviewEvidenceResult {
    terminalOutcome?.let { return terminalResult(it) }
    require(request.lane == assignment.lane) { "Evidence lane does not own this assignment." }
    requireRepositoryRelativePath(request.path)
    val normalized = request.path.replace('\\', '/')
    val assigned = normalized in assignment.assignedPaths || normalized in namedDependencies
    if (!assigned) {
      require(!request.reachabilityReason.isNullOrBlank()) { "Out-of-assignment evidence requires a reachability reason." }
      expansions += normalized
      if (expansions.size > budget.maxAssignmentExpansions) return exceeded("assignment_expansions", budget.maxAssignmentExpansions.toLong(), expansions.size.toLong())
    }
    val candidate = root.resolve(normalized).normalize()
    require(candidate.startsWith(root)) { "Evidence path escapes the repository." }
    val real = candidate.toRealPath()
    require(real.startsWith(root) && Files.isRegularFile(real)) { "Evidence path must be a repository file." }
    val content = Files.readString(real, StandardCharsets.UTF_8)
    val bytes = content.toByteArray(StandardCharsets.UTF_8).size.toLong()
    if (bytes > budget.maxEvidenceResultBytes) return exceeded("evidence_result_bytes", budget.maxEvidenceResultBytes, bytes)
    val observedCumulative = cumulativeBytes + bytes
    if (observedCumulative > budget.maxLaneEvidenceBytes) return exceeded("lane_evidence_bytes", budget.maxLaneEvidenceBytes, observedCumulative)
    cumulativeBytes = observedCumulative
    return ReviewEvidenceResult(content, bytes, cumulativeBytes, expansions.size)
  }

  private fun exceeded(kind: String, limit: Long, observed: Long): ReviewEvidenceResult = ReviewEvidenceResult(
    content = null,
    bytes = 0,
    cumulativeBytes = cumulativeBytes,
    expansionCount = expansions.size,
    budgetExceeded = ReviewContextBudgetExceeded(assignment.lane, kind, limit, observed, assignment.packetDigest, assignment.digest, true),
  ).also {
    check(it.budgetExceeded?.type == REVIEW_CONTEXT_BUDGET_EXCEEDED)
    terminalOutcome = it.budgetExceeded
  }

  fun validateLaneResult(result: String): ReviewContextBudgetExceeded? {
    terminalOutcome?.let { return it }
    val observed = result.toByteArray(StandardCharsets.UTF_8).size.toLong()
    return if (observed > budget.maxLaneResultBytes) {
      exceeded("lane_result_bytes", budget.maxLaneResultBytes, observed).budgetExceeded
    } else {
      null
    }
  }

  private fun terminalResult(outcome: ReviewContextBudgetExceeded): ReviewEvidenceResult = ReviewEvidenceResult(
    content = null,
    bytes = 0,
    cumulativeBytes = cumulativeBytes,
    expansionCount = expansions.size,
    budgetExceeded = outcome,
  )
}
