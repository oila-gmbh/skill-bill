package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.error.ReviewHunkEvidenceIntegrityError
import skillbill.error.ReviewHunkEvidenceLocatorMissingError
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewRefusedOperationRecord
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceLocatorReadRequest
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.LANE_EVIDENCE_BYTES_DIMENSION
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetEvaluator
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewLaneIdentity
import skillbill.review.context.model.ReviewOperationKind
import skillbill.review.context.model.ReviewOperationPolicy
import skillbill.review.context.model.ReviewRequestedOperation
import skillbill.review.context.model.requireRepositoryRelativePath
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val EXPANSION_ID_HEX_LENGTH = 24

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
  private val authorizedExpansionLedger = binding.trustedExpansionLedger.toMutableList()
  private val projectedHunks = binding.projectedHunks
  private val locatorReader = binding.locatorReader
  private val bodyExtractor = binding.bodyExtractor
  private val completeFileCheckpoint = (
    assignment.assignedPaths + assignment.dependencyAllowlist.normalized
    ).distinct().associateWith(::checkpointDigest)

  private var cumulativeBytes = 0L
  private var authorizedReadCount = 0
  private var refusedOperationCount = 0
  private var resultBytes = 0L
  private var laneResultObserved = false
  private var toolCalls = 0
  private var modelTurns = 0
  private val expansionLedger = mutableListOf<ReviewExpansionRecord>()
  private val refusalLedger = mutableListOf<ReviewRefusedOperationRecord>()
  private val admittedEvidenceTargets = mutableSetOf<String>()
  private var terminalOutcome: ReviewBudgetOutcome? = null
  private val deniedUnits = mutableListOf<String>()
  private val hunkCommitById = assignment.assignedBundle.entries
    .flatMap { entry -> entry.hunkIds.map { it to entry.commitSha } }
    .toMap()

  init {
    val admitted = assignment.assignedPaths + assignment.dependencyAllowlist.normalized +
      assignment.evidenceTargets.map { it.path } + assignment.expansions.map { it.requestedPath }
    admitted.distinct().forEach { validateRepositoryMapping(root, it) }
  }

  @Synchronized
  override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord {
    require(request.lane == assignment.lane) { "Expansion lane does not own this assignment." }
    require(request.reachabilityReason.isNotBlank()) { "Expansion reachability reason must not be blank." }
    requireRepositoryRelativePath(request.path)
    require(policy.isReachable(request.path)) { "Expansion path is outside the assignment evidence surface." }
    val existing = authorizedExpansionLedger.singleOrNull {
      it.requestedPath == request.path && it.reachabilityReason == request.reachabilityReason
    }
    if (existing != null) return existing
    val sequence = (authorizedExpansionLedger.maxOfOrNull { it.sequence } ?: -1) + 1
    val expansion = ReviewExpansionRecord(
      expansionId = stableExpansionId(request.path, request.reachabilityReason),
      assignmentDigest = assignment.digest,
      requestedPath = request.path,
      reachabilityReason = request.reachabilityReason,
      authorized = true,
      sequence = sequence,
    )
    authorizedExpansionLedger += expansion
    return expansion
  }

  @Synchronized
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
    val exactPath = request.path
    if (!exactPath.startsWith('/') && !exactPath.startsWith('\\')) {
      requireRepositoryRelativePath(exactPath)
    }
    val operation = ReviewRequestedOperation(ReviewOperationKind.FILE_READ, exactPath, request.reachabilityReason)
    policy.classify(operation)?.let { return refused(it) }
    requireRepositoryRelativePath(exactPath)
    val normalizedTarget = normalizeEvidenceIdentity(exactPath)
    if (!admittedEvidenceTargets.add(normalizedTarget)) {
      return refused(
        ForbiddenReviewOperation(
          "repeated_evidence_read",
          exactPath,
          "The normalized evidence target was already read by this lane.",
        ),
      )
    }
    val assigned = policy.isAssigned(exactPath)
    val expansion = request.authorizedExpansion
    if (!assigned || expansion != null) {
      val expansion = requireNotNull(request.authorizedExpansion) {
        "Unassigned evidence requires an authorized expansion record."
      }
      require(expansion.authorized) { "Expansion '${expansion.expansionId}' is not authorized." }
      require(expansion.assignmentDigest == assignment.digest) {
        "Expansion '${expansion.expansionId}' does not belong to this assignment."
      }
      require(expansion.requestedPath == exactPath) {
        "Expansion '${expansion.expansionId}' does not authorize '$exactPath'."
      }
      require(expansion.reachabilityReason == request.reachabilityReason) {
        "Expansion '${expansion.expansionId}' reason provenance changed before admission."
      }
      require(expansion !in expansionLedger) { "Expansion '${expansion.expansionId}' was already admitted." }
      require(expansion in authorizedExpansionLedger) {
        "Expansion '${expansion.expansionId}' was not authorized by this assignment's measured broker."
      }
      expansionLedger += expansion
      if (expansionLedger.size > budget.maxAssignmentExpansions) {
        return exceeded("assignment_expansions", budget.maxAssignmentExpansions.toLong(), expansionLedger.size.toLong())
      }
    }

    return readAdmittedFile(exactPath, assigned, expansion != null)
  }

  private fun readAdmittedFile(
    normalized: String,
    assigned: Boolean,
    completeFileAuthorized: Boolean,
  ): ReviewEvidenceResult {
    authorizedReadCount += 1
    return if (assigned && !completeFileAuthorized) {
      readProjectedHunks(normalized)
    } else {
      readCompleteFile(normalized, assigned)
    }
  }

  private fun readProjectedHunks(path: String): ReviewEvidenceResult {
    val hunks = projectedHunks
      .filter { it.path == path }
      .sortedWith(compareBy({ it.newStart }, { it.oldStart }, { it.hunkId }))
    val delivered = mutableListOf<String>()
    for (hunk in hunks) {
      val body = materializeAssignedHunk(hunk)
      val bytes = body.toByteArray(StandardCharsets.UTF_8).size.toLong()
      assignedHunkBudgetOutcome(bytes, unitForHunk(hunk))?.let { exceeded ->
        return if (delivered.isEmpty()) {
          exceeded
        } else {
          val content = delivered.joinToString("\n")
          ReviewEvidenceResult(
            content,
            content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            cumulativeBytes,
            expansionLedger.size,
            budgetExceeded = exceeded.budgetExceeded,
          )
        }
      }
      cumulativeBytes += bytes
      delivered += body
    }
    val content = delivered.joinToString("\n")
    return ReviewEvidenceResult(
      content,
      content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
      cumulativeBytes,
      expansionLedger.size,
    )
  }

  private fun materializeAssignedHunk(hunk: ReviewChangedHunk): String {
    val locator = hunk.evidenceLocator
    val body = if (locatorReader !== FeatureTaskRuntimeSharedEvidenceLocatorReadPort.NONE) {
      val payload = locatorReader.readDiffPayload(
        FeatureTaskRuntimeSharedEvidenceLocatorReadRequest(root, locator.storePath, locator.payloadFile),
      )
      bodyExtractor.extract(payload, hunk)
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

  private fun assignedHunkBudgetOutcome(bytes: Long, unit: String): ReviewEvidenceResult? {
    val observedCumulative = cumulativeBytes + bytes
    return if (observedCumulative > budget.maxLaneEvidenceBytes) {
      recordLaneEvidenceDenial(unit)
      exceeded("lane_evidence_bytes", budget.maxLaneEvidenceBytes, observedCumulative)
    } else {
      null
    }
  }

  private fun readCompleteFile(path: String, assigned: Boolean): ReviewEvidenceResult {
    val real = resolveRepositoryFile(root, path)
    val expectedDigest = completeFileCheckpoint.getValue(path)
    if (real == null) {
      if (expectedDigest != null) rejectCheckpointDrift(path)
      require(assigned) { "Expanded evidence path must be a repository file." }
      return unavailableResult(cumulativeBytes, expansionLedger.size)
    }
    val contentBytes = Files.readAllBytes(real)
    if (expectedDigest == null || digest(contentBytes) != expectedDigest) {
      rejectCheckpointDrift(path)
    }
    return serveEvidence(path, contentBytes)
  }

  private fun serveEvidence(path: String, contentBytes: ByteArray): ReviewEvidenceResult {
    val bytes = contentBytes.size.toLong()
    evidenceBudgetOutcome(bytes, unitAtPath(path))?.let { return it }
    cumulativeBytes += bytes
    return ReviewEvidenceResult(
      contentBytes.toString(StandardCharsets.UTF_8),
      bytes,
      cumulativeBytes,
      expansionLedger.size,
    )
  }

  private fun checkpointDigest(path: String): String? {
    val real = resolveRepositoryFile(root, path) ?: return null
    return digest(Files.readAllBytes(real))
  }

  private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

  private fun rejectCheckpointDrift(path: String): Nothing = throw InvalidReviewContextSchemaError(
    sourceLabel = "review-evidence:${assignment.reviewId}:${assignment.lane}",
    reason = "Complete-file evidence '$path' changed after the immutable launch checkpoint was bound.",
  )

  private fun stableExpansionId(path: String, reason: String): String {
    val input = "${assignment.digest}\u0000$path\u0000$reason".toByteArray(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(input)
      .joinToString("") { "%02x".format(it) }
    return "exp-${digest.take(EXPANSION_ID_HEX_LENGTH)}"
  }

  @Synchronized
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

  @Synchronized
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

  @Synchronized
  override fun validateLaneResult(result: String): ReviewBudgetOutcome? {
    laneResultObserved = true
    terminalOutcome?.let { return it }
    resultBytes = maxOf(resultBytes, result.toByteArray(StandardCharsets.UTF_8).size.toLong())
    return ReviewBudgetEvaluator.laneResultOutcome(identity, budget, resultBytes)?.also { terminalOutcome = it }
  }

  @Synchronized
  override fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome? {
    laneResultObserved = true
    terminalOutcome?.let { return it }
    resultBytes += chunk.toByteArray(StandardCharsets.UTF_8).size.toLong()
    return ReviewBudgetEvaluator.laneResultOutcome(identity, budget, resultBytes)?.also { terminalOutcome = it }
  }

  @Synchronized
  override fun hasObservedLaneResult(): Boolean = laneResultObserved

  @Synchronized
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

  @Synchronized
  override fun accounting(): ReviewLaneAccounting {
    val terminal = terminalOutcome
    val evidenceIncomplete = terminal?.budgetKind == LANE_EVIDENCE_BYTES_DIMENSION
    return ReviewLaneAccounting(
      lane = assignment.lane,
      authorizedReadCount = authorizedReadCount,
      refusedOperationCount = refusedOperationCount,
      refusals = refusalLedger.toList(),
      evidenceBytes = cumulativeBytes,
      expansions = expansionLedger.toList(),
      toolCalls = toolCalls,
      modelTurns = modelTurns,
      resultBytes = resultBytes,
      terminalOutcome = terminal,
      budgetDimension = if (evidenceIncomplete) LANE_EVIDENCE_BYTES_DIMENSION else null,
      unreviewedUnits = if (evidenceIncomplete) deniedUnits.distinct() else emptyList(),
    )
  }

  @Synchronized
  override fun terminalOutcome(): ReviewBudgetOutcome? = terminalOutcome

  private fun evidenceBudgetOutcome(bytes: Long, unit: String): ReviewEvidenceResult? {
    if (bytes > budget.maxEvidenceResultBytes) {
      return exceeded("evidence_result_bytes", budget.maxEvidenceResultBytes, bytes)
    }
    val observedCumulative = cumulativeBytes + bytes
    return if (observedCumulative > budget.maxLaneEvidenceBytes) {
      recordLaneEvidenceDenial(unit)
      exceeded("lane_evidence_bytes", budget.maxLaneEvidenceBytes, observedCumulative)
    } else {
      null
    }
  }

  private fun recordLaneEvidenceDenial(unit: String) {
    deniedUnits += unit
  }

  private fun unitForHunk(hunk: ReviewChangedHunk): String = "${commitShaForHunk(hunk.hunkId)}@${hunk.path}"

  private fun unitAtPath(path: String): String {
    val hunkId = projectedHunks.firstOrNull { it.path == path }?.hunkId
    val commit = hunkId?.let(::commitShaForHunk) ?: assignment.headRevision
    return "$commit@$path"
  }

  private fun commitShaForHunk(hunkId: String): String = hunkCommitById[hunkId] ?: assignment.headRevision

  private fun refused(forbidden: ForbiddenReviewOperation): ReviewEvidenceResult =
    forbiddenResult(forbidden, cumulativeBytes, expansionLedger.size)

  private fun exceeded(kind: String, limit: Long, observed: Long): ReviewEvidenceResult {
    val outcome = checkNotNull(ReviewBudgetEvaluator.exceededOrNull(identity, kind, limit, observed)) {
      "Budget dimension '$kind' reported an excess of $observed against $limit that does not exceed it."
    }
    terminalOutcome = outcome
    return terminalResult(outcome, cumulativeBytes, expansionLedger.size)
  }

  private fun batchResult(
    results: List<ReviewEvidenceResult>,
    outcome: ReviewBudgetOutcome?,
  ): ReviewEvidenceBatchResult {
    refusedOperationCount += results.count { it.forbidden != null || it.budgetExceeded != null }
    results.forEach { result ->
      result.forbidden?.let { refusalLedger += ReviewRefusedOperationRecord(it.category, it.target) }
      result.budgetExceeded?.let { refusalLedger += ReviewRefusedOperationRecord(it.type, it.budgetKind) }
    }
    return ReviewEvidenceBatchResult(results, cumulativeBytes, expansionLedger.toList(), outcome)
  }
}

private fun normalizeEvidenceIdentity(path: String): String =
  Path.of(path).normalize().joinToString("/") { it.toString() }

private fun validateRepositoryMapping(root: Path, repositoryPath: String) {
  requireRepositoryRelativePath(repositoryPath)
  var current = root
  repositoryPath.split('/').forEach { logicalComponent ->
    val component = runCatching { Path.of(logicalComponent) }
      .getOrElse { throw IllegalArgumentException("Review path is not representable on the active filesystem.", it) }
    require(!component.isAbsolute && component.nameCount == 1 && component.toString() == logicalComponent) {
      "Review path '$repositoryPath' is not represented exactly on the active filesystem."
    }
    current = current.resolve(component)
    require(!Files.isSymbolicLink(current)) { "Review path '$repositoryPath' crosses a symbolic link." }
  }
  require(current.normalize().startsWith(root)) { "Review path '$repositoryPath' escapes the repository root." }
}

private fun resolveRepositoryFile(root: Path, normalized: String): Path? {
  val candidate = root.resolve(normalized).normalize()
  require(candidate.startsWith(root)) { "Evidence path escapes the repository." }
  var component = root
  root.relativize(candidate).forEach { segment ->
    component = component.resolve(segment)
    if (!Files.exists(component, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
    require(!Files.isSymbolicLink(component)) { "Evidence paths must not contain symbolic links." }
  }
  val real = candidate.toRealPath()
  require(real.startsWith(root) && Files.isRegularFile(real)) { "Evidence path must be a repository file." }
  return real
}

private fun unavailableResult(cumulativeBytes: Long, expansionCount: Int) = ReviewEvidenceResult(
  content = null,
  bytes = 0,
  cumulativeBytes = cumulativeBytes,
  expansionCount = expansionCount,
)

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
