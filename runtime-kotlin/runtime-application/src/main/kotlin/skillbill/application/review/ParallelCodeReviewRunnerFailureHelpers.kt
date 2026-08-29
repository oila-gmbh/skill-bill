package skillbill.application.review

import skillbill.application.goalrunner.agentFailureExcerpt
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.context.model.ReviewLaneAssembledBundle
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.context.model.ReviewRegisterParseSeamException
import skillbill.review.model.ParallelReviewParseResult
import skillbill.review.model.ParallelReviewRawFinding

internal class ParallelCodeReviewRunnerFailureHelpers(
  private val registerParse: (String) -> ParallelReviewParseResult,
) {
  fun softAdmitFindings(
    stdout: String,
    launch: ParallelCodeReviewInlineParentLaunch,
  ): ParallelCodeReviewSoftRegisterAdmission = try {
    val parsed = parseLaneRegisterSeam(stdout, launch.assignment.lane, registerParse)
    ParallelCodeReviewSoftRegisterAdmission(
      findings = attributeInlineFindings(parsed, launch.selected),
      droppedCandidateDiagnostic = rejectedCandidateDiagnostic(parsed),
      rejectedCandidateCount = parsed.rejections.size,
    )
  } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
    ParallelCodeReviewSoftRegisterAdmission(emptyList(), null, 0)
  }

  private fun attributeInlineFindings(
    parsed: ParallelReviewParseResult,
    selected: List<ReviewSpecialistLaunchRequest>,
  ): List<ParallelReviewRawFinding> {
    val fallbackLane = selected.minByOrNull { it.assignment.laneDecision.orderIndex }
    val fallbackPath = fallbackLane?.assignment?.assignedPaths?.firstOrNull()
      ?: ParallelReviewFindingParser.UNASSIGNED_REPOSITORY_PATH
    return parsed.findings.map { finding ->
      val findingPath = finding.repositoryPath
      val pathOwners = selected.filter { launch ->
        findingPath != null && launch.assignment.assignedPaths.any { path -> path == findingPath }
      }.distinctBy { it.assignment.laneDecision.specialistSkillName }
      val owner = resolveInlineFindingOwner(finding.specialistSkillName, pathOwners, selected)
        ?: fallbackLane
      val path = when {
        findingPath != null &&
          findingPath != ParallelReviewFindingParser.UNASSIGNED_REPOSITORY_PATH -> findingPath
        else -> fallbackPath
      }
      val line = finding.line ?: PARALLEL_REVIEW_FIRST_SOURCE_LINE
      finding.copy(
        specialistSkillName = owner?.assignment?.laneDecision?.specialistSkillName
          ?: finding.specialistSkillName,
        originLayerChains = owner?.assignment?.laneDecision?.originLayerChains.orEmpty(),
        repositoryPath = path,
        line = line,
        location = "$path:$line",
      )
    }
  }

  private fun resolveInlineFindingOwner(
    declaredSpecialist: String?,
    pathOwners: List<ReviewSpecialistLaunchRequest>,
    selected: List<ReviewSpecialistLaunchRequest>,
  ): ReviewSpecialistLaunchRequest? {
    val selectedByName = selected.distinctBy { it.assignment.laneDecision.specialistSkillName }
    if (declaredSpecialist != null) {
      selectedByName.singleOrNull { it.assignment.laneDecision.specialistSkillName == declaredSpecialist }
        ?.let { return it }
    }
    return pathOwners.minByOrNull { it.assignment.laneDecision.orderIndex }
  }

  fun laneFailureReason(facts: AgentRunLaunchFacts): String? = when {
    facts.timedOut -> "agent timed out"
    facts.spawnFailed -> buildString {
      append("agent process failed to spawn")
      agentFailureExcerpt(
        facts.stderr,
        facts.stdout,
        PARALLEL_REVIEW_STDERR_EXCERPT_MAX_LENGTH,
      )?.let { excerpt ->
        append(" — ${excerpt.lineSequence().first().take(PARALLEL_REVIEW_STDERR_EXCERPT_MAX_LENGTH)}")
      }
    }
    facts.interrupted -> "agent was interrupted"
    facts.exitStatus == null -> "agent exited with unknown status"
    facts.exitStatus != 0 -> buildString {
      append("agent exited with status ${facts.exitStatus}")
      agentFailureExcerpt(
        facts.stderr,
        facts.stdout,
        PARALLEL_REVIEW_STDERR_EXCERPT_MAX_LENGTH,
      )?.let { excerpt ->
        append(" — ${excerpt.lineSequence().first().take(PARALLEL_REVIEW_STDERR_EXCERPT_MAX_LENGTH)}")
      }
    }
    facts.stdoutTruncated -> "agent output exceeded the retention cap before completion"
    else -> null
  }

  private fun rejectedCandidateDiagnostic(parsed: ParallelReviewParseResult): String? {
    val rejection = parsed.rejections.firstOrNull() ?: return null
    return "dropped ${parsed.rejections.size} of ${parsed.candidateCount} [F-XXX] candidate line(s); " +
      "first at line ${rejection.linePosition} rejected as ${rejection.reason.wireValue}: " +
      rejection.lineText.take(PARALLEL_REVIEW_REGISTER_ABSENCE_EXCERPT_MAX_LENGTH)
  }
}

internal fun parseLaneRegisterSeam(
  stdout: String,
  lane: String,
  parse: (String) -> ParallelReviewParseResult = ParallelReviewFindingParser::parse,
): ParallelReviewParseResult = try {
  parse(stdout)
} catch (@Suppress("TooGenericExceptionCaught") thrown: RuntimeException) {
  throw ReviewRegisterParseSeamException(seam = INLINE_FINDING_PARSE_SEAM, lane = lane, cause = thrown)
}

internal fun parallelCodeReviewNoOpResumeOutcome(agentId: String) = ParallelReviewLaneOutcome(
  success = true,
  rawOutput = "",
  accounting = ReviewLaneAccounting(
    lane = agentId,
    evidenceBytes = 0,
    expansions = emptyList(),
    toolCalls = 0,
    modelTurns = 0,
    resultBytes = 0,
    terminalStatus = NO_OP_RESUME_TERMINAL_STATUS,
    reviewDisposition = ReviewLaneReviewDisposition.COMPLETE,
    bundleCompositionDigest = ReviewLaneAssembledBundle.EMPTY.compositionDigest,
  ),
  reviewDisposition = ReviewLaneReviewDisposition.COMPLETE,
  bundleCompositionDigest = ReviewLaneAssembledBundle.EMPTY.compositionDigest,
)

internal fun parallelCodeReviewInlineTerminalStatus(
  facts: AgentRunLaunchFacts,
  disposition: ReviewLaneReviewDisposition,
): String = when {
  disposition == ReviewLaneReviewDisposition.INCOMPLETE -> "incomplete"
  facts.timedOut -> "timeout"
  facts.interrupted -> "interrupted"
  facts.spawnFailed -> "spawn_failure"
  facts.exitStatus != 0 -> "process_failure"
  else -> "completed"
}

internal fun parallelCodeReviewCaptureLane(lane: () -> ParallelReviewLaneOutcome): ParallelReviewLaneOutcome = try {
  lane()
} catch (seam: ReviewRegisterParseSeamException) {
  throw seam
} catch (@Suppress("TooGenericExceptionCaught") thrown: Exception) {
  ParallelReviewLaneOutcome(
    success = false,
    rawOutput = "",
    failureReason = "lane launch threw ${thrown::class.simpleName}: ${thrown.message ?: "no detail"}",
  )
}
