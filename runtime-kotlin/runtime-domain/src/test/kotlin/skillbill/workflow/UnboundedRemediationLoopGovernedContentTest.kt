package skillbill.workflow

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKILL-157 AC-001 to AC-005: the governed feature-task and goal surfaces describe the semantic
 * remediation loops as unbounded with an advisory warning threshold, and that documented threshold is
 * bound to the runtime declaration rather than duplicated as prose-side literals.
 */
class UnboundedRemediationLoopGovernedContentTest {
  private val threshold = FeatureTaskRuntimePhaseWorkflowDefinition.SEMANTIC_LOOP_WARNING_THRESHOLD
  private val crossingIteration = threshold + 1

  // Governed prose is hard-wrapped, so assert against a whitespace-normalized view; a reflow must not
  // break a contract assertion.
  private fun governedText(path: String): String =
    Files.readString(remediationRepoRoot().resolve(path)).replace(Regex("\\s+"), " ")

  private val surfacePaths = listOf(
    "skills/bill-feature-task-runtime/content.md",
    "skills/bill-feature-task-prose/content.md",
    "skills/bill-feature-task-prose/native-agents/agents.yaml",
    "skills/bill-feature-goal/content.md",
    "skills/bill-feature-task-subtask-runner/content.md",
  )

  // `review_cap_disposition` survives as a durable legacy field name, so the denylist targets cap
  // *statements* the run would obey, never the identifier that carries decodable legacy state.
  private val legacyCapPhrases = listOf(
    "two-pass",
    "two pass",
    "two total review passes",
    "pass three",
    "third pass",
    "one remediation iteration",
    "single re-review",
    "single possible",
    "at most one inline re-review",
    "review_cap_reached",
    "permits one inline remediation pass",
  )

  @Test
  fun `no governed surface states a finite review or audit remediation cap`() {
    surfacePaths.forEach { path ->
      val text = governedText(path).lowercase()
      legacyCapPhrases.forEach { phrase ->
        assertFalse(
          text.contains(phrase),
          "$path must not state a remediation cap; found legacy phrase '$phrase'.",
        )
      }
    }
  }

  @Test
  fun `every surface documents the advisory threshold bound to the runtime declaration`() {
    surfacePaths.forEach { path ->
      val text = governedText(path)
      assertTrue(
        text.contains("iteration $threshold to iteration $crossingIteration"),
        "$path must name the crossing from iteration $threshold to iteration $crossingIteration.",
      )
      assertTrue(
        text.contains("advisory threshold of $threshold was exceeded and remediation continues"),
        "$path must state that crossing the advisory threshold of $threshold continues remediation.",
      )
    }
  }

  @Test
  fun `the runtime and prose surfaces require remediation to continue until blocking items clear`() {
    val runtime = governedText("skills/bill-feature-task-runtime/content.md")
    assertTrue(runtime.contains("carries no iteration cap: it continues while any Blocker finding remains"))
    assertTrue(runtime.contains("repair and re-audit continue while any blocking gap remains"))
    assertTrue(runtime.contains("The threshold is a warning signal, not a cap"))

    val prose = governedText("skills/bill-feature-task-prose/content.md")
    assertTrue(prose.contains("keep repairing and re-reviewing while any Blocker finding remains"))
    assertTrue(prose.contains("no pass number ends the loop"))
    assertTrue(prose.contains("advance only after every Blocker finding clears"))
  }

  @Test
  fun `pass one keeps its selected mode and later passes stay inline and remediation-scoped`() {
    val runtime = governedText("skills/bill-feature-task-runtime/content.md")
    assertTrue(runtime.contains("review pass one uses the selected delegated mode and every later remediation"))
    assertTrue(runtime.contains("bill-code-review mode:inline context:feature-remediation"))

    val runner = governedText("skills/bill-feature-task-subtask-runner/content.md")
    assertTrue(runner.contains("For every later pass, call `bill-code-review mode:inline context:feature-remediation`"))

    val nativeAgents = governedText("skills/bill-feature-task-prose/native-agents/agents.yaml")
    assertTrue(nativeAgents.contains("for every later review run `bill-code-review mode:inline`"))
  }

  @Test
  fun `non-blocking severities keep their unchanged behavior on every rewritten surface`() {
    assertTrue(
      governedText("skills/bill-feature-task-prose/content.md")
        .contains("Continue past Major, Minor, and Nit findings while preserving them as review evidence"),
    )
    assertTrue(
      governedText("skills/bill-feature-task-prose/native-agents/agents.yaml")
        .contains("Major, Minor, and Nit findings never block and never stop the workflow"),
    )
    assertTrue(
      governedText("skills/bill-feature-goal/content.md")
        .contains("Major, Minor, and Nit findings remain durable evidence and never prevent advancement"),
    )
  }

  @Test
  fun `both semantic backward edges carry no finite per-edge cap`() {
    val semanticLoopIds = setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
      FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
    )
    val semanticEdges = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.backwardEdges
      .filter { it.loopId in semanticLoopIds }

    assertEquals(
      semanticLoopIds,
      semanticEdges.map { it.loopId }.toSet(),
      "both semantic remediation edges must be declared",
    )
    semanticEdges.forEach { edge ->
      assertNull(
        edge.perEdgeCap,
        "the ${edge.loopId} edge must stay unbounded; a finite per-edge cap reintroduces a count-based stop.",
      )
      assertEquals(
        threshold,
        edge.warnAfterIterations,
        "the ${edge.loopId} edge must warn at the shared threshold.",
      )
    }
  }

  @Test
  fun `the unrelated bounded regeneration edges stay capped`() {
    val regenerationEdges = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.backwardEdges
      .filter { FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(it.loopId) }

    assertEquals(3, regenerationEdges.size, "each planning producer keeps its own regeneration edge")
    regenerationEdges.forEach { edge ->
      assertEquals(
        FeatureTaskRuntimePhaseWorkflowDefinition.MAX_RECORD_REGENERATION_ATTEMPTS,
        edge.perEdgeCap,
        "the ${edge.loopId} regeneration edge must remain bounded.",
      )
    }
    assertEquals(2, FeatureTaskRuntimePhaseWorkflowDefinition.MAX_RECORD_REGENERATION_ATTEMPTS)
  }
}

private fun remediationRepoRoot(): Path {
  var current = Path.of("").toAbsolutePath().normalize()
  while (current.parent != null) {
    if (
      Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts")) &&
      Files.isDirectory(current.resolve("orchestration/contracts"))
    ) {
      return current
    }
    current = current.parent
  }
  error("Could not locate skill-bill repo root from ${Path.of("").toAbsolutePath().normalize()}")
}
