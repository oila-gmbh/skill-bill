package skillbill.scaffold

import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.command.RoutingSignalsInput
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-52.2 subtask 2 (TEST-T01): byte-equivalence tests for
 * `ScaffoldCommandRequest.toRawScaffoldPayload()`. This bridge re-materialises the typed request
 * back into the legacy raw `Map<String, Any?>` payload shape so the existing
 * `scaffoldWithAdapters(...)` orchestrator path keeps working (AC4 byte-equivalent scaffold
 * outputs). These tests pin the omission rules so a regression cannot silently introduce a
 * "$key=$default" entry that the legacy seam used to drop.
 */
class ScaffoldCommandRequestRawPayloadTest {
  @Test
  fun `horizontal skill emits canonical fields with omission rules`() {
    val raw = ScaffoldCommandRequest.HorizontalSkill(
      name = "bill-foo",
      description = "do the foo",
      contentBody = "## body",
      subagentSpecialists = listOf("ui"),
      suppressSubagents = false,
      scaffoldPayloadVersion = "1.0",
      repoRoot = "/repo",
    ).toRawScaffoldPayload()

    assertEquals("1.0", raw["scaffold_payload_version"])
    assertEquals("/repo", raw["repo_root"])
    assertEquals("horizontal", raw["kind"])
    assertEquals("bill-foo", raw["name"])
    assertEquals("do the foo", raw["description"])
    assertEquals("## body", raw["content_body"])
    assertEquals(listOf("ui"), raw["subagent_specialists"])
    // suppressSubagents=false MUST NOT emit no_subagents (legacy seam parity)
    assertFalse("no_subagents" in raw)
  }

  @Test
  fun `horizontal skill omits blank description and absent content_body`() {
    val raw = ScaffoldCommandRequest.HorizontalSkill(
      name = "bill-foo",
      description = "",
      contentBody = null,
      subagentSpecialists = emptyList(),
      suppressSubagents = true,
      scaffoldPayloadVersion = "1.0",
      repoRoot = null,
    ).toRawScaffoldPayload()

    assertFalse("description" in raw, "blank description must be omitted, got: $raw")
    assertFalse("content_body" in raw, "null content_body must be omitted")
    assertFalse("repo_root" in raw, "null repo_root must be omitted")
    // Empty subagent_specialists for HorizontalSkill is also omitted (it is a List<String>, not
    // List<String>? — and the legacy seam emitted nothing when the list was empty).
    assertFalse("subagent_specialists" in raw)
    assertEquals(true, raw["no_subagents"])
  }

  @Test
  fun `platform pack emits routing signals with one-side-null encoding`() {
    val raw = ScaffoldCommandRequest.PlatformPack(
      platform = "kotlin",
      displayName = "Kotlin",
      description = "",
      skeletonMode = "starter",
      specialistAreas = null,
      routingSignals = RoutingSignalsInput(strong = listOf(".kt"), tieBreakers = null),
      baselineLayers = emptyList(),
      subagentSpecialists = null,
      suppressSubagents = false,
      contentBody = null,
      nameOverride = null,
      scaffoldPayloadVersion = "1.0",
      repoRoot = null,
    ).toRawScaffoldPayload()

    assertEquals("platform-pack", raw["kind"])
    assertEquals("kotlin", raw["platform"])
    assertEquals("Kotlin", raw["display_name"])
    assertEquals("starter", raw["skeleton_mode"])
    assertFalse("specialist_areas" in raw)
    val routing = raw["routing_signals"] as Map<*, *>
    assertEquals(listOf(".kt"), routing["strong"])
    assertFalse("tie_breakers" in routing, "null tieBreakers must be omitted from routing block")
    assertFalse("baseline_layers" in raw, "empty baseline layers must be omitted")
    assertFalse("subagent_specialists" in raw, "null subagentSpecialists must be omitted")
    assertFalse("no_subagents" in raw)
    assertFalse("name" in raw, "null nameOverride must not be emitted as 'name'")
  }

  @Test
  fun `platform pack distinguishes null vs empty subagentSpecialists`() {
    val withNull = ScaffoldCommandRequest.PlatformPack(
      platform = "kotlin",
      displayName = "Kotlin",
      description = "",
      skeletonMode = "full",
      specialistAreas = null,
      routingSignals = null,
      baselineLayers = emptyList(),
      subagentSpecialists = null,
      suppressSubagents = false,
      contentBody = null,
      nameOverride = null,
      scaffoldPayloadVersion = "1.0",
    ).toRawScaffoldPayload()

    val withEmpty = ScaffoldCommandRequest.PlatformPack(
      platform = "kotlin",
      displayName = "Kotlin",
      description = "",
      skeletonMode = "full",
      specialistAreas = null,
      routingSignals = null,
      baselineLayers = emptyList(),
      subagentSpecialists = emptyList(),
      suppressSubagents = false,
      contentBody = null,
      nameOverride = null,
      scaffoldPayloadVersion = "1.0",
    ).toRawScaffoldPayload()

    assertFalse("subagent_specialists" in withNull, "null variant must omit subagent_specialists")
    // Empty list still emitted — null vs empty distinction is preserved by the bridge
    assertTrue("subagent_specialists" in withEmpty, "empty list variant must emit empty list")
    assertEquals(emptyList<String>(), withEmpty["subagent_specialists"])
  }

  @Test
  fun `platform pack preserves baseline layer ordering`() {
    val layerA = CodeReviewBaselineLayer(
      platform = "kotlin",
      skill = "bill-kotlin-code-review",
      scope = CodeReviewCompositionScope.SameReviewScope,
      required = true,
      mode = CodeReviewCompositionMode.KmpBaseline,
    )
    val layerB = CodeReviewBaselineLayer(
      platform = "kmp",
      skill = "bill-kmp-code-review",
      scope = CodeReviewCompositionScope.SameReviewScope,
      required = false,
      mode = CodeReviewCompositionMode.KmpBaseline,
    )
    val raw = ScaffoldCommandRequest.PlatformPack(
      platform = "androidx",
      displayName = "AndroidX",
      description = "",
      skeletonMode = "starter",
      specialistAreas = null,
      routingSignals = RoutingSignalsInput(strong = listOf("androidx"), tieBreakers = null),
      baselineLayers = listOf(layerA, layerB),
      subagentSpecialists = null,
      suppressSubagents = false,
      contentBody = null,
      nameOverride = null,
      scaffoldPayloadVersion = "1.0",
    ).toRawScaffoldPayload()

    @Suppress("UNCHECKED_CAST")
    val emitted = raw["baseline_layers"] as List<Map<String, Any?>>
    assertEquals(2, emitted.size)
    assertEquals("kotlin", emitted[0]["platform"])
    assertEquals("bill-kotlin-code-review", emitted[0]["skill"])
    assertEquals("same-review-scope", emitted[0]["scope"])
    assertEquals(true, emitted[0]["required"])
    assertEquals("kmp-baseline", emitted[0]["mode"])
    assertEquals("kmp", emitted[1]["platform"])
    assertEquals(false, emitted[1]["required"])
  }

  @Test
  fun `platform pack carries nameOverride into 'name' wire field`() {
    val raw = ScaffoldCommandRequest.PlatformPack(
      platform = "kotlin",
      displayName = "Kotlin",
      description = "",
      skeletonMode = "full",
      specialistAreas = null,
      routingSignals = null,
      baselineLayers = emptyList(),
      subagentSpecialists = null,
      suppressSubagents = false,
      contentBody = null,
      nameOverride = "bill-kotlin-custom",
      scaffoldPayloadVersion = "1.0",
    ).toRawScaffoldPayload()

    assertEquals("bill-kotlin-custom", raw["name"])
  }

  @Test
  fun `platform pack omits blank displayName`() {
    val raw = ScaffoldCommandRequest.PlatformPack(
      platform = "kotlin",
      displayName = "",
      description = "",
      skeletonMode = "full",
      specialistAreas = null,
      routingSignals = null,
      baselineLayers = emptyList(),
      subagentSpecialists = null,
      suppressSubagents = false,
      contentBody = null,
      nameOverride = null,
      scaffoldPayloadVersion = "1.0",
    ).toRawScaffoldPayload()

    assertFalse("display_name" in raw, "blank displayName must be omitted")
  }

  @Test
  fun `platform override emits family and omits null optional fields`() {
    val raw = ScaffoldCommandRequest.PlatformOverride(
      platform = "kotlin",
      family = "quality-check",
      description = "qc override",
      contentBody = null,
      subagentSpecialists = null,
      suppressSubagents = false,
      nameOverride = null,
      scaffoldPayloadVersion = "1.0",
    ).toRawScaffoldPayload()

    assertEquals("platform-override-piloted", raw["kind"])
    assertEquals("kotlin", raw["platform"])
    assertEquals("quality-check", raw["family"])
    assertEquals("qc override", raw["description"])
    assertFalse("content_body" in raw)
    assertFalse("subagent_specialists" in raw)
    assertFalse("no_subagents" in raw)
    assertFalse("name" in raw)
  }

  @Test
  fun `code review area emits canonical fields`() {
    val raw = ScaffoldCommandRequest.CodeReviewArea(
      platform = "kotlin",
      area = "security",
      description = "",
      contentBody = null,
      nameOverride = null,
      scaffoldPayloadVersion = "1.0",
    ).toRawScaffoldPayload()

    assertEquals("code-review-area", raw["kind"])
    assertEquals("kotlin", raw["platform"])
    assertEquals("security", raw["area"])
    assertFalse("description" in raw)
    assertFalse("content_body" in raw)
    assertFalse("name" in raw)
  }

  @Test
  fun `add-on emits canonical fields and omits null body and dirs`() {
    val raw = ScaffoldCommandRequest.AddOn(
      name = "bill-grill",
      platform = "kotlin",
      description = "",
      body = null,
      consumerSkillDirs = null,
      scaffoldPayloadVersion = "1.0",
    ).toRawScaffoldPayload()

    assertEquals("add-on", raw["kind"])
    assertEquals("bill-grill", raw["name"])
    assertEquals("kotlin", raw["platform"])
    assertFalse("description" in raw)
    assertFalse("body" in raw)
    assertFalse("consumer_skill_dirs" in raw)
  }

  @Test
  fun `add-on emits body and consumer_skill_dirs when present`() {
    val raw = ScaffoldCommandRequest.AddOn(
      name = "bill-grill",
      platform = "kotlin",
      description = "an addon",
      body = "## body",
      consumerSkillDirs = listOf("code-review/bill-kotlin-code-review"),
      scaffoldPayloadVersion = "1.0",
    ).toRawScaffoldPayload()

    assertEquals("an addon", raw["description"])
    assertEquals("## body", raw["body"])
    assertEquals(listOf("code-review/bill-kotlin-code-review"), raw["consumer_skill_dirs"])
  }
}
