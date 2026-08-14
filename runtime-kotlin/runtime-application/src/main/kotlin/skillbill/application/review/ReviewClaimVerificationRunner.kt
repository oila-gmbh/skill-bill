package skillbill.application.review

import skillbill.contracts.JsonSupport
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.GovernedReviewVerificationLaunch
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewClaimVerdictAdmission
import skillbill.review.context.model.ReviewClaimWorkerResult
import skillbill.review.context.model.ReviewCitedRegion
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewStage
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration

data class ReviewClaimVerificationOutcome(
  val verdicts: List<ReviewFindingVerdict>,
  val envelopes: List<Map<String, Any?>> = emptyList(),
  val skipReason: String? = null,
)

class ReviewClaimVerificationRunner(
  private val launcher: GoalRunnerSubtaskLauncher,
  private val envelopeValidator: ReviewContextEnvelopeValidator,
) {
  @Suppress("LongParameterList")
  fun run(
    packet: ReviewContextPacket?,
    findings: List<ParallelReviewMergedFinding>,
    existingVerdicts: List<ReviewFindingVerdict>,
    mode: ResolvedReviewExecutionMode,
    budget: ReviewContextBudgetPolicy,
    brokerId: String,
    repoRoot: Path,
    timeout: Duration,
    modelOverride: String? = null,
  ): ReviewClaimVerificationOutcome {
    if (packet == null) {
      return ReviewClaimVerificationOutcome(
        verdicts = emptyList(),
        skipReason = "the review compiled no packet, so there is no delta to verify against",
      )
    }
    if (findings.isEmpty()) {
      return ReviewClaimVerificationOutcome(
        verdicts = emptyList(),
        skipReason = "the review pass emitted no findings, so there is nothing to verify",
      )
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
    val envelopes = mutableListOf<Map<String, Any?>>()
    val verdicts = pending.map { finding ->
      verifyOne(
        packet = packet,
        finding = finding,
        mode = mode,
        budget = budget,
        brokerId = brokerId,
        repoRoot = repoRoot,
        timeout = timeout,
        modelOverride = modelOverride,
        recordedAt = recordedAt,
        envelopes = envelopes,
      )
    }
    return ReviewClaimVerificationOutcome(
      verdicts = existingVerdicts.filter { it.stage == ReviewStage.VERIFICATION && it.findingRef in durableRefs } +
        verdicts,
      envelopes = envelopes,
    )
  }

  @Suppress("LongParameterList")
  private fun verifyOne(
    packet: ReviewContextPacket,
    finding: ParallelReviewMergedFinding,
    mode: ResolvedReviewExecutionMode,
    budget: ReviewContextBudgetPolicy,
    brokerId: String,
    repoRoot: Path,
    timeout: Duration,
    modelOverride: String?,
    recordedAt: String,
    envelopes: MutableList<Map<String, Any?>>,
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
    envelopes += envelope
    val prompt = verificationPrompt(launch)
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

  private fun verificationPrompt(launch: GovernedReviewVerificationLaunch): String = buildString {
    appendLine("Verify exactly one review finding against the cited region and the delta.")
    appendLine("Do not receive or use a spec intent projection, reviewer narrative, or parent transcript.")
    appendLine("Do not inspect sibling findings.")
    appendLine("Review is read-only: do not build, compile, or run tests.")
    appendLine("Evidence surface: ${launch.evidenceSurfaceRules}")
    appendLine(
      "Finding ${launch.finding.fNumber}: ${launch.finding.severity.displayName} | " +
        "${launch.finding.location} | ${launch.finding.description}",
    )
    appendLine("Cited region: ${launch.citedRegion.path}:${launch.citedRegion.startLine}-${launch.citedRegion.endLine}")
    appendLine("Delta: ${launch.packet.baseRevision}..${launch.packet.headRevision}")
    appendLine("Return a JSON object with claim_verdict (confirmed|refuted|unresolved) and citations as [{path, line}].")
    appendLine("A refuted verdict must cite the file:line construct that makes the code safe.")
    appendLine("Do not change the finding text, severity, or location.")
  }

  companion object {
    const val ISSUE_KEY: String = "code-review-verification"
  }
}

internal fun citedRegionOf(finding: ParallelReviewMergedFinding): ReviewCitedRegion? {
  val path = finding.repositoryPath ?: finding.location.substringBefore(':').takeIf { it.isNotBlank() } ?: return null
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

internal fun parseCitations(raw: Any?): List<ReviewFindingCitation> {
  val items = raw as? List<*> ?: return emptyList()
  return items.mapNotNull { item ->
    val map = JsonSupport.anyToStringAnyMap(item) ?: return@mapNotNull null
    val path = map["path"] as? String ?: return@mapNotNull null
    val line = intValue(map["line"]) ?: return@mapNotNull null
    runCatching { ReviewFindingCitation(path, line) }.getOrNull()
  }
}

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
