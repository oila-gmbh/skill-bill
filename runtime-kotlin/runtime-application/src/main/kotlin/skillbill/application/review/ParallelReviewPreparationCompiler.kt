package skillbill.application.review

import skillbill.application.review.model.DelegatedReviewLaunchRequest
import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.ports.review.ReviewBuildTestFactsPort
import skillbill.ports.review.ReviewGuidancePort
import skillbill.ports.review.ReviewLaneSelectionPort
import skillbill.ports.review.ReviewLearningsPort
import skillbill.ports.review.ReviewScopeResolverPort
import skillbill.ports.review.ReviewStackRoutingPort
import skillbill.ports.review.model.ReviewFactPorts
import skillbill.ports.review.model.ReviewScopeFacts
import skillbill.ports.review.model.ReviewStackRoutingFacts
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewRevision
import java.nio.file.Path
import java.security.MessageDigest

/** Compiles the already-resolved parallel-review facts into validated assignment-owned launches. */
internal object ParallelReviewPreparationCompiler {
  fun compile(
    input: ParallelReviewPreparationInput,
    budget: ReviewContextBudgetPolicy,
    envelopeValidator: ReviewContextEnvelopeValidator,
  ): List<DelegatedReviewLaunchRequest> {
    val parsedHunks = parseUnifiedDiff(input.diff)
    val parsedPaths = parsedHunks.mapTo(mutableSetOf()) { it.path }
    val hunks = parsedHunks + headerOnlyChanges(input.diff).filterNot { it.path in parsedPaths }
    require(hunks.isNotEmpty()) { "The authoritative review diff contains no parseable changed hunks." }
    val routes = specialistRoutes(input, hunks)
    val decisions = routes.map { route ->
      ReviewLaneDecision(
        route.lane,
        true,
        "selected non-empty ${route.rubric.area ?: "generic"} specialist lane",
        listOf("parallel-review", route.rubric.area ?: "generic"),
        route.ownedPaths,
      )
    }
    val revisionId = digest(input.diff)
    val preparation = prepareReview(input, hunks, routes, decisions, revisionId, budget, envelopeValidator)
    return launchRequests(input, preparation, routes, budget)
  }

  private fun specialistRoutes(
    input: ParallelReviewPreparationInput,
    hunks: List<ReviewChangedHunk>,
  ): List<SpecialistRoute> {
    val selectedRubrics = input.rubrics.mapNotNull { rubric ->
      val ownedPaths = ownedPathsFor(rubric, hunks)
      rubric.takeIf { ownedPaths.isNotEmpty() }?.let { SelectedRubric(it, ownedPaths) }
    }
    require(selectedRubrics.isNotEmpty()) { "Review routing selected no non-empty specialist lane." }
    return input.agents.flatMap { agentId ->
      selectedRubrics.map { selected ->
        val specialistId = selected.rubric.area ?: selected.rubric.rubricId
        SpecialistRoute("$agentId:$specialistId", agentId, selected.rubric, selected.ownedPaths)
      }
    }
  }

  @Suppress("LongParameterList")
  private fun prepareReview(
    input: ParallelReviewPreparationInput,
    hunks: List<ReviewChangedHunk>,
    routes: List<SpecialistRoute>,
    decisions: List<ReviewLaneDecision>,
    revisionId: String,
    budget: ReviewContextBudgetPolicy,
    envelopeValidator: ReviewContextEnvelopeValidator,
  ) = ReviewPreparationService(
    reviewFactPorts(input, hunks, decisions, revisionId),
    envelopeValidator,
    budget,
  ).prepare(
    ReviewPreparationRequest(
      reviewId = "code-review-parallel-$revisionId",
      reviewRevision = ReviewRevision(revisionId, 1),
      criteriaReferences = routes.associate { it.lane to listOf("independent branch-diff specialist review") },
    ),
  )

  private fun reviewFactPorts(
    input: ParallelReviewPreparationInput,
    hunks: List<ReviewChangedHunk>,
    decisions: List<ReviewLaneDecision>,
    revisionId: String,
  ): ReviewFactPorts {
    val scope = ReviewScopeFacts(
      "repo-root-realpath-v1:${input.repoRoot.toRealPath()}",
      "parallel-scope-base-$revisionId",
      "parallel-scope-head-$revisionId",
      "authoritative supplied parallel-review diff",
      hunks,
    )
    val routing = ReviewStackRoutingFacts(input.stack, input.stack, emptyList(), emptyList())
    return ReviewFactPorts(
      scope = object : ReviewScopeResolverPort {
        override fun resolveScope(reviewId: String) = scope
      },
      stackRouting = object : ReviewStackRoutingPort {
        override fun resolveStackRouting(scope: ReviewScopeFacts) = routing
      },
      guidance = object : ReviewGuidancePort {
        override fun resolveMatchedRules(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) =
          emptyList<skillbill.review.context.model.ReviewRuleReference>()
      },
      learnings = object : ReviewLearningsPort {
        override fun resolveLearnings(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) =
          emptyList<skillbill.review.context.model.ReviewLearningsReference>()
      },
      buildTestFacts = object : ReviewBuildTestFactsPort {
        override fun resolveBuildTestFacts(scope: ReviewScopeFacts) =
          emptyList<skillbill.review.context.model.ReviewBuildTestFact>()
      },
      laneSelection = object : ReviewLaneSelectionPort {
        override fun decideLanes(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) = decisions
      },
    )
  }

  private fun launchRequests(
    input: ParallelReviewPreparationInput,
    preparation: skillbill.application.review.model.ReviewPreparationResult,
    routes: List<SpecialistRoute>,
    budget: ReviewContextBudgetPolicy,
  ): List<DelegatedReviewLaunchRequest> {
    val routesByLane = routes.associateBy(SpecialistRoute::lane)
    return preparation.assignments.map { assignment ->
      val route = requireNotNull(routesByLane[assignment.lane]) {
        "Prepared assignment '${assignment.lane}' has no selected specialist route."
      }
      DelegatedReviewLaunchRequest(
        packet = preparation.packet,
        assignment = assignment,
        specialistContract = SPECIALIST_CONTRACT,
        rubrics = listOf(route.rubric),
        brokerId = "review-evidence-${assignment.digest}",
        budget = budget,
        agentId = route.agentId,
        workerKind = ReviewWorkerKind.GENERIC,
        repoRoot = input.repoRoot,
      )
    }
  }

  private fun ownedPathsFor(rubric: ReviewRubricProjection, hunks: List<ReviewChangedHunk>): List<String> {
    val area = rubric.area ?: return hunks.map { it.path }.distinct().sorted()
    if (area in REQUIRED_BASELINE_AREAS) return hunks.map { it.path }.distinct().sorted()
    val signals = AREA_SIGNALS[area].orEmpty()
    if (signals.isEmpty()) return emptyList()
    return hunks.filter { hunk ->
      val searchable = "${hunk.path}\n${hunk.content}".lowercase()
      signals.any(searchable::contains)
    }.map { it.path }.distinct().sorted()
  }

  private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.replace("\r\n", "\n").toByteArray())
    .joinToString("") { "%02x".format(it) }

  private fun parseUnifiedDiff(diff: String): List<ReviewChangedHunk> {
    val lines = diff.replace("\r\n", "\n").lines()
    val result = mutableListOf<ReviewChangedHunk>()
    var oldPath: String? = null
    var newPath: String? = null
    var index = 0
    val header = Regex(
      "^@@ -(?<oldStart>\\d+)(?:,(?<oldCount>\\d+))? " +
        "\\+(?<newStart>\\d+)(?:,(?<newCount>\\d+))? @@",
    )
    while (index < lines.size) {
      val line = lines[index]
      when {
        line.startsWith("--- ") -> oldPath = diffPath(line.removePrefix("--- "))
        line.startsWith("+++ ") -> newPath = diffPath(line.removePrefix("+++ "))
        line.startsWith("@@ ") -> {
          val match = requireNotNull(header.find(line)) { "Malformed unified-diff hunk header: $line" }
          val content = buildString {
            appendLine(line)
            index += 1
            while (index < lines.size && !lines[index].startsWith("@@ ") && !lines[index].startsWith("diff --git ")) {
              appendLine(lines[index])
              index += 1
            }
          }.removeSuffix("\n")
          val path = requireNotNull(newPath ?: oldPath) { "Unified-diff hunk has no file path." }
          val oldStart = requireNotNull(match.groups["oldStart"]).value
          val oldCount = match.groups["oldCount"]?.value.orEmpty()
          val newStart = requireNotNull(match.groups["newStart"]).value
          val newCount = match.groups["newCount"]?.value.orEmpty()
          result += ReviewChangedHunk(
            path,
            oldStart.toInt(),
            oldCount.ifBlank { "1" }.toInt(),
            newStart.toInt(),
            newCount.ifBlank { "1" }.toInt(),
            content,
          )
          continue
        }
      }
      index += 1
    }
    return result
  }

  private fun diffPath(raw: String): String? {
    val value = raw.substringBefore('\t')
    if (value == "/dev/null") return null
    return value.removePrefix("a/").removePrefix("b/")
  }

  private fun headerOnlyChanges(diff: String): List<ReviewChangedHunk> {
    val normalized = diff.replace("\r\n", "\n")
    val records = normalized.split(Regex("(?m)(?=^diff --git )"))
      .mapNotNull { record ->
        if (!record.startsWith("diff --git ")) return@mapNotNull null
        val lines = record.lines()
        val path = lines.firstNotNullOfOrNull { line ->
          line.takeIf { it.startsWith("+++ b/") }
            ?.removePrefix("+++ b/")
            ?.substringBefore('\t')
        } ?: DIFF_GIT_PATH.find(lines.first())?.groupValues?.get(1)
        path?.let { ReviewChangedHunk(it, 0, 0, 0, 0, record.trimEnd()) }
      }
      .distinctBy { it.path }
    if (records.isNotEmpty()) return records
    val lines = normalized.lines()
    return lines.mapIndexedNotNull { index, line ->
      if (!line.startsWith("+++ b/")) return@mapIndexedNotNull null
      val path = line.removePrefix("+++ b/").substringBefore('\t')
      val content = lines.drop(index + 1).takeWhile { !it.startsWith("diff --git ") }.joinToString("\n")
      ReviewChangedHunk(path, 0, 0, 0, 0, content)
    }.distinctBy { it.path }
  }

  private const val SPECIALIST_CONTRACT =
    "Use only the assignment-owned evidence surface. Return only F-XXX risk-register lines."
  private val DIFF_GIT_PATH = Regex("^diff --git (?:a/\\S+|\"a/.+\") (?:b/|\"b/)(.+?)\"?$")
  private val REQUIRED_BASELINE_AREAS = setOf("architecture", "platform-correctness")
  private val AREA_SIGNALS = mapOf(
    "performance" to listOf("performance", "allocation", "memory", "buffer", "bytes", "token", "batch", "blocking"),
    "security" to listOf("auth", "secret", "unsafe", "forbidden", "path", "process", "network", "input"),
    "testing" to listOf("test", "fixture", "assert", "mock", "fake"),
    "api-contracts" to listOf("schema", "contract", "request", "response", "serialization", "version"),
    "persistence" to listOf("database", "repository", "transaction", "migration", "checkpoint", "persist"),
    "reliability" to listOf("retry", "timeout", "shutdown", "runner", "launch", "failure", "telemetry"),
    "ui" to listOf("ui", "view", "compose", "render", "form", "navigation"),
    "ux-accessibility" to listOf("accessibility", "semantic", "focus", "keyboard", "localization", "label"),
  )
}

private data class SelectedRubric(val rubric: ReviewRubricProjection, val ownedPaths: List<String>)

private data class SpecialistRoute(
  val lane: String,
  val agentId: String,
  val rubric: ReviewRubricProjection,
  val ownedPaths: List<String>,
)

internal data class ParallelReviewPreparationInput(
  val diff: String,
  val stack: String?,
  val agents: List<String>,
  val repoRoot: Path,
  val rubrics: List<ReviewRubricProjection>,
)
