package skillbill.scaffold

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.RetiredScaffoldKindError
import skillbill.error.ScaffoldPayloadVersionMismatchError
import skillbill.error.UnknownPreShellFamilyError
import skillbill.error.UnknownSkillKindError
import skillbill.scaffold.payload.detectKind
import skillbill.scaffold.payload.optionalSpecialistSubagents
import skillbill.scaffold.payload.rejectBaselineLayersForNonPlatformPack
import skillbill.scaffold.payload.rejectLeafSubagentSpecialists
import skillbill.scaffold.payload.resolvePlatformPackDefaults
import skillbill.scaffold.payload.resolvePlatformPackSelection
import skillbill.scaffold.payload.validatePayloadVersion
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.SKILL_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.policy.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_PACK
import skillbill.scaffold.runtime.PRE_SHELL_FAMILIES
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
  fun `detectKind throws RetiredScaffoldKindError for retired partial kind aliases`() {
    listOf("platform-override-piloted", "platform-override", "override", "code-review-area", "area", "specialist")
      .forEach { kind ->
        val error = assertFailsWith<RetiredScaffoldKindError> {
          detectKind(mapOf("kind" to kind))
        }
        val message = error.message.orEmpty()
        assertTrue(kind in message, "Got: $message")
        assertTrue("platform-pack" in message, "Got: $message")
      }
  }

  @Test
  fun `detectKind rejects retired feature task family name with replacement`() {
    val retiredFamily = "feature-" + "implement"

    val error = assertFailsWith<UnknownPreShellFamilyError> {
      detectKind(mapOf("kind" to "platform-override-piloted", "family" to retiredFamily))
    }

    val message = error.message.orEmpty()
    assertTrue(retiredFamily in message, "Got: $message")
    assertTrue("feature-task" in message, "Got: $message")
  }

  @Test
  fun `pre shell family taxonomy contains feature task and feature verify only`() {
    assertEquals(setOf("feature-task", "feature-verify"), PRE_SHELL_FAMILIES)
  }

  @Test
  fun `resolvePlatformPackSelection returns all approved areas when no selector is provided`() {
    val selection = resolvePlatformPackSelection(emptyMap())
    assertEquals(APPROVED_CODE_REVIEW_AREAS.sorted(), selection.selectedAreas)
  }

  @Test
  fun `resolvePlatformPackSelection rejects retired skeleton_mode with migration message`() {
    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      resolvePlatformPackSelection(mapOf("skeleton_mode" to "full"))
    }
    val message = error.message.orEmpty()
    assertTrue("skeleton_mode" in message, "Got: $message")
    assertTrue("no longer supported" in message, "Got: $message")
    assertTrue("remove unwanted focus areas" in message, "Got: $message")
  }

  @Test
  fun `resolvePlatformPackSelection rejects retired specialist_areas with migration message`() {
    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      resolvePlatformPackSelection(mapOf("specialist_areas" to listOf("ui")))
    }
    val message = error.message.orEmpty()
    assertTrue("specialist_areas" in message, "Got: $message")
    assertTrue("no longer supported" in message, "Got: $message")
    assertTrue("remove unwanted focus areas" in message, "Got: $message")
  }

  @Test
  fun `resolvePlatformPackDefaults resolves java preset defaults`() {
    val defaults = resolvePlatformPackDefaults(emptyMap(), "java")
    assertEquals("Java", defaults.displayName)
    assertEquals(true, defaults.presetUsed)
    assertTrue(defaults.strongSignals.isNotEmpty())
  }

  @Test
  fun `resolvePlatformPackDefaults resolves python preset defaults`() {
    val defaults = resolvePlatformPackDefaults(emptyMap(), "python")
    assertEquals("Python", defaults.displayName)
    assertEquals(true, defaults.presetUsed)
    assertTrue(defaults.strongSignals.contains("pyproject.toml"))
    assertTrue(defaults.strongSignals.contains("*.py"))
    assertTrue(defaults.tieBreakers.any { it.contains("generated") })
    assertTrue(defaults.tieBreakers.any { it.contains("vendored") })
  }

  @Test
  fun `resolvePlatformPackDefaults resolves php preset defaults`() {
    val defaults = resolvePlatformPackDefaults(emptyMap(), "php")
    assertEquals("PHP", defaults.displayName)
    assertEquals(true, defaults.presetUsed)
    assertTrue(defaults.strongSignals.contains("composer.json"))
    assertTrue(defaults.strongSignals.contains("*.php"))
    assertTrue(defaults.tieBreakers.any { it.contains("Composer metadata") })
    assertTrue(defaults.tieBreakers.any { it.contains("vendor") })
  }

  @Test
  fun `resolvePlatformPackDefaults resolves go preset defaults`() {
    val defaults = resolvePlatformPackDefaults(emptyMap(), "go")
    assertEquals("Go", defaults.displayName)
    assertEquals(true, defaults.presetUsed)
    assertTrue(defaults.strongSignals.contains("go.mod"))
    assertTrue(defaults.strongSignals.contains("*.go"))
    assertTrue(defaults.tieBreakers.any { it.contains("module/workspace metadata") })
    assertTrue(defaults.tieBreakers.any { it.contains("generated clients") })
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
