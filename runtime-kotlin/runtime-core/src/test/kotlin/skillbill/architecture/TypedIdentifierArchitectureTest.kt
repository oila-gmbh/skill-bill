package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypedIdentifierArchitectureTest {
  @Test
  fun `runtime ports and application have an empty primitive identifier census`() {
    val violations = productionSourceFiles().flatMap { sourceFile ->
      primitiveIdentifierViolations(
        sourceFile.relativePath,
        sourceFile.source,
      )
    }
    assertEquals(emptyList(), violations)
  }

  @Test
  fun `primitive identity fixture catches aliases nullable collections callbacks and generators`() {
    val fixture = """
      typealias WorkflowText = String
      data class Invalid(
        val parentWorkflowId: String?,
        val issueKeys: List<String>,
        val workflowCallback: (String) -> String,
      )
      fun generateWorkflowId(): String = "workflow"
      fun resolveAgentId(): String = "agent"
    """.trimIndent()
    val violations = primitiveIdentifierViolations("fixture.kt", fixture)
    assertEquals(
      setOf("parentWorkflowId", "issueKeys", "workflowCallback", "generateWorkflowId", "resolveAgentId", "WorkflowText"),
      violations.map { it.substringAfterLast("::") }.toSet(),
    )
  }

  @Test
  fun `typed identity fixture accepts typed signatures and unrelated strings`() {
    val fixture = """
      data class Valid(
        val workflowId: WorkflowId,
        val issueKey: IssueKey,
        val labels: List<String>,
        val repositoryId: String,
      )
      fun generateWorkflowId(): WorkflowId = WorkflowId("workflow")
      fun renderRepositoryId(): String = "repository"
    """.trimIndent()
    assertEquals(emptyList(), primitiveIdentifierViolations("fixture.kt", fixture))
  }

  @Test
  fun `conversion fixture rejects inner extraction and custom identifier parsing`() {
    val invalid = """
      fun WorkflowId.asText(): String = value
      fun unwrapReviewRunId(value: ReviewRunId): String = value.value
      fun parseIssueKey(value: String): IssueKey = IssueKey(value)
    """.trimIndent()
    val violations = conversionViolations(invalid)
    assertEquals(
      setOf("asText", "unwrapReviewRunId", "parseIssueKey"),
      violations.map { it.substringAfterLast("::") }.toSet(),
    )
    assertEquals(emptyList(), conversionViolations("fun mintReviewRunId(): ReviewRunId = ReviewRunId(\"new\")"))
  }

  @Test
  fun `six identifier declarations retain inline backing types without custom conversion helpers`() {
    val expected = mapOf(
      "WorkflowId" to "runtime-domain/src/main/kotlin/skillbill/workflow/engine/model/WorkflowId.kt",
      "SessionId" to "runtime-domain/src/main/kotlin/skillbill/workflow/engine/model/SessionId.kt",
      "IssueKey" to "runtime-domain/src/main/kotlin/skillbill/workflow/decomposition/model/IssueKey.kt",
      "SubtaskId" to "runtime-domain/src/main/kotlin/skillbill/workflow/decomposition/model/SubtaskId.kt",
      "ReviewRunId" to "runtime-domain/src/main/kotlin/skillbill/review/model/ReviewRunId.kt",
      "AgentId" to "runtime-domain/src/main/kotlin/skillbill/agent/model/AgentId.kt",
    )
    expected.forEach { (name, relativePath) ->
      val source = ArchitectureScanSupport.runtimeRoot.resolve(relativePath).toFile().readText()
      assertContains(source, "@JvmInline")
      assertContains(source, "value class $name")
      assertFalse(Regex("fun[[:space:]]+toString[[:space:]]*\\(").containsMatchIn(source))
      assertFalse(Regex("fun[[:space:]]+parse[[:space:]]*\\(").containsMatchIn(source))
      assertTrue(source.contains("val value:"), name)
    }
  }

  private data class SourceFile(val relativePath: String, val source: String)

  private fun productionSourceFiles(): List<SourceFile> = listOf(
    "runtime-ports/src/main/kotlin",
    "runtime-application/src/main/kotlin",
  ).flatMap { root ->
    ArchitectureScanSupport.kotlinFilesUnder(ArchitectureScanSupport.runtimeRoot.resolve(root)).map { path ->
      SourceFile(ArchitectureScanSupport.runtimeRoot.relativize(path).toString(), path.toFile().readText())
    }
  }

  private fun primitiveIdentifierViolations(relativePath: String, source: String): List<String> {
    val code = source
      .replace(Regex("(?s)/\\*.*?\\*/"), " ")
      .replace(Regex("(?m)//.*$"), " ")
      .replace(Regex("\\s+"), " ")
    val names = IDENTIFIER_NAMES.joinToString("|")
    val primitive = "(?:String(?:\\?)?|(?:List|Set|Collection)<String(?:\\?)?>|Map<String[^>]*>)"
    val violations = mutableListOf<String>()
    Regex("\\b($names)\\s*:\\s*$primitive").findAll(code).forEach { match ->
      violations += "$relativePath::${match.groupValues[1]}"
    }
    Regex("\\b(\\w*(?:WorkflowId|SessionId|IssueKey|SubtaskId|ReviewRunId|AgentId)\\w*)\\s*:\\s*\\([^)]*\\)\\s*->\\s*String").findAll(code)
      .forEach { match -> violations += "$relativePath::${match.groupValues[1]}" }
    Regex("\\bfun\\s+(\\w*(?:WorkflowId|SessionId|IssueKey|SubtaskId|ReviewRunId|AgentId)\\w*)\\s*\\([^)]*\\)\\s*:\\s*$primitive").findAll(code)
      .forEach { match -> violations += "$relativePath::${match.groupValues[1]}" }
    Regex("\\bfun\\s+(\\w*(?:WorkflowId|SessionId|IssueKey|SubtaskId|ReviewRunId|AgentId)\\w*)\\s*\\([^)]*\\)\\s*=").findAll(code)
      .filter { match -> !code.substring(match.range.first, match.range.last + 1).contains(":") }
      .forEach { match -> violations += "$relativePath::${match.groupValues[1]}" }
    Regex("\\btypealias\\s+(\\w*(?:Workflow|Session|Issue|Subtask|ReviewRun|Agent)\\w*)\\s*=\\s*String").findAll(code)
      .forEach { match -> violations += "$relativePath::${match.groupValues[1]}" }
    return violations.distinct().sorted()
  }

  private fun conversionViolations(source: String): List<String> =
    Regex("\\bfun\\s+(\\w*(?:toString|parse|asString|asText|unwrap)\\w*)\\s*\\(")
      .findAll(source)
      .map { "fixture::${it.groupValues[1]}" }
      .toList()

  private companion object {
    val IDENTIFIER_NAMES = setOf(
      "workflowId",
      "parentWorkflowId",
      "parentGoalWorkflowId",
      "knownWorkflowId",
      "activityWorkflowId",
      "activityParentWorkflowId",
      "workflowIds",
      "sessionId",
      "issueKey",
      "normalizedIssueKey",
      "issueKeys",
      "subtaskId",
      "subtaskIds",
      "reviewRunId",
      "reviewRunIds",
      "agentId",
      "finalizingAgentId",
      "resolvedAgentId",
      "invokedAgentId",
      "participatingAgentIds",
    )
  }
}
