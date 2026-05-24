package skillbill.scaffold.policy

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.ScaffoldPayloadVersionMismatchError
import skillbill.error.UnknownSkillKindError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScaffoldPayloadPolicyTest {
  @Test
  fun `detectKind returns horizontal for a horizontal payload`() {
    val kind = detectKind(mapOf("kind" to "horizontal"))
    assertEquals("horizontal", kind)
  }

  @Test
  fun `detectKind throws UnknownSkillKindError for an unknown kind`() {
    val error = assertFailsWith<UnknownSkillKindError> {
      detectKind(mapOf("kind" to "not-a-kind"))
    }
    val message = error.message
    requireNotNull(message)
    assertEquals(true, message.contains("not-a-kind"))
  }

  @Test
  fun `detectKind throws InvalidScaffoldPayloadError when kind is missing`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      detectKind(mapOf("foo" to "bar"))
    }
  }

  @Test
  fun `validatePayloadVersion accepts the canonical wire version`() {
    validatePayloadVersion(mapOf("scaffold_payload_version" to SCAFFOLD_PAYLOAD_VERSION))
  }

  @Test
  fun `validatePayloadVersion throws when the version disagrees`() {
    assertFailsWith<ScaffoldPayloadVersionMismatchError> {
      validatePayloadVersion(mapOf("scaffold_payload_version" to "9.9"))
    }
  }

  @Test
  fun `validatePayloadVersion throws when the version field is missing`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      validatePayloadVersion(mapOf("kind" to "horizontal"))
    }
  }

  @Test
  fun `parseBaselineLayerPayload accepts a well-formed object`() {
    val raw = mapOf(
      "platform" to "kmp",
      "skill" to "bill-kmp-code-review",
      "scope" to "same-review-scope",
      "required" to true,
      "mode" to "kmp-baseline",
    )

    val layer = parseBaselineLayerPayload(0, raw)

    assertEquals("kmp", layer.platform)
    assertEquals("bill-kmp-code-review", layer.skill)
    assertEquals(true, layer.required)
    assertEquals("same-review-scope", layer.scope.wireValue)
    assertEquals("kmp-baseline", layer.mode.wireValue)
  }

  @Test
  fun `parseBaselineLayerPayload throws when raw is not an object`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      parseBaselineLayerPayload(2, "not-an-object")
    }
  }

  @Test
  fun `parseBaselineLayerPayload throws when scope wire value is unsupported`() {
    val raw = mapOf(
      "platform" to "kmp",
      "skill" to "bill-kmp-code-review",
      "scope" to "bogus-scope",
      "required" to true,
      "mode" to "kmp-baseline",
    )

    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      parseBaselineLayerPayload(0, raw)
    }
    val message = error.message
    requireNotNull(message)
    assertEquals(true, message.contains("bogus-scope"))
    // Protects the stable caller-facing diagnostic contract — the KDoc on
    // parseBaselineLayerPayload promises a `baseline_layers[N].field` prefix.
    assertEquals(true, message.contains("baseline_layers[0].scope"))
  }

  @Test
  fun `parseBaselineLayerPayload throws when required is missing`() {
    val raw = mapOf(
      "platform" to "kmp",
      "skill" to "bill-kmp-code-review",
      "scope" to "same-review-scope",
      "mode" to "kmp-baseline",
    )

    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      parseBaselineLayerPayload(1, raw)
    }
    val message = error.message
    requireNotNull(message)
    assertEquals(true, message.contains("baseline_layers[1].required"))
  }

  @Test
  fun `parseBaselineLayerPayload throws when platform or skill is blank`() {
    // SKILL-52.1 subtask 2 (review-fix F-test-006): blank-string negative paths exercise the
    // `requireStringInPayloadMap` blank-string branch and assert the stable field-prefix prefix.
    val blankPlatform = mapOf(
      "platform" to "",
      "skill" to "bill-kmp-code-review",
      "scope" to "same-review-scope",
      "required" to true,
      "mode" to "kmp-baseline",
    )
    val blankPlatformError = assertFailsWith<InvalidScaffoldPayloadError> {
      parseBaselineLayerPayload(0, blankPlatform)
    }
    val blankPlatformMessage = blankPlatformError.message
    requireNotNull(blankPlatformMessage)
    assertEquals(true, blankPlatformMessage.contains("baseline_layers[0].platform"))

    val blankSkill = mapOf(
      "platform" to "kmp",
      "skill" to "   ",
      "scope" to "same-review-scope",
      "required" to true,
      "mode" to "kmp-baseline",
    )
    val blankSkillError = assertFailsWith<InvalidScaffoldPayloadError> {
      parseBaselineLayerPayload(2, blankSkill)
    }
    val blankSkillMessage = blankSkillError.message
    requireNotNull(blankSkillMessage)
    assertEquals(true, blankSkillMessage.contains("baseline_layers[2].skill"))
  }
}
