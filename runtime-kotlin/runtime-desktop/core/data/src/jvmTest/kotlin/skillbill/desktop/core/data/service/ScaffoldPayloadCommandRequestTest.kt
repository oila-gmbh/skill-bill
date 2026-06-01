package skillbill.desktop.core.data.service

import skillbill.desktop.core.domain.model.ScaffoldBaselineLayerPayload
import skillbill.desktop.core.domain.model.ScaffoldPayload
import skillbill.desktop.core.domain.model.ScaffoldPlatformPackSkeleton
import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.RetiredScaffoldKindError
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKILL-52.2 subtask 2 (TEST-T02): field-by-field tests for
 * [skillbill.desktop.core.data.service.toCommandRequest] — the typed desktop payload to runtime
 * `ScaffoldCommandRequest` sealed-to-sealed bridge. Specifically pins:
 *  - specialistAreas non-empty collapses skeletonMode to null on the typed request,
 *  - STARTER/FULL enum maps to the runtime-wire strings,
 *  - routing-signals null/empty handling,
 *  - unsupported baseline-layer wire-values throw [InvalidScaffoldPayloadError] (CORR-F003)
 *    so the gateway's SkillBillRuntimeException branch reports rollbackComplete = true.
 */
class ScaffoldPayloadCommandRequestTest {
  @Test
  fun `HorizontalSkill payload maps to typed request with verbatim fields`() {
    val request = ScaffoldPayload.HorizontalSkill(
      name = "bill-foo",
      description = "do the foo",
      contentBody = "## body",
      subagentSpecialists = listOf("ui", "security"),
      suppressSubagents = false,
      repoRoot = "/repo",
    ).toCommandRequest() as ScaffoldCommandRequest.HorizontalSkill

    assertEquals("bill-foo", request.name)
    assertEquals("do the foo", request.description)
    assertEquals("## body", request.contentBody)
    assertEquals(listOf("ui", "security"), request.subagentSpecialists)
    assertEquals(false, request.suppressSubagents)
    assertEquals("1.0", request.scaffoldPayloadVersion)
    assertEquals("/repo", request.repoRoot)
  }

  @Test
  fun `PlatformPack with non-empty specialistAreas collapses skeletonMode to null`() {
    val request = ScaffoldPayload.PlatformPack(
      platform = "kotlin",
      displayName = "Kotlin",
      skeletonMode = ScaffoldPlatformPackSkeleton.FULL,
      specialistAreas = listOf("ui", "security"),
    ).toCommandRequest() as ScaffoldCommandRequest.PlatformPack

    assertNull(request.skeletonMode, "specialistAreas non-empty must collapse skeletonMode to null")
    assertEquals(listOf("ui", "security"), request.specialistAreas)
  }

  @Test
  fun `PlatformPack STARTER skeletonMode maps to 'starter' wire string`() {
    val request = ScaffoldPayload.PlatformPack(
      platform = "kotlin",
      skeletonMode = ScaffoldPlatformPackSkeleton.STARTER,
      specialistAreas = emptyList(),
    ).toCommandRequest() as ScaffoldCommandRequest.PlatformPack

    assertEquals("starter", request.skeletonMode)
    assertNull(request.specialistAreas, "empty specialistAreas list must become null on the typed request")
  }

  @Test
  fun `PlatformPack FULL skeletonMode maps to 'full' wire string`() {
    val request = ScaffoldPayload.PlatformPack(
      platform = "kotlin",
      skeletonMode = ScaffoldPlatformPackSkeleton.FULL,
      specialistAreas = emptyList(),
    ).toCommandRequest() as ScaffoldCommandRequest.PlatformPack

    assertEquals("full", request.skeletonMode)
  }

  @Test
  fun `PlatformPack empty routing signals on both sides yields routingSignals = null`() {
    val request = ScaffoldPayload.PlatformPack(
      platform = "kotlin",
      skeletonMode = ScaffoldPlatformPackSkeleton.FULL,
      strongRoutingSignals = emptyList(),
      tieBreakers = emptyList(),
    ).toCommandRequest() as ScaffoldCommandRequest.PlatformPack

    assertNull(
      request.routingSignals,
      "both-sides-empty routing signals must yield null (preset fallback path)",
    )
  }

  @Test
  fun `PlatformPack one-side-empty routing signals preserves the other side`() {
    val onlyStrong = ScaffoldPayload.PlatformPack(
      platform = "kotlin",
      skeletonMode = ScaffoldPlatformPackSkeleton.FULL,
      strongRoutingSignals = listOf(".kt"),
      tieBreakers = emptyList(),
    ).toCommandRequest() as ScaffoldCommandRequest.PlatformPack

    val onlyTieBreakers = ScaffoldPayload.PlatformPack(
      platform = "kotlin",
      skeletonMode = ScaffoldPlatformPackSkeleton.FULL,
      strongRoutingSignals = emptyList(),
      tieBreakers = listOf("Prefer Kotlin"),
    ).toCommandRequest() as ScaffoldCommandRequest.PlatformPack

    assertEquals(listOf(".kt"), onlyStrong.routingSignals?.strong)
    assertNull(onlyStrong.routingSignals?.tieBreakers)
    assertNull(onlyTieBreakers.routingSignals?.strong)
    assertEquals(listOf("Prefer Kotlin"), onlyTieBreakers.routingSignals?.tieBreakers)
  }

  @Test
  fun `PlatformPack maps baseline layers to runtime sealed equivalents`() {
    val request = ScaffoldPayload.PlatformPack(
      platform = "androidx",
      skeletonMode = ScaffoldPlatformPackSkeleton.STARTER,
      strongRoutingSignals = listOf("androidx"),
      baselineLayers = listOf(
        ScaffoldBaselineLayerPayload(
          platform = "kotlin",
          skill = "bill-kotlin-code-review",
          scope = "same-review-scope",
          required = true,
          mode = "kmp-baseline",
        ),
      ),
    ).toCommandRequest() as ScaffoldCommandRequest.PlatformPack

    val layer = request.baselineLayers.single()
    assertEquals("kotlin", layer.platform)
    assertEquals("bill-kotlin-code-review", layer.skill)
    assertEquals(CodeReviewCompositionScope.SameReviewScope, layer.scope)
    assertEquals(true, layer.required)
    assertEquals(CodeReviewCompositionMode.KmpBaseline, layer.mode)
  }

  @Test
  fun `unsupported baseline-layer scope throws InvalidScaffoldPayloadError with field path`() {
    val payload = ScaffoldPayload.PlatformPack(
      platform = "androidx",
      skeletonMode = ScaffoldPlatformPackSkeleton.STARTER,
      strongRoutingSignals = listOf("androidx"),
      baselineLayers = listOf(
        ScaffoldBaselineLayerPayload(
          platform = "kotlin",
          skill = "bill-kotlin-code-review",
          scope = "bogus-scope",
          required = true,
          mode = "kmp-baseline",
        ),
      ),
    )

    val error = assertFailsWith<InvalidScaffoldPayloadError> { payload.toCommandRequest() }
    val message = error.message.orEmpty()
    assertTrue("baseline_layers[0].scope" in message, "Got: $message")
    assertTrue("bogus-scope" in message, "Got: $message")
  }

  @Test
  fun `unsupported baseline-layer mode throws InvalidScaffoldPayloadError with field path`() {
    val payload = ScaffoldPayload.PlatformPack(
      platform = "androidx",
      skeletonMode = ScaffoldPlatformPackSkeleton.STARTER,
      strongRoutingSignals = listOf("androidx"),
      baselineLayers = listOf(
        ScaffoldBaselineLayerPayload(
          platform = "kotlin",
          skill = "bill-kotlin-code-review",
          scope = "same-review-scope",
          required = true,
          mode = "bogus-mode",
        ),
      ),
    )

    val error = assertFailsWith<InvalidScaffoldPayloadError> { payload.toCommandRequest() }
    val message = error.message.orEmpty()
    assertTrue("baseline_layers[0].mode" in message, "Got: $message")
    assertTrue("bogus-mode" in message, "Got: $message")
  }

  @Test
  fun `PlatformOverride payload is rejected before runtime request submission`() {
    val error = assertFailsWith<RetiredScaffoldKindError> {
      ScaffoldPayload.PlatformOverride(
        platform = "kotlin",
        family = "quality-check",
        description = "qc override",
        contentBody = "## body",
        subagentSpecialists = emptyList(),
        suppressSubagents = false,
      ).toCommandRequest()
    }

    assertTrue("retired for new partial scaffold creation" in error.message.orEmpty())
  }

  @Test
  fun `CodeReviewArea payload is rejected before runtime request submission`() {
    val error = assertFailsWith<RetiredScaffoldKindError> {
      ScaffoldPayload.CodeReviewArea(
        platform = "kotlin",
        area = "security",
        description = "",
        contentBody = null,
      ).toCommandRequest()
    }

    assertTrue("platform-pack" in error.message.orEmpty())
  }

  @Test
  fun `AddOn payload maps to typed request`() {
    val request = ScaffoldPayload.AddOn(
      name = "bill-grill",
      platform = "kotlin",
      description = "grill it",
      body = "## grill",
    ).toCommandRequest() as ScaffoldCommandRequest.AddOn

    assertEquals("bill-grill", request.name)
    assertEquals("kotlin", request.platform)
    assertEquals("grill it", request.description)
    assertEquals("## grill", request.body)
  }
}
