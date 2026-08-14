package skillbill.application.review

import skillbill.contracts.JsonSupport
import skillbill.domain.review.context.model.SpecIntentProjection
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.GovernedReviewAdjudicationLaunch
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.context.model.ReviewSpecAdjudicationAdmission
import skillbill.review.context.model.ReviewSpecAdjudicationWorkerResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewStage
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration

data class ReviewSpecAdjudicationOutcome(
  val verdicts: List<ReviewFindingVerdict>,
  val envelopes: List<Map<String, Any?>> = emptyList(),
  val skipReason: String? = null,
)

class ReviewSpecAdjudicationRunner(
  private val launcher: GoalRunnerSubtaskLauncher,
  private val envelopeValidator: ReviewContextEnvelopeValidator,
) {
  @Suppress("LongParameterList")
  fun run(
    packet: ReviewContextPacket?,
    findings: List<ParallelReviewMergedFinding>,
    existingVerdicts: List<ReviewFindingVerdict>,
    projection: SpecIntentProjection?,
    budget: ReviewContextBudgetPolicy,
    brokerId: String,
    repoRoot: Path,
    timeout: Duration,
    modelOverride: String? = null,
  ): ReviewSpecAdjudicationOutcome {
    if (projection == null) {
      return ReviewSpecAdjudicationOutcome(verdicts = emptyList(), skipReason = SPEC_CONTEXT_NONE)
    }
    if (packet == null) {
      return ReviewSpecAdjudicationOutcome(verdicts = emptyList(), skipReason = SPEC_CONTEXT_NONE)
    }
    val stage1ByRef = existingVerdicts
      .filter { it.stage == ReviewStage.VERIFICATION }
      .associateBy { it.findingRef }
    val durableAdj = existingVerdicts
      .filter { it.stage == ReviewStage.ADJUDICATION }
      .associateBy { it.findingRef }
    val survivors = findings.sortedBy { it.fNumber }.filter { finding ->
      val stage1 = stage1ByRef[finding.fNumber] ?: return@filter false
      stage1.claimVerdict != ReviewClaimVerdict.REFUTED
    }
    if (survivors.isEmpty()) {
      return ReviewSpecAdjudicationOutcome(
        verdicts = durableAdj.values.toList(),
        skipReason = "no confirmed or unresolved findings to adjudicate",
      )
    }
    val pending = survivors.filterNot { it.fNumber in durableAdj.keys }
    if (pending.isEmpty()) {
      return ReviewSpecAdjudicationOutcome(
        verdicts = survivors.mapNotNull { durableAdj[it.fNumber] },
        skipReason = "every surviving finding already holds a durable adjudication verdict",
      )
    }
    val recordedAt = Instant.now().toString()
    val prepared = pending.map { finding ->
      prepareLaunch(
        packet = packet,
        finding = finding,
        stage1 = stage1ByRef.getValue(finding.fNumber),
        projection = projection,
        budget = budget,
        brokerId = brokerId,
        recordedAt = recordedAt,
      )
    }
    val envelopes = mutableListOf<Map<String, Any?>>()
    val launched = prepared.map { job ->
      when (job) {
        is PreparedAdjudication.Rejected -> job.verdict
        is PreparedAdjudication.Ready -> {
          envelopes += job.envelope
          launchOne(job, repoRoot, timeout, modelOverride, recordedAt)
        }
      }
    }
    return ReviewSpecAdjudicationOutcome(
      verdicts = survivors.mapNotNull { durableAdj[it.fNumber] } + launched,
      envelopes = envelopes,
    )
  }

  @Suppress("LongParameterList")
  private fun prepareLaunch(
    packet: ReviewContextPacket,
    finding: ParallelReviewMergedFinding,
    stage1: ReviewFindingVerdict,
    projection: SpecIntentProjection,
    budget: ReviewContextBudgetPolicy,
    brokerId: String,
    recordedAt: String,
  ): PreparedAdjudication {
    val region = citedRegionOf(finding)
      ?: return PreparedAdjudication.Rejected(
        ReviewFindingVerdict(
          stage = ReviewStage.ADJUDICATION,
          findingRef = finding.fNumber,
          claimVerdict = stage1.claimVerdict,
          scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
          recordedAt = recordedAt,
          rejectionReason = "finding has no cited file:line region",
        ),
      )
    val launch = GovernedReviewAdjudicationLaunch(
      packet = packet,
      finding = finding,
      stage1Verdict = stage1,
      specIntentProjection = projection,
      citedRegion = region,
      evidenceSurfaceRules = ReviewPreparationService.adjudicationEvidenceSurfaceRules(),
      dependencyAllowlist = ReviewDependencyAllowlist(packet.dependencyAllowlist.normalized),
      brokerId = brokerId,
      budget = budget,
    )
    val envelope = launch.toAdjudicationLaunchEnvelope().asWireMap()
    val launchBytes = JsonSupport.mapToJsonString(envelope).toByteArray(Charsets.UTF_8).size.toLong()
    if (launchBytes > budget.maxLaneLaunchBytes) {
      throw InvalidReviewContextSchemaError(
        sourceLabel = "review adjudication launch for ${finding.fNumber}",
        reason = "adjudication launch exceeded max_lane_launch_bytes",
        definitionName = "adjudication_launch",
      )
    }
    envelopeValidator.validate(envelope, "review adjudication launch for ${finding.fNumber}")
    return PreparedAdjudication.Ready(finding, stage1, projection, envelope, launch)
  }

  private fun launchOne(
    job: PreparedAdjudication.Ready,
    repoRoot: Path,
    timeout: Duration,
    modelOverride: String?,
    recordedAt: String,
  ): ReviewFindingVerdict {
    val prompt = adjudicationPrompt(job.launch)
    val outcome = launcher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = job.launch.brokerId,
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
        ReviewFindingVerdict(
          stage = ReviewStage.ADJUDICATION,
          findingRef = job.finding.fNumber,
          claimVerdict = job.stage1.claimVerdict,
          scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
          recordedAt = recordedAt,
          rejectionReason = "unsupported agent: ${outcome.reason}",
        )
      is AgentRunLaunchFacts -> fromLaunchFacts(job, outcome, recordedAt)
    }
  }

  private fun fromLaunchFacts(
    job: PreparedAdjudication.Ready,
    facts: AgentRunLaunchFacts,
    recordedAt: String,
  ): ReviewFindingVerdict {
    launchFailureReason(facts)?.let { reason ->
      return ReviewFindingVerdict(
        stage = ReviewStage.ADJUDICATION,
        findingRef = job.finding.fNumber,
        claimVerdict = job.stage1.claimVerdict,
        scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
        recordedAt = recordedAt,
        rejectionReason = reason,
      )
    }
    val worker = parseAdjudicationWorkerResult(facts.stdout)
    return ReviewSpecAdjudicationAdmission.admit(
      job.finding,
      job.stage1,
      job.projection,
      worker,
      recordedAt,
    )
  }

  private fun adjudicationPrompt(launch: GovernedReviewAdjudicationLaunch): String = buildString {
    appendLine("Adjudicate exactly one review finding against the spec intent projection.")
    appendLine("Do not inspect sibling findings.")
    appendLine("Do not re-test whether the finding's claim is true; stage 1 settled that.")
    appendLine("Review is read-only: do not build, compile, or run tests.")
    appendLine("Evidence surface: ${launch.evidenceSurfaceRules}")
    appendLine(
      "Finding ${launch.finding.fNumber}: ${launch.finding.severity.displayName} | " +
        "${launch.finding.location} | ${launch.finding.description}",
    )
    appendLine("Stage 1 verdict: ${launch.stage1Verdict.claimVerdict.wireValue}")
    appendLine("Cited region: ${launch.citedRegion.path}:${launch.citedRegion.startLine}-${launch.citedRegion.endLine}")
    appendLine("Return a JSON object with exactly one scope_disposition " +
      "(in_scope|out_of_scope_preexisting|spec_deviation|spec_accepted_tradeoff).")
    appendLine("Cite the justifying spec element for out_of_scope_preexisting, spec_deviation, or any severity adjustment.")
    appendLine("Do not change the finding text, severity, or location.")
  }

  companion object {
    const val ISSUE_KEY: String = "code-review-adjudication"
    const val SPEC_CONTEXT_NONE: String = "spec_context: none"
  }
}

private sealed class PreparedAdjudication {
  data class Ready(
    val finding: ParallelReviewMergedFinding,
    val stage1: ReviewFindingVerdict,
    val projection: SpecIntentProjection,
    val envelope: Map<String, Any?>,
    val launch: GovernedReviewAdjudicationLaunch,
  ) : PreparedAdjudication()

  data class Rejected(val verdict: ReviewFindingVerdict) : PreparedAdjudication()
}

internal fun parseAdjudicationWorkerResult(stdout: String): ReviewSpecAdjudicationWorkerResult? {
  val payload = parseJsonObject(stdout) ?: return null
  val finding = JsonSupport.anyToStringAnyMap(payload["finding"])
  val adjustment = JsonSupport.anyToStringAnyMap(payload["severity_adjustment"])
  val dispositionField = stringList(payload["scope_disposition"])
  val extraDispositions = stringList(payload["dispositions"])
  val primary = dispositionField.firstOrNull()
  return ReviewSpecAdjudicationWorkerResult(
    scopeDisposition = primary,
    dispositionValues = (dispositionField.drop(1) + extraDispositions),
    citedSpecElement = payload["cited_spec_element"] as? String,
    citations = parseCitations(payload["citations"]),
    severityAdjustmentDirection = adjustment?.get("direction") as? String,
    severityAdjustmentJustification = adjustment?.get("justification") as? String,
    adjustedSeverity = (adjustment?.get("adjusted_severity") as? String) ?: payload["adjusted_severity"] as? String,
    findingRef = (finding?.get("finding_ref") as? String) ?: payload["finding_ref"] as? String,
    severity = (finding?.get("severity") as? String) ?: payload["severity"] as? String,
    location = (finding?.get("location") as? String) ?: payload["location"] as? String,
    description = (finding?.get("description") as? String) ?: payload["description"] as? String,
  )
}

private fun stringList(raw: Any?): List<String> = when (raw) {
  is String -> listOf(raw)
  is List<*> -> raw.mapNotNull { it as? String }
  else -> emptyList()
}
