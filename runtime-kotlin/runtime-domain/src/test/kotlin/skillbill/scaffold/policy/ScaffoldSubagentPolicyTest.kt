package skillbill.scaffold.policy

import skillbill.error.InvalidScaffoldPayloadError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScaffoldSubagentPolicyTest {
  @Test
  fun `optionalSpecialistSubagents returns empty result when fields are absent`() {
    val parsed = optionalSpecialistSubagents(emptyMap(), SKILL_KIND_HORIZONTAL)

    assertEquals(emptyList(), parsed.specialists)
    assertEquals(false, parsed.suppressed)
  }

  @Test
  fun `optionalSpecialistSubagents preserves supplied specialist names`() {
    val parsed = optionalSpecialistSubagents(
      mapOf("subagent_specialists" to listOf("ui", "api-contracts")),
      SKILL_KIND_PLATFORM_PACK,
    )

    assertEquals(listOf("ui", "api-contracts"), parsed.specialists)
    assertEquals(false, parsed.suppressed)
  }

  @Test
  fun `optionalSpecialistSubagents reports suppressed when no_subagents is true`() {
    val parsed = optionalSpecialistSubagents(
      mapOf("no_subagents" to true),
      SKILL_KIND_HORIZONTAL,
    )

    assertEquals(emptyList(), parsed.specialists)
    assertEquals(true, parsed.suppressed)
  }

  @Test
  fun `optionalSpecialistSubagents throws when subagent_specialists is not a list`() {
    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      optionalSpecialistSubagents(
        mapOf("subagent_specialists" to "ui"),
        SKILL_KIND_HORIZONTAL,
      )
    }
    val message = error.message
    requireNotNull(message)
    assertEquals(true, message.contains("must be a list of strings"))
  }

  @Test
  fun `optionalSpecialistSubagents throws when no_subagents is not a boolean`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      optionalSpecialistSubagents(
        mapOf("no_subagents" to "true"),
        SKILL_KIND_HORIZONTAL,
      )
    }
  }

  @Test
  fun `optionalSpecialistSubagents throws when a specialist name violates the pattern`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      optionalSpecialistSubagents(
        mapOf("subagent_specialists" to listOf("BAD_NAME")),
        SKILL_KIND_HORIZONTAL,
      )
    }
  }

  @Test
  fun `optionalSpecialistSubagents rejects duplicate specialist names`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      optionalSpecialistSubagents(
        mapOf("subagent_specialists" to listOf("ui", "ui")),
        SKILL_KIND_HORIZONTAL,
      )
    }
  }

  @Test
  fun `optionalSpecialistSubagents rejects specialists for leaf kinds`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      optionalSpecialistSubagents(
        mapOf("subagent_specialists" to listOf("ui")),
        SKILL_KIND_CODE_REVIEW_AREA,
      )
    }
  }

  @Test
  fun `optionalSpecialistSubagents rejects suppression mixed with specialists`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      optionalSpecialistSubagents(
        mapOf(
          "subagent_specialists" to listOf("ui"),
          "no_subagents" to true,
        ),
        SKILL_KIND_PLATFORM_PACK,
      )
    }
  }

  @Test
  fun `rejectLeafSubagentSpecialists no-ops when subagent_specialists is absent`() {
    rejectLeafSubagentSpecialists(emptyMap(), SKILL_KIND_ADD_ON)
  }

  @Test
  fun `rejectLeafSubagentSpecialists throws when leaf kind tries to declare specialists`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      rejectLeafSubagentSpecialists(
        mapOf("subagent_specialists" to listOf("ui")),
        SKILL_KIND_ADD_ON,
      )
    }
  }

  @Test
  fun `rejectBaselineLayersForNonPlatformPack passes when baseline_layers is absent`() {
    rejectBaselineLayersForNonPlatformPack(emptyMap(), SKILL_KIND_HORIZONTAL)
  }

  @Test
  fun `rejectBaselineLayersForNonPlatformPack rejects baseline_layers on non-platform-pack kinds`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      rejectBaselineLayersForNonPlatformPack(
        mapOf("baseline_layers" to listOf(emptyMap<String, Any?>())),
        SKILL_KIND_HORIZONTAL,
      )
    }
  }
}
