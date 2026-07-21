package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetEvaluator
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewLaneIdentity
import skillbill.review.context.model.ReviewOperationKind
import skillbill.review.context.model.ReviewOperationPolicy
import skillbill.review.context.model.ReviewRequestedOperation
import skillbill.review.context.model.requireRepositoryRelativePath
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemReviewEvidenceBrokerFactory : ReviewEvidenceBrokerFactory {
  override fun brokerFor(binding: ReviewEvidenceBrokerBinding): ReviewEvidenceBroker =
    FileSystemReviewEvidenceBroker(binding)
}

@Suppress("TooManyFunctions")
class FileSystemReviewEvidenceBroker(binding: ReviewEvidenceBrokerBinding) : ReviewEvidenceBroker {
  private val root: Path = binding.repoRoot.toRealPath()
  private val assignment = binding.assignment
  private val budget = binding.budget
  private val identity = ReviewLaneIdentity.of(assignment)
  private val policy = ReviewOperationPolicy(assignment, binding.laneRubricId, binding.namedDependencies)

  private var cumulativeBytes = 0L
  private var resultBytes = 0L
  private var toolCalls = 0
  private var modelTurns = 0
  private val expansionLedger = mutableListOf<ReviewExpansionRecord>()
  private var terminalOutcome: ReviewBudgetOutcome? = null

  override fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult {
    require(request.lane == assignment.lane) { "Evidence lane does not own this assignment." }
    terminalOutcome?.let { outcome ->
      val terminated = request.requests.map {
        terminalResult(outcome, cumulativeBytes, expansionLedger.size)
      }
      return batchResult(terminated, outcome)
    }

    val results = mutableListOf<ReviewEvidenceResult>()
    for (evidenceRequest in request.requests) {
      val result = readOne(evidenceRequest)
      results += result
      val exceeded = result.budgetExceeded
      if (exceeded != null) {
        // A terminated lane serves no further evidence; the remaining batch entries report the
        // same terminal outcome rather than being silently dropped.
        val served = results.size
        request.requests.drop(served).forEach {
          results += terminalResult(exceeded, cumulativeBytes, expansionLedger.size)
        }
        break
      }
    }
    return batchResult(results, terminalOutcome)
  }

  private fun readOne(request: ReviewEvidenceRequest): ReviewEvidenceResult {
    requireRepositoryRelativePath(request.path)
    val normalized = request.path.replace('\\', '/')
    val operation = ReviewRequestedOperation(ReviewOperationKind.FILE_READ, normalized, request.reachabilityReason)
    policy.classify(operation)?.let { return forbiddenResult(it, cumulativeBytes, expansionLedger.size) }

    if (!policy.isAssigned(normalized)) {
      val expansion = requireNotNull(request.authorizedExpansion) {
        "Unassigned evidence requires an authorized expansion record."
      }
      require(expansion.authorized) { "Expansion '${expansion.expansionId}' is not authorized." }
      require(expansion.assignmentDigest == assignment.digest) {
        "Expansion '${expansion.expansionId}' does not belong to this assignment."
      }
      require(expansion.requestedPath.replace('\\', '/') == normalized) {
        "Expansion '${expansion.expansionId}' does not authorize '$normalized'."
      }
      require(expansion.reachabilityReason == request.reachabilityReason) {
        "Expansion '${expansion.expansionId}' reason provenance changed before admission."
      }
      require(expansion.sequence == expansionLedger.size) {
        "Expansion '${expansion.expansionId}' is out of sequence."
      }
      expansionLedger += expansion
      if (expansionLedger.size > budget.maxAssignmentExpansions) {
        return exceeded("assignment_expansions", budget.maxAssignmentExpansions.toLong(), expansionLedger.size.toLong())
      }
    }

    val real = resolveRepositoryFile(root, normalized)
    val bytes = Files.size(real)
    evidenceBudgetOutcome(bytes)?.let { return it }
    val content = Files.readString(real, StandardCharsets.UTF_8)
    cumulativeBytes += bytes
    return ReviewEvidenceResult(content, bytes, cumulativeBytes, expansionLedger.size)
  }

  override fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult {
    require(call.lane == assignment.lane) { "Tool call lane does not own this assignment." }
    terminalOutcome?.let { return ReviewToolCallResult(budgetExceeded = it) }
    policy.classify(ReviewRequestedOperation(call.kind, call.target, searchScopes = call.searchScopes))?.let {
      return ReviewToolCallResult(forbidden = it)
    }
    toolCalls += 1
    val outcome = ReviewBudgetEvaluator.exceededOrNull(
      identity,
      "specialist_tool_calls",
      budget.maxSpecialistToolCalls.toLong(),
      toolCalls.toLong(),
    )
    return ReviewToolCallResult(budgetExceeded = outcome?.also { terminalOutcome = it })
  }

  override fun recordModelTurn(): ReviewBudgetOutcome? {
    terminalOutcome?.let { return it }
    modelTurns += 1
    return ReviewBudgetEvaluator.exceededOrNull(
      identity,
      "specialist_model_turns",
      budget.maxSpecialistModelTurns.toLong(),
      modelTurns.toLong(),
    )?.also { terminalOutcome = it }
  }

  override fun validateLaneResult(result: String): ReviewBudgetOutcome? {
    terminalOutcome?.let { return it }
    resultBytes = maxOf(resultBytes, result.toByteArray(StandardCharsets.UTF_8).size.toLong())
    return ReviewBudgetEvaluator.laneResultOutcome(identity, budget, resultBytes)?.also { terminalOutcome = it }
  }

  override fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome? {
    terminalOutcome?.let { return it }
    resultBytes += chunk.toByteArray(StandardCharsets.UTF_8).size.toLong()
    return ReviewBudgetEvaluator.laneResultOutcome(identity, budget, resultBytes)?.also { terminalOutcome = it }
  }

  override fun evaluateProviderUsage(usage: ProviderTokenUsage, enforceable: Boolean): ReviewBudgetOutcome? {
    val outcome = ReviewBudgetEvaluator.providerUsageOutcome(
      identity,
      budget.providerTokenThresholds,
      usage,
      enforceable,
    ) ?: return null
    // A non-enforceable excess is observed only after the worker exited, so it is reported without
    // becoming the lane's terminal state.
    if (outcome.enforceable) terminalOutcome = outcome
    return outcome
  }

  override fun accounting(): ReviewLaneAccounting = ReviewLaneAccounting(
    lane = assignment.lane,
    evidenceBytes = cumulativeBytes,
    expansions = expansionLedger.toList(),
    toolCalls = toolCalls,
    modelTurns = modelTurns,
    resultBytes = resultBytes,
    terminalOutcome = terminalOutcome,
  )

  override fun terminalOutcome(): ReviewBudgetOutcome? = terminalOutcome

  private fun evidenceBudgetOutcome(bytes: Long): ReviewEvidenceResult? {
    if (bytes > budget.maxEvidenceResultBytes) {
      return exceeded("evidence_result_bytes", budget.maxEvidenceResultBytes, bytes)
    }
    val observedCumulative = cumulativeBytes + bytes
    return if (observedCumulative > budget.maxLaneEvidenceBytes) {
      exceeded("lane_evidence_bytes", budget.maxLaneEvidenceBytes, observedCumulative)
    } else {
      null
    }
  }

  private fun exceeded(kind: String, limit: Long, observed: Long): ReviewEvidenceResult {
    val outcome = checkNotNull(ReviewBudgetEvaluator.exceededOrNull(identity, kind, limit, observed)) {
      "Budget dimension '$kind' reported an excess of $observed against $limit that does not exceed it."
    }
    terminalOutcome = outcome
    return terminalResult(outcome, cumulativeBytes, expansionLedger.size)
  }

  private fun batchResult(results: List<ReviewEvidenceResult>, outcome: ReviewBudgetOutcome?) =
    ReviewEvidenceBatchResult(results, cumulativeBytes, expansionLedger.toList(), outcome)
}

private fun resolveRepositoryFile(root: Path, normalized: String): Path {
  val candidate = root.resolve(normalized).normalize()
  require(candidate.startsWith(root)) { "Evidence path escapes the repository." }
  val real = candidate.toRealPath()
  require(real.startsWith(root) && Files.isRegularFile(real)) { "Evidence path must be a repository file." }
  return real
}

private fun forbiddenResult(forbidden: ForbiddenReviewOperation, cumulativeBytes: Long, expansionCount: Int) =
  ReviewEvidenceResult(
    content = null,
    bytes = 0,
    cumulativeBytes = cumulativeBytes,
    expansionCount = expansionCount,
    forbidden = forbidden,
  )

private fun terminalResult(outcome: ReviewBudgetOutcome, cumulativeBytes: Long, expansionCount: Int) =
  ReviewEvidenceResult(
    content = null,
    bytes = 0,
    cumulativeBytes = cumulativeBytes,
    expansionCount = expansionCount,
    budgetExceeded = outcome,
  )
