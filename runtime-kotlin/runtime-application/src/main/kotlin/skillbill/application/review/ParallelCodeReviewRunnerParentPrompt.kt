package skillbill.application.review

import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal data class ParallelCodeReviewParentPromptRequest(
  val selected: List<ReviewSpecialistLaunchRequest>,
  val routedManifests: List<PlatformManifest>,
  val resolvedMode: ResolvedReviewExecutionMode,
  val agentId: String,
  val baseRevision: String? = null,
  val headRevision: String? = null,
  val specPath: Path? = null,
)

object ParallelCodeReviewRunnerParentPrompt {
  internal fun build(request: ParallelCodeReviewParentPromptRequest): String {
    val selected = request.selected
    val resolvedMode = request.resolvedMode
    val inline = resolvedMode == ResolvedReviewExecutionMode.INLINE
    return buildString {
      append(modeFraming(resolvedMode))
      appendCursorDelegatedFanOut(selected, resolvedMode, request.agentId)
      appendLine("Detected stack: ${request.routedManifests.joinToString("+") { it.slug }.ifBlank { "generic" }}")
      val rubricLabel = selected.joinToString { launch ->
        val decision = launch.assignment.laneDecision
        "${decision.specialistSkillName}" +
          "[lane=${decision.lane};add-ons=${decision.addOns.joinToString("+").ifBlank { "none" }}]"
      }.ifBlank { "code-review" }
      appendLine("Authoritative routed rubric identities: $rubricLabel")
      selected.forEach { launch ->
        val decision = launch.assignment.laneDecision
        appendLine()
        appendLine("## Resolved rubric: ${decision.specialistSkillName}")
        appendLine("Review lane: ${decision.lane}")
        launch.rubrics.forEach { rubric -> appendLine(rubric.body) }
      }
      val resolvedBase = request.baseRevision ?: selected.firstOrNull()?.packet?.baseRevision ?: "unspecified"
      val resolvedHead = request.headRevision ?: selected.firstOrNull()?.packet?.headRevision ?: "unspecified"
      appendLine(
        "The immutable review pair is base=$resolvedBase " +
          "target=$resolvedHead. " +
          "The governing spec is ${request.specPath ?: "the resolved spec projection"}. " +
          "Read committed content on demand " +
          "through the bound broker with read_evidence and request_expansion, or with bounded and paged Git reads. " +
          "Do not expect paths, hunk spans, or hunk bodies in this launch prompt.",
      )
      appendLine(if (inline) PARALLEL_REVIEW_INLINE_DEPTH_DIRECTIVE else PARALLEL_REVIEW_DELEGATED_DEPTH_DIRECTIVE)
      appendLine(
        "Return free-form review prose and end with an explicit verdict line: " +
          "`verdict: approved` or `verdict: changes_requested` (needs_fix is accepted as changes_requested). " +
          "There is no findings-register format gate and no $PARALLEL_REVIEW_NO_FINDINGS_TOKEN requirement — " +
          "missing or imperfect register lines never fail the review.",
      )
      appendLine(
        "When you have concrete defects, also emit optional `[F-XXX]` register lines so claim " +
          "verification can re-check them: " +
          "'[F-XXX] Severity | Confidence | specialist=<skill name from Resolved rubric> | " +
          "commits=<sha>[,<sha>] | path=\"<repo-relative path>\" | line=<positive integer> | description'. " +
          "Use only the bare skill name for specialist — never copy the [paths=...;add-ons=...;origins=...] " +
          "annotation from the routed rubric catalog. Imperfect lines remain part of the prose result " +
          "and never block settlement; parsed lines are optional verification enrichment.",
      )
      appendLine()
      selected.forEach { launch ->
        val decision = launch.assignment.laneDecision
        appendLine("## Assigned bundle: ${decision.specialistSkillName}")
        appendLine("Lane: ${decision.lane}")
        appendLine(
          "Read the committed base-to-target revision pair on demand through read_evidence or bounded, " +
            "paged Git reads. Keep routing and coverage metadata private to the runtime.",
        )
      }
    }
  }

  private fun StringBuilder.appendCursorDelegatedFanOut(
    selected: List<ReviewSpecialistLaunchRequest>,
    resolvedMode: ResolvedReviewExecutionMode,
    agentId: String,
  ) {
    if (agentId != "cursor" || resolvedMode != ResolvedReviewExecutionMode.DELEGATED) return
    val nativeLanes = selected
      .filter { it.workerKind == ReviewWorkerKind.PROVIDER_NATIVE }
      .mapNotNull { it.logicalWorkerName }
      .distinct()
    if (nativeLanes.isEmpty()) return
    appendLine()
    appendLine(
      "Launch these specialist lanes in parallel in one instruction: " +
        nativeLanes.joinToString(", ") +
        ". Invoke each lane with one /name line:",
    )
    nativeLanes.forEach { logicalName -> appendLine("/$logicalName") }
  }

  private fun modeFraming(resolvedMode: ResolvedReviewExecutionMode): String = buildString {
    if (resolvedMode == ResolvedReviewExecutionMode.INLINE) {
      appendLine("Run exactly one bill-code-review mode:inline review prompt in this context.")
      appendLine("Resolved execution mode: inline")
      appendLine(
        "Depth: reduced. Merge the routed areas below into one combined checklist and traverse the " +
          "diff exactly once against it, holding all areas in mind simultaneously, under a bounded " +
          "budget. Never re-walk the diff once per area; coverage is accounted per area in your " +
          "output, not by separate passes. This is not equivalent coverage to a full per-specialist " +
          "review and must not be presented as one; state that specialist depth was not applied.",
      )
    } else {
      appendLine("Run one bill-code-review mode:delegated review over the routed specialist fan-out.")
      appendLine("Resolved execution mode: delegated")
      appendLine(
        "Depth: full. Launch one specialist worker per resolved rubric below. Pass each specialist's " +
          "raw return through unchanged — do not require a register shape from them. You alone author " +
          "the final review prose and verdict from whatever they returned.",
      )
    }
  }
}
