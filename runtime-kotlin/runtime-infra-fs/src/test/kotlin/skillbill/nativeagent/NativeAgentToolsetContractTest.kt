package skillbill.nativeagent

import skillbill.nativeagent.composition.NativeAgentCompositionKind
import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.composition.parseNativeAgentSourceFile
import skillbill.nativeagent.composition.parseNativeAgentSourceText
import skillbill.nativeagent.composition.parseNativeAgentTools
import skillbill.nativeagent.composition.renderNativeAgentBundle
import skillbill.nativeagent.composition.renderNativeAgentSource
import skillbill.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A declared toolset is the only mechanism that keeps unused tool schemas out of a worker's every
 * model turn, so it has to survive the bundle round-trip and reach the rendered provider frontmatter.
 */
class NativeAgentToolsetContractTest {
  @Test
  fun `a declared toolset round-trips through the bundle and reaches rendered frontmatter`() {
    val agent = NativeAgentSource(
      name = "fixture-reviewer",
      description = "Fixture reviewer.",
      body = "# Worker\n\nReview it.",
      tools = listOf("Read", "Grep", "Glob", "Bash"),
    )

    val bundle = renderNativeAgentBundle(listOf(agent))
    assertTrue(bundle.contains("tools: [Read, Grep, Glob, Bash]"), "Bundle render must emit the toolset: $bundle")

    val roundTripped = parseNativeAgentSourceText(renderNativeAgentSource(agent))
    assertEquals(listOf("Read", "Grep", "Glob", "Bash"), roundTripped.tools)

    val rendered = NativeAgentProvider.Claude.render(agent)
    assertTrue(rendered.contains("tools: Read, Grep, Glob, Bash"), "Claude render must emit the toolset: $rendered")

    // Cursor has no `tools` key, so the declaration has to reach its one capability control instead.
    // Emitting neither leaves the worker on the host default of every tool the parent can reach.
    val cursor = NativeAgentProvider.Cursor.render(agent)
    assertTrue(cursor.contains("readonly: true"), "Cursor render must project the read-only toolset: $cursor")
  }

  // `readonly: true` is a claim about the worker, so a toolset that grants mutation must not earn it.
  @Test
  fun `a toolset granting mutation is not projected as read-only`() {
    val agent = NativeAgentSource(
      name = "fixture-writer",
      description = "Fixture writer.",
      body = "# Worker\n\nWrite it.",
      tools = listOf("Read", "Write"),
    )

    assertFalse(NativeAgentProvider.Cursor.render(agent).contains("readonly:"))
  }

  @Test
  fun `an omitted toolset stays absent so the host default is preserved`() {
    val agent = NativeAgentSource(name = "fixture-open", description = "Fixture.", body = "# Worker\n\nWork.")

    assertTrue(agent.tools.isEmpty())
    assertFalse(NativeAgentProvider.Claude.render(agent).contains("tools:"))
    assertFalse(renderNativeAgentBundle(listOf(agent)).contains("tools:"))
    assertFalse(NativeAgentProvider.Cursor.render(agent).contains("readonly:"))
  }

  @Test
  fun `an empty or repeated toolset fails loudly rather than silently widening access`() {
    assertFailsWith<IllegalArgumentException> { parseNativeAgentTools(emptyList<String>(), "fixture") }
    assertFailsWith<IllegalArgumentException> { parseNativeAgentTools(listOf("Read", "Read"), "fixture") }
    assertFailsWith<IllegalArgumentException> { parseNativeAgentTools(listOf("Read", ""), "fixture") }
    assertFailsWith<IllegalArgumentException> { parseNativeAgentTools("Read", "fixture") }
  }

  @Test
  fun `every governed review worker declares a toolset without mutation or delegation`() {
    val root = repoRoot()
    val bundles = listOf(root.resolve("platform-packs"), root.resolve("skills")).flatMap { tree ->
      Files.walk(tree).use { paths -> paths.filter { it.fileName?.toString() == "agents.yaml" }.toList() }
    }
    assertTrue(bundles.isNotEmpty(), "Expected to discover native-agent bundles under $root")

    val reviewWorkers = bundles.flatMap { bundle -> parseNativeAgentSourceFile(bundle).map { bundle to it } }
      .filter { (_, agent) -> "code-review" in agent.name }
    assertTrue(reviewWorkers.isNotEmpty(), "Expected to discover governed review workers")

    reviewWorkers.forEach { (bundle, agent) ->
      assertTrue(
        agent.tools.isNotEmpty(),
        "${agent.name} in $bundle must declare a toolset; an undeclared worker inherits every host tool.",
      )
      val forbidden = agent.tools.filter { it in setOf("Edit", "Write", "NotebookEdit", "Agent") }
      assertEquals(
        emptyList(),
        forbidden,
        "${agent.name} must hold no mutation or delegation tool, but declares $forbidden",
      )
      assertTrue(
        NativeAgentProvider.Cursor.render(agent).contains("readonly: true"),
        "${agent.name} must render read-only on Cursor; Cursor has no tools key, so a declaration that " +
          "reaches no capability field hands the worker every tool the parent can reach.",
      )
    }
  }

  // Inline is the cheap tier only if its worker is the declared narrow-toolset agent; a
  // general-purpose substitute silently restores the whole host tool surface on every turn.
  @Test
  fun `the inline review worker is declared with the narrow reviewer toolset`() {
    val root = repoRoot()
    val bundle = root.resolve("skills/bill-code-review-inline/native-agents/agents.yaml")
    val inline = parseNativeAgentSourceFile(bundle).single { it.name == "bill-code-review-inline" }

    assertEquals(listOf("Read", "Grep", "Glob", "Bash"), inline.tools)
    // The body is composed from the internal skill's governed content, so the rubric lives in one
    // place rather than being duplicated into the bundle entry.
    assertEquals(NativeAgentCompositionKind.GovernedContent, inline.composition?.kind)
    val governed = Files.readString(root.resolve("skills/bill-code-review-inline/content.md"))
    assertTrue("internal-for: bill-code-review" in governed, "The inline worker must install as a sidecar")
    assertTrue("never launches one" in governed, "The governed content must forbid per-area fan-out")
  }

  private fun repoRoot(): Path {
    var candidate = Path.of("").toAbsolutePath()
    while (!Files.isDirectory(candidate.resolve("platform-packs"))) {
      candidate = candidate.parent ?: error("Could not locate the repository root from ${Path.of("").toAbsolutePath()}")
    }
    return candidate
  }
}
