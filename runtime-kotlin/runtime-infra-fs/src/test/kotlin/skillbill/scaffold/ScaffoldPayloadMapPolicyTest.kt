package skillbill.scaffold

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.ScaffoldPayloadVersionMismatchError
import skillbill.error.UnknownSkillKindError
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.SKILL_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.policy.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_PACK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SKILL-52.2 subtask 2 (Task 11): coverage for the raw-map scaffold-payload policy helpers that
 * moved from `runtime-domain.scaffold.policy` to `runtime-infra-fs.scaffold` as `internal`
 * helpers. These are exercised here because the legacy filesystem orchestrator path inside
 * `runtime-infra-fs` still consumes raw maps internally.
 */
class ScaffoldPayloadMapPolicyTest {
  @Test
  fun `validatePayloadVersion accepts the canonical wire version`() {
    validatePayloadVersion(mapOf("scaffold_payload_version" to "1.0"))
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
  fun `detectKind returns horizontal for a horizontal payload`() {
    assertEquals("horizontal", detectKind(mapOf("kind" to "horizontal")))
  }

  @Test
  fun `detectKind throws UnknownSkillKindError for an unknown kind`() {
    assertFailsWith<UnknownSkillKindError> {
      detectKind(mapOf("kind" to "not-a-kind"))
    }
  }

  @Test
  fun `resolvePlatformPackSelection returns all approved areas when full skeleton is chosen`() {
    val selection = resolvePlatformPackSelection(mapOf("skeleton_mode" to "full"))
    assertEquals(APPROVED_CODE_REVIEW_AREAS.sorted(), selection.selectedAreas)
  }

  @Test
  fun `resolvePlatformPackSelection rejects unknown specialist areas`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      resolvePlatformPackSelection(
        mapOf("specialist_areas" to listOf("not-an-approved-area")),
      )
    }
  }

  @Test
  fun `resolvePlatformPackSelection rejects payloads providing both skeleton_mode and specialist_areas`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      resolvePlatformPackSelection(
        mapOf(
          "skeleton_mode" to "full",
          "specialist_areas" to listOf("ui"),
        ),
      )
    }
  }

  @Test
  fun `resolvePlatformPackDefaults resolves java preset defaults`() {
    val defaults = resolvePlatformPackDefaults(emptyMap(), "java")
    assertEquals("Java", defaults.displayName)
    assertEquals(true, defaults.presetUsed)
    assertTrue(defaults.strongSignals.isNotEmpty())
  }

  @Test
  fun `resolvePlatformPackDefaults loud-fails when no preset and no routing signals are supplied`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      resolvePlatformPackDefaults(emptyMap(), "no-such-preset")
    }
  }

  @Test
  fun `optionalSpecialistSubagents preserves supplied specialist names`() {
    val parsed = optionalSpecialistSubagents(
      mapOf("subagent_specialists" to listOf("ui", "api-contracts")),
      SKILL_KIND_PLATFORM_PACK,
    )
    assertEquals(listOf("ui", "api-contracts"), parsed.specialists)
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
  fun `optionalSpecialistSubagents rejects duplicate specialist names`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      optionalSpecialistSubagents(
        mapOf("subagent_specialists" to listOf("ui", "ui")),
        SKILL_KIND_HORIZONTAL,
      )
    }
  }

  @Test
  fun `rejectLeafSubagentSpecialists no-ops when subagent_specialists is absent`() {
    rejectLeafSubagentSpecialists(emptyMap(), SKILL_KIND_ADD_ON)
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

  @Test
  fun `optionalSpecialistSubagents rejects no_subagents=true with non-empty subagent list`() {
    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      optionalSpecialistSubagents(
        mapOf(
          "subagent_specialists" to listOf("ui"),
          "no_subagents" to true,
        ),
        SKILL_KIND_HORIZONTAL,
      )
    }
    val message = error.message.orEmpty()
    assertTrue("no_subagents=true" in message, "Got: $message")
    assertTrue("subagent_specialists" in message, "Got: $message")
  }

  @Test
  fun `optionalSpecialistSubagents rejects subagent name that violates SUBAGENT_NAME_PATTERN`() {
    listOf("UPPER", "1starts-with-digit", "has/slash", "has space").forEach { invalidName ->
      val error = assertFailsWith<InvalidScaffoldPayloadError> {
        optionalSpecialistSubagents(
          mapOf("subagent_specialists" to listOf(invalidName)),
          SKILL_KIND_HORIZONTAL,
        )
      }
      val message = error.message.orEmpty()
      assertTrue("invalid name '$invalidName'" in message, "Got: $message for input '$invalidName'")
    }
  }

  @Test
  fun `optionalSpecialistSubagents loud-fails when no_subagents is not a boolean`() {
    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      optionalSpecialistSubagents(
        mapOf("no_subagents" to "true"),
        SKILL_KIND_HORIZONTAL,
      )
    }
    val message = error.message.orEmpty()
    assertTrue("no_subagents" in message, "Got: $message")
    assertTrue("boolean" in message, "Got: $message")
  }

  @Test
  fun `optionalSpecialistSubagents loud-fails when subagent_specialists is not a list`() {
    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      optionalSpecialistSubagents(
        mapOf("subagent_specialists" to "ui"),
        SKILL_KIND_HORIZONTAL,
      )
    }
    val message = error.message.orEmpty()
    assertTrue("subagent_specialists" in message, "Got: $message")
    assertTrue("list of strings" in message, "Got: $message")
  }
}
