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
    val diff = input.diff
    val agents = input.agents
    val hunks = parseUnifiedDiff(diff).ifEmpty { headerOnlyChanges(diff) }
    require(hunks.isNotEmpty()) { "The authoritative review diff contains no parseable changed hunks." }
    val paths = hunks.map { it.path }.distinct().sorted()
    val decisions = agents.map {
      ReviewLaneDecision(it, true, "selected parallel review lane", listOf("parallel-review"), paths)
    }
    val revisionId = digest(diff)
    val scope = ReviewScopeFacts(
      "repo-root-realpath-v1:${input.repoRoot.toRealPath()}",
      "parallel-scope-base-$revisionId",
      "parallel-scope-head-$revisionId",
      "authoritative supplied parallel-review diff",
      hunks,
    )
    val routing = ReviewStackRoutingFacts(input.stack, input.stack, emptyList(), emptyList())
    val preparation = ReviewPreparationService(
      ReviewFactPorts(
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
      ),
      envelopeValidator,
      budget,
    ).prepare(
      ReviewPreparationRequest(
        reviewId = "code-review-parallel-$revisionId",
        reviewRevision = ReviewRevision(revisionId, 1),
        criteriaReferences = agents.associateWith { listOf("independent branch-diff review") },
      ),
    )
    return preparation.assignments.map { assignment ->
      DelegatedReviewLaunchRequest(
        packet = preparation.packet,
        assignment = assignment,
        specialistContract = SPECIALIST_CONTRACT,
        rubrics = listOf(input.rubric),
        brokerId = "review-evidence-${assignment.digest}",
        budget = budget,
        agentId = assignment.lane,
        workerKind = ReviewWorkerKind.GENERIC,
        repoRoot = input.repoRoot,
      )
    }
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
}

internal data class ParallelReviewPreparationInput(
  val diff: String,
  val stack: String?,
  val agents: List<String>,
  val repoRoot: Path,
  val rubric: ReviewRubricProjection,
)
