package skillbill.cli.scaffold

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.ScaffoldPayloadVersionMismatchError
import skillbill.error.UnknownSkillKindError
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScaffoldCommandRequestParserTest {
  @Test
  fun `parses horizontal skill request`() {
    val request = parseScaffoldCommandRequest(
      mapOf(
        "scaffold_payload_version" to "1.0",
        "kind" to "horizontal",
        "name" to "bill-foo",
        "description" to "do the foo",
        "content_body" to "## body",
        "subagent_specialists" to listOf("ui", "security"),
        "repo_root" to "/repo",
      ),
    )

    val horizontal = request as ScaffoldCommandRequest.HorizontalSkill
    assertEquals("bill-foo", horizontal.name)
    assertEquals("do the foo", horizontal.description)
    assertEquals("## body", horizontal.contentBody)
    assertEquals(listOf("ui", "security"), horizontal.subagentSpecialists)
    assertEquals(false, horizontal.suppressSubagents)
    assertEquals("1.0", horizontal.scaffoldPayloadVersion)
    assertEquals("/repo", horizontal.repoRoot)
  }

  @Test
  fun `parses platform pack request with specialist areas and routing override`() {
    val request = parseScaffoldCommandRequest(
      mapOf(
        "scaffold_payload_version" to "1.0",
        "kind" to "platform-pack",
        "platform" to "kotlin",
        "display_name" to "Kotlin",
        "specialist_areas" to listOf("ui", "security"),
        "routing_signals" to mapOf(
          "strong" to listOf(".kt"),
          "tie_breakers" to listOf("Prefer Kotlin"),
        ),
        "baseline_layers" to listOf(
          mapOf(
            "platform" to "kmp",
            "skill" to "bill-kmp-code-review",
            "scope" to "same-review-scope",
            "required" to true,
            "mode" to "kmp-baseline",
          ),
        ),
      ),
    )

    val pack = request as ScaffoldCommandRequest.PlatformPack
    assertEquals("kotlin", pack.platform)
    assertEquals("Kotlin", pack.displayName)
    assertEquals(listOf("ui", "security"), pack.specialistAreas)
    assertNull(pack.skeletonMode)
    assertEquals(listOf(".kt"), pack.routingSignals?.strong)
    assertEquals(listOf("Prefer Kotlin"), pack.routingSignals?.tieBreakers)
    assertEquals(1, pack.baselineLayers.size)
    val layer = pack.baselineLayers.single()
    assertEquals("kmp", layer.platform)
    assertEquals(CodeReviewCompositionScope.SameReviewScope, layer.scope)
    assertEquals(CodeReviewCompositionMode.KmpBaseline, layer.mode)
  }

  @Test
  fun `parses platform override piloted request`() {
    val request = parseScaffoldCommandRequest(
      mapOf(
        "scaffold_payload_version" to "1.0",
        "kind" to "platform-override-piloted",
        "platform" to "kotlin",
        "family" to "code-review",
      ),
    )

    val override = request as ScaffoldCommandRequest.PlatformOverride
    assertEquals("kotlin", override.platform)
    assertEquals("code-review", override.family)
  }

  @Test
  fun `parses code review area request`() {
    val request = parseScaffoldCommandRequest(
      mapOf(
        "scaffold_payload_version" to "1.0",
        "kind" to "code-review-area",
        "platform" to "kotlin",
        "area" to "security",
      ),
    )

    val area = request as ScaffoldCommandRequest.CodeReviewArea
    assertEquals("kotlin", area.platform)
    assertEquals("security", area.area)
  }

  @Test
  fun `parses add-on request`() {
    val request = parseScaffoldCommandRequest(
      mapOf(
        "scaffold_payload_version" to "1.0",
        "kind" to "add-on",
        "name" to "bill-grill",
        "platform" to "kotlin",
        "body" to "## body",
        "consumer_skill_dirs" to listOf("code-review/bill-kotlin-code-review"),
      ),
    )

    val addon = request as ScaffoldCommandRequest.AddOn
    assertEquals("bill-grill", addon.name)
    assertEquals("kotlin", addon.platform)
    assertEquals("## body", addon.body)
    assertEquals(listOf("code-review/bill-kotlin-code-review"), addon.consumerSkillDirs)
  }

  @Test
  fun `version mismatch throws ScaffoldPayloadVersionMismatchError`() {
    val error = assertFailsWith<ScaffoldPayloadVersionMismatchError> {
      parseScaffoldCommandRequest(
        mapOf("scaffold_payload_version" to "9.9", "kind" to "horizontal", "name" to "bill-foo"),
      )
    }
    assertTrue(error.message.orEmpty().contains("9.9"))
  }

  @Test
  fun `unknown kind throws UnknownSkillKindError`() {
    val error = assertFailsWith<UnknownSkillKindError> {
      parseScaffoldCommandRequest(
        mapOf("scaffold_payload_version" to "1.0", "kind" to "not-a-kind"),
      )
    }
    assertTrue(error.message.orEmpty().contains("not-a-kind"))
  }

  @Test
  fun `missing required field throws InvalidScaffoldPayloadError`() {
    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      parseScaffoldCommandRequest(
        mapOf("scaffold_payload_version" to "1.0", "kind" to "horizontal"),
      )
    }
    assertTrue(error.message.orEmpty().contains("'name'"))
  }

  @Test
  fun `wrong field type throws InvalidScaffoldPayloadError`() {
    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      parseScaffoldCommandRequest(
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "horizontal",
          "name" to "bill-foo",
          "no_subagents" to "true",
        ),
      )
    }
    assertTrue(error.message.orEmpty().contains("'no_subagents'"))
  }

  @Test
  fun `parser preserves unknown platform-override family verbatim for downstream validation`() {
    // The parser is a wire-shape adapter — it does NOT validate pre-shell family membership.
    // UnknownPreShellFamilyError is fired by the runtime orchestrator (planPlatformOverridePiloted
    // in ScaffoldService) once it sees the family is not in SHELLED_FAMILIES or PRE_SHELL_FAMILIES.
    // This test pins the contract: the parser preserves the raw family string verbatim on the
    // typed request, so the loud-fail still fires at the same semantic point downstream.
    val request = parseScaffoldCommandRequest(
      mapOf(
        "scaffold_payload_version" to "1.0",
        "kind" to "platform-override-piloted",
        "platform" to "kotlin",
        "family" to "not-a-family",
      ),
    )

    val override = request as ScaffoldCommandRequest.PlatformOverride
    assertEquals("not-a-family", override.family)
  }

  @Test
  fun `empty baseline_layers list loud-fails with InvalidScaffoldPayloadError`() {
    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      parseScaffoldCommandRequest(
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "platform-pack",
          "platform" to "kotlin",
          "routing_signals" to mapOf("strong" to listOf(".kt")),
          "baseline_layers" to emptyList<Map<String, Any?>>(),
        ),
      )
    }
    assertTrue("at least one layer" in error.message.orEmpty(), "Got: ${error.message}")
  }

  @Test
  fun `routing_signals strong field present but wrong type loud-fails`() {
    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      parseScaffoldCommandRequest(
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "platform-pack",
          "platform" to "kotlin",
          "routing_signals" to mapOf("strong" to "not-a-list"),
        ),
      )
    }
    val message = error.message.orEmpty()
    assertTrue("routing_signals.strong" in message, "Got: $message")
    assertTrue("must be a list of strings" in message, "Got: $message")
  }

  @Test
  fun `invalid baseline layer scope throws InvalidScaffoldPayloadError with field prefix`() {
    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      parseScaffoldCommandRequest(
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "platform-pack",
          "platform" to "kotlin",
          "routing_signals" to mapOf("strong" to listOf(".kt")),
          "baseline_layers" to listOf(
            mapOf(
              "platform" to "kmp",
              "skill" to "bill-kmp-code-review",
              "scope" to "bogus-scope",
              "required" to true,
              "mode" to "kmp-baseline",
            ),
          ),
        ),
      )
    }
    val message = error.message.orEmpty()
    assertTrue("baseline_layers[0].scope" in message, "Got: $message")
    assertTrue("bogus-scope" in message, "Got: $message")
  }
}
