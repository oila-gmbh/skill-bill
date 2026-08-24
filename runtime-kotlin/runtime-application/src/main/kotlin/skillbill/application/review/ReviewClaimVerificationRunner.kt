package skillbill.application.review

import skillbill.agent.model.AgentPhaseInput
import skillbill.agent.model.AgentPhaseOutput
import skillbill.application.review.model.ReviewClaimVerificationOutcome
import skillbill.contracts.JsonSupport
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.ReviewFindingFieldCodec
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.GovernedReviewVerificationLaunch
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewCitedRegion
import skillbill.review.context.model.ReviewClaimVerdictAdmission
import skillbill.review.context.model.ReviewClaimWorkerResult
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.context.model.requireRepositoryRelativePath
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewStage
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration

class ReviewClaimVerificationRunner(
  private val launcher: GoalRunnerSubtaskLauncher,
  private val envelopeValidator: ReviewContextEnvelopeValidator,
) {
  @Suppress("LongParameterList")
  fun run(
    packet: ReviewContextPacket?,
    reviewOutput: String = "",
    findings: List<ParallelReviewMergedFinding>,
    existingVerdicts: List<ReviewFindingVerdict>,
    mode: ResolvedReviewExecutionMode,
    budget: ReviewContextBudgetPolicy,
    brokerId: String,
    repoRoot: Path,
    timeout: Duration,
    modelOverride: String? = null,
    promptSuffix: String = "",
  ): ReviewClaimVerificationOutcome {
    if (packet == null) {
      return ReviewClaimVerificationOutcome(
        verdicts = emptyList(),
        skipReason = "the review compiled no packet, so there is no delta to verify against",
      )
    }
    if (findings.isEmpty()) {
      return when {
        !reviewOutputNeedsProseVerification(reviewOutput) -> ReviewClaimVerificationOutcome(
          verdicts = emptyList(),
          skipReason = "the review pass emitted no findings, so there is nothing to verify",
        )
        else -> verifyReviewOutput(
          packet = packet,
          reviewOutput = reviewOutput,
          mode = mode,
          budget = budget,
          brokerId = brokerId,
          repoRoot = repoRoot,
          timeout = timeout,
          modelOverride = modelOverride,
          promptSuffix = promptSuffix,
        )
      }
    }
    val durableRefs = existingVerdicts
      .filter { it.stage == ReviewStage.VERIFICATION }
      .map { it.findingRef }
      .toSet()
    val pending = findings.sortedBy { it.fNumber }.filterNot { it.fNumber in durableRefs }
    if (pending.isEmpty()) {
      return ReviewClaimVerificationOutcome(
        verdicts = existingVerdicts.filter { it.stage == ReviewStage.VERIFICATION },
        skipReason = "every finding already holds a durable verification verdict",
      )
    }
    val recordedAt = Instant.now().toString()
    val verdicts = pending.map { finding ->
      verifyOne(
        packet = packet,
        reviewOutput = reviewOutput,
        finding = finding,
        mode = mode,
        budget = budget,
        brokerId = brokerId,
        repoRoot = repoRoot,
        timeout = timeout,
        modelOverride = modelOverride,
        recordedAt = recordedAt,
        promptSuffix = promptSuffix,
      )
    }
    return ReviewClaimVerificationOutcome(
      verdicts = existingVerdicts.filter { it.stage == ReviewStage.VERIFICATION && it.findingRef in durableRefs } +
        verdicts,
    )
  }

  @Suppress("LongParameterList")
  private fun verifyReviewOutput(
    packet: ReviewContextPacket,
    reviewOutput: String,
    mode: ResolvedReviewExecutionMode,
    budget: ReviewContextBudgetPolicy,
    brokerId: String,
    repoRoot: Path,
    timeout: Duration,
    modelOverride: String?,
    promptSuffix: String,
  ): ReviewClaimVerificationOutcome {
    if (reviewOutput.isBlank()) {
      return ReviewClaimVerificationOutcome(
        verdicts = emptyList(),
        skipReason = "the review phase produced no output to verify",
      )
    }
    val phaseInput = AgentPhaseInput(
      input = reviewOutput,
      requestedAction = VERIFY_CLAIMS_ACTION,
    )
    val prompt = appendPromptSuffix(
      proseVerificationPrompt(packet, phaseInput, mode),
      promptSuffix,
    )
    if (prompt.toByteArray(Charsets.UTF_8).size.toLong() > budget.maxLaneLaunchBytes) {
      return ReviewClaimVerificationOutcome(
        verdicts = emptyList(),
        skipReason = "verification phase launch exceeded max_lane_launch_bytes",
      )
    }
    val outcome = launcher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = brokerId,
        configuredAgentOverrideId = null,
        skillRunRequest = SkillRunRequest(
          issueKey = ISSUE_KEY,
          repoRoot = repoRoot,
          timeout = timeout,
          promptOverride = prompt,
          modelOverride = modelOverride,
        ),
      ),
    )
    return when (outcome) {
      is UnsupportedAgentRunLaunch -> ReviewClaimVerificationOutcome(
        verdicts = emptyList(),
        skipReason = "unsupported agent: ${outcome.reason}",
      )
      is AgentRunLaunchFacts -> ReviewClaimVerificationOutcome(
        verdicts = emptyList(),
        output = AgentPhaseOutput(outcome.stdout),
        skipReason = launchFailureReason(outcome),
      )
    }
  }

  @Suppress("LongParameterList")
  private fun verifyOne(
    packet: ReviewContextPacket,
    reviewOutput: String,
    finding: ParallelReviewMergedFinding,
    mode: ResolvedReviewExecutionMode,
    budget: ReviewContextBudgetPolicy,
    brokerId: String,
    repoRoot: Path,
    timeout: Duration,
    modelOverride: String?,
    recordedAt: String,
    promptSuffix: String,
  ): ReviewFindingVerdict {
    val region = citedRegionOf(finding)
      ?: return unresolved(finding, recordedAt, "finding has no cited file:line region")
    val launch = GovernedReviewVerificationLaunch(
      packet = packet,
      finding = finding,
      citedRegion = region,
      evidenceSurfaceRules = ReviewPreparationService.verificationEvidenceSurfaceRules(mode),
      dependencyAllowlist = ReviewDependencyAllowlist(packet.dependencyAllowlist.normalized),
      brokerId = brokerId,
      budget = budget,
    )
    val envelope = launch.toVerificationLaunchEnvelope().asWireMap()
    val launchBytes = JsonSupport.mapToJsonString(envelope).toByteArray(Charsets.UTF_8).size.toLong()
    if (launchBytes > budget.maxLaneLaunchBytes) {
      return ReviewFindingVerdict(
        stage = ReviewStage.VERIFICATION,
        findingRef = finding.fNumber,
        claimVerdict = ReviewClaimVerdict.UNRESOLVED,
        recordedAt = recordedAt,
        rejectionReason = "verification launch exceeded max_lane_launch_bytes",
      )
    }
    envelopeValidator.validate(envelope, "review verification launch for ${finding.fNumber}")
    val prompt = appendPromptSuffix(
      verificationPrompt(launch, AgentPhaseInput(reviewOutput, VERIFY_CLAIMS_ACTION)),
      promptSuffix,
    )
    val outcome = launcher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = brokerId,
        configuredAgentOverrideId = null,
        skillRunRequest = SkillRunRequest(
          issueKey = ISSUE_KEY,
          repoRoot = repoRoot,
          timeout = timeout,
          promptOverride = prompt,
          modelOverride = modelOverride,
        ),
      ),
    )
    return when (outcome) {
      is UnsupportedAgentRunLaunch ->
        unresolved(finding, recordedAt, "unsupported agent: ${outcome.reason}")
      is AgentRunLaunchFacts -> fromLaunchFacts(finding, outcome, recordedAt)
    }
  }

  private fun fromLaunchFacts(
    finding: ParallelReviewMergedFinding,
    facts: AgentRunLaunchFacts,
    recordedAt: String,
  ): ReviewFindingVerdict {
    launchFailureReason(facts)?.let { reason ->
      return unresolved(finding, recordedAt, reason)
    }
    val worker = parseWorkerResult(facts.stdout)
      ?: return unresolved(finding, recordedAt, "unparseable verification output")
    return ReviewClaimVerdictAdmission.admit(finding, worker, recordedAt).verdict
  }

  private fun verificationPrompt(launch: GovernedReviewVerificationLaunch, phaseInput: AgentPhaseInput): String =
    buildString {
      appendLine("Verify exactly one review finding against the cited region and the delta.")
      appendLine("Phase input:")
      appendLine(phaseInput.input)
      appendLine("Requested action: ${phaseInput.requestedAction}")
      appendLine("The structured finding below is optional enrichment; the phase input is authoritative.")
      appendLine("Do not receive or use a spec intent projection or parent transcript.")
      appendLine("Do not inspect sibling findings.")
      appendLine("Review is read-only: do not build, compile, or run tests.")
      appendLine("Evidence surface: ${launch.evidenceSurfaceRules}")
      appendLine(
        "Finding ${launch.finding.fNumber}: ${launch.finding.severity.displayName} | " +
          "${launch.finding.location} | ${launch.finding.description}",
      )
      appendLine(
        "Cited region: ${launch.citedRegion.path}:" +
          "${launch.citedRegion.startLine}-${launch.citedRegion.endLine}",
      )
      appendLine("Delta: ${launch.packet.baseRevision}..${launch.packet.headRevision}")
      appendLine(
        "Return free-form verification prose describing confirmed, refuted, or unresolved. " +
          "An optional claim_verdict and citations as [{path, line}] may enrich the result.",
      )
      appendLine("A refuted verdict must cite the file:line construct that makes the code safe.")
      appendLine("Do not change the finding text, severity, or location.")
    }

  private fun proseVerificationPrompt(
    packet: ReviewContextPacket,
    phaseInput: AgentPhaseInput,
    mode: ResolvedReviewExecutionMode,
  ): String = buildString {
    appendLine("Verify each claim in the review phase output against the repository delta.")
    appendLine("Phase input:")
    appendLine(phaseInput.input)
    appendLine("Requested action: ${phaseInput.requestedAction}")
    appendLine("Review is read-only: do not build, compile, or run tests.")
    appendLine("Verification depth: ${mode.name.lowercase()}.")
    appendLine("Delta: ${packet.baseRevision}..${packet.headRevision}")
    appendLine("Return free-form verification prose. The output string is authoritative.")
  }

  companion object {
    const val ISSUE_KEY: String = "code-review-verification"
    const val VERIFY_CLAIMS_ACTION: String = "Verify each claim in that input against the repository delta."
  }
}

internal fun appendPromptSuffix(prompt: String, suffix: String): String {
  if (suffix.isEmpty()) return prompt
  return prompt.trimEnd() + "\n\n" + suffix
}

internal fun citedRegionOf(finding: ParallelReviewMergedFinding): ReviewCitedRegion? {
  val rawPath = finding.repositoryPath ?: finding.location.substringBefore(
    ':',
  ).takeIf { it.isNotBlank() } ?: return null
  val path = runCatching {
    requireRepositoryRelativePath(rawPath.trim())
    rawPath.trim()
  }.getOrNull() ?: return null
  val line = finding.line ?: finding.location.substringAfter(':', "").toIntOrNull()?.takeIf { it >= 1 } ?: return null
  return runCatching { ReviewCitedRegion(path, line, line) }.getOrNull()
}

internal fun parseWorkerResult(stdout: String): ReviewClaimWorkerResult? {
  val payload = parseJsonObject(stdout) ?: return null
  val finding = JsonSupport.anyToStringAnyMap(payload["finding"])
  return ReviewClaimWorkerResult(
    claimVerdict = payload["claim_verdict"] as? String,
    citations = parseCitations(payload["citations"]),
    findingRef = (finding?.get("finding_ref") as? String) ?: payload["finding_ref"] as? String,
    severity = (finding?.get("severity") as? String) ?: payload["severity"] as? String,
    location = (finding?.get("location") as? String) ?: payload["location"] as? String,
    description = (finding?.get("description") as? String) ?: payload["description"] as? String,
  )
}

internal fun parseJsonObject(stdout: String): Map<String, Any?>? {
  val trimmed = stdout.trim()
  JsonSupport.parseObjectOrNull(trimmed)?.let {
    return JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it))
  }
  val start = trimmed.indexOf('{')
  val end = trimmed.lastIndexOf('}')
  if (start < 0 || end <= start) return null
  return JsonSupport.parseObjectOrNull(trimmed.substring(start, end + 1))
    ?.let { JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it)) }
}

internal fun parseCitations(raw: Any?): List<ReviewFindingCitation> = ReviewFindingFieldCodec.citationsOf(raw)

internal fun intValue(raw: Any?): Int? = when (raw) {
  is Int -> raw
  is Long -> raw.toInt()
  is String -> raw.toIntOrNull()
  else -> null
}

internal fun launchFailureReason(facts: AgentRunLaunchFacts): String? = when {
  facts.timedOut -> "agent timed out"
  facts.spawnFailed -> "agent process failed to spawn"
  facts.interrupted -> "agent was interrupted"
  facts.exitStatus == null -> "agent exited with unknown status"
  facts.exitStatus != 0 -> "agent exited with status ${facts.exitStatus}"
  facts.stdoutTruncated -> "agent output exceeded the retention cap before completion"
  else -> null
}

private fun unresolved(
  finding: ParallelReviewMergedFinding,
  recordedAt: String,
  reason: String,
): ReviewFindingVerdict = ReviewFindingVerdict(
  stage = ReviewStage.VERIFICATION,
  findingRef = finding.fNumber,
  claimVerdict = ReviewClaimVerdict.UNRESOLVED,
  recordedAt = recordedAt,
  rejectionReason = reason,
)
