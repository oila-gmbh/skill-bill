package skillbill.application.review

import skillbill.application.model.ReviewLaneIntegrationInput
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.GovernedReviewIntegrationLaunch
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewIntegrationTerminalOutcome
import skillbill.review.context.model.ReviewPacketConsumerContract
import skillbill.review.context.model.ReviewSpecialistSummary
import skillbill.review.context.model.structuredString
import skillbill.review.model.ParallelReviewRawFinding
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Runs the single bounded integration pass after every specialist lane reaches a terminal state.
 *
 * Exactly one pass runs per review regardless of how many commits the sequence carries, and it
 * launches no specialist rubric. A review with nothing to integrate over — a synthetic unit or a
 * lone commit — is skipped deterministically with a stated reason rather than run on empty input.
 */
class ReviewIntegrationPassRunner(
  private val launcher: GoalRunnerSubtaskLauncher,
  private val envelopeValidator: ReviewContextEnvelopeValidator,
) {
  @Suppress("LongParameterList")
  fun run(
    packet: ReviewContextPacket,
    lanes: List<ReviewLaneIntegrationInput>,
    budget: ReviewContextBudgetPolicy,
    brokerId: String,
    repoRoot: Path,
    timeout: Duration,
    modelOverride: String? = null,
  ): ReviewIntegrationPassOutcome {
    skipReasonFor(packet, lanes)?.let { reason ->
      return ReviewIntegrationPassOutcome.skipped(packet.commitSequenceDigest, reason)
    }
    val integration = GovernedReviewIntegrationLaunch(
      packet = packet,
      specialistSummaries = lanes.map(::summaryOf),
      integrationContract = ReviewPacketConsumerContract.INTEGRATION_CONTRACT,
      brokerId = brokerId,
      budget = budget,
    )
    // Validating before launch is what makes the exclusion rule enforceable: a projection that grew
    // a raw-evidence or transcript key fails here rather than reaching the integration worker.
    envelopeValidator.validate(
      integration.toIntegrationLaunchEnvelope().asWireMap(),
      "review integration launch for ${packet.reviewId}",
    )
    val prompt = integrationPrompt(integration)
    val outcome = launcher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = brokerId,
        configuredAgentOverrideId = null,
        skillRunRequest = SkillRunRequest(
          issueKey = "code-review-integration",
          repoRoot = repoRoot,
          timeout = timeout,
          promptOverride = prompt,
          modelOverride = modelOverride,
        ),
      ),
    )
    val launchBytes = prompt.toByteArray(Charsets.UTF_8).size.toLong()
    return when (outcome) {
      is UnsupportedAgentRunLaunch -> ReviewIntegrationPassOutcome(
        commitSequenceDigest = integration.commitSequenceDigest,
        terminalOutcome = ReviewIntegrationTerminalOutcome.UNSUPPORTED_PROVIDER,
        summarizedLaneCount = integration.specialistSummaries.size,
        launchBytes = launchBytes,
        failureReason = "unsupported agent: ${outcome.reason}",
      )
      is AgentRunLaunchFacts -> completedOutcome(integration, outcome, launchBytes)
    }
  }

  private fun completedOutcome(
    integration: GovernedReviewIntegrationLaunch,
    facts: AgentRunLaunchFacts,
    launchBytes: Long,
  ): ReviewIntegrationPassOutcome {
    val terminal = terminalOutcomeOf(facts)
    return ReviewIntegrationPassOutcome(
      commitSequenceDigest = integration.commitSequenceDigest,
      terminalOutcome = terminal,
      summarizedLaneCount = integration.specialistSummaries.size,
      findings = if (terminal == ReviewIntegrationTerminalOutcome.COMPLETED) {
        crossCommitFindings(facts.stdout, integration)
      } else {
        emptyList()
      },
      launchBytes = launchBytes,
      resultBytes = facts.stdout.toByteArray(Charsets.UTF_8).size.toLong(),
      modelTurns = 1,
      failureReason = if (terminal == ReviewIntegrationTerminalOutcome.COMPLETED) null else terminal.wireValue,
    )
  }

  /**
   * The integration pass exists to report interactions between commits, so a finding it returns
   * must name the commits it relates, and those commits must belong to the reviewed sequence. A
   * finding naming no commit is a single-commit observation the finishing lane already owned.
   *
   * A worker citing an abbreviated or hallucinated SHA is unusable output, not a contract breach:
   * abbreviations resolve against the owned set by unique prefix and anything still foreign drops
   * the finding, so the remaining cross-commit findings survive instead of the whole run throwing
   * away every specialist lane's already-finished work.
   */
  private fun crossCommitFindings(
    stdout: String,
    integration: GovernedReviewIntegrationLaunch,
  ): List<ParallelReviewRawFinding> {
    val owned = integration.packet.ownedCommitIds
    return ParallelReviewFindingParser.parse(stdout).mapNotNull { finding ->
      val resolved = finding.commitShas.map { sha -> resolveCommitSha(sha, owned) }
      if (resolved.any { it == null }) return@mapNotNull null
      finding.copy(
        specialistSkillName = INTEGRATION_LANE,
        commitShas = resolved.filterNotNull().distinct(),
      )
    }.filter { it.commitShas.size > 1 }
  }

  /** Exact match, else the single owned commit this abbreviation prefixes; ambiguous stays foreign. */
  private fun resolveCommitSha(sha: String, owned: Set<String>): String? = when {
    sha in owned -> sha
    else -> owned.filter { it.startsWith(sha) }.singleOrNull()
  }

  private fun summaryOf(input: ReviewLaneIntegrationInput): ReviewSpecialistSummary {
    val decision = input.launch.assignment.laneDecision
    return ReviewSpecialistSummary.of(
      lane = input.launch.assignment.lane,
      assignmentDigest = input.launch.assignment.digest,
      completion = input.completion,
      assignedPaths = input.launch.assignment.assignedPaths,
      commitShas = input.launch.assignment.assignedBundle.entries.map { it.commitSha },
      findingCount = input.findingCount,
      summary = "Specialist '${decision.specialistSkillName}' reviewed " +
        "${input.launch.assignment.assignedPaths.size} assigned path(s) in one pass and reported " +
        "${input.findingCount} finding(s).",
    )
  }

  private fun skipReasonFor(packet: ReviewContextPacket, lanes: List<ReviewLaneIntegrationInput>): String? = when {
    packet.commitUnits.any { it.source.isSynthetic } ->
      "the review scope resolved to a synthetic unit, so there is no commit sequence to integrate over"
    packet.commitUnits.size < MIN_INTEGRATION_COMMITS ->
      "the commit sequence carries a single commit, so no cross-commit behavior exists to integrate"
    lanes.isEmpty() ->
      "no specialist lane reached a terminal state, so there are no lane summaries to integrate"
    else -> null
  }

  /**
   * Bounded by construction: commit subjects and lane summaries only. Adding a hunk body here would
   * be caught by the pre-launch envelope validation, but it must not be written in the first place.
   */
  private fun integrationPrompt(integration: GovernedReviewIntegrationLaunch): String = buildString {
    appendLine(integration.integrationContract)
    appendLine()
    appendLine("Commit sequence (${integration.packet.commitUnits.size} commits, in order):")
    integration.packet.commitUnits.sortedBy { it.orderIndex }.forEach { unit ->
      appendLine("- ${unit.orderIndex}: ${unit.commitSha} ${structuredString(unit.subject.replace("\r\n", "\n"))}")
    }
    appendLine()
    appendLine("Specialist lane summaries (already reviewed; do not re-run their rubrics):")
    integration.specialistSummaries.sortedBy { it.lane }.forEach { summary ->
      appendLine(
        "- ${summary.lane} | coverage=${summary.disposition.wireValue} | " +
          "findings=${summary.findingCount} | ${summary.summary}",
      )
      if (!summary.isCleanCoverage) {
        appendLine(
          "  Coverage gap — this lane left unreviewed: ${summary.unreviewedUnits.joinToString(", ")}. " +
            "You are not reviewing these and must not report this gap as covered.",
        )
      }
    }
    appendLine()
    appendLine("Final-state evidence you may read (head revision):")
    integration.finalStateEvidenceTargets.forEach { target -> appendLine("- ${structuredString(target.path)}") }
    appendLine()
    appendLine(
      "Return only '[F-XXX] Severity | Confidence | commits=<sha>,<sha> | path=<JSON string> | " +
        "line=<positive integer> | description' lines. Every finding must name at least two commits " +
        "from the sequence above; a single-commit observation belongs to its specialist lane, not here.",
    )
  }

  private fun terminalOutcomeOf(facts: AgentRunLaunchFacts): ReviewIntegrationTerminalOutcome = when {
    facts.timedOut -> ReviewIntegrationTerminalOutcome.TIMEOUT
    facts.interrupted -> ReviewIntegrationTerminalOutcome.INTERRUPTED
    facts.spawnFailed -> ReviewIntegrationTerminalOutcome.SPAWN_FAILURE
    facts.stdoutTruncated -> ReviewIntegrationTerminalOutcome.PROCESS_FAILURE
    facts.exitStatus != 0 -> ReviewIntegrationTerminalOutcome.PROCESS_FAILURE
    else -> ReviewIntegrationTerminalOutcome.COMPLETED
  }

  companion object {
    /** Attribution for a finding the integration pass owns; never a specialist skill name. */
    const val INTEGRATION_LANE: String = "review-integration"

    private const val MIN_INTEGRATION_COMMITS = 2
  }
}
