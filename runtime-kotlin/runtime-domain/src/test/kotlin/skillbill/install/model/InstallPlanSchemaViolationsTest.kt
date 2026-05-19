package skillbill.install.model

import skillbill.error.InvalidInstallPlanSchemaError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * SKILL-48 Subtask 2b AC5: per-violation tests. Each case starts from a
 * known-valid wire map and mutates one field; the test asserts that
 * `InstallPlanSchemaValidator.validate` throws
 * [InvalidInstallPlanSchemaError] with the expected dotted `fieldPath`.
 *
 * Mirrors `WorkflowStateSchemaViolationsTest`. The known-valid base
 * map is constructed by hand (not via `buildInstallPlanWireMap`) so
 * the test exercises the validator in isolation from the builder.
 */
class InstallPlanSchemaViolationsTest {
  private fun validBaseWireMap(): MutableMap<String, Any?> = linkedMapOf(
    "status" to "planned",
    "contract_version" to INSTALL_PLAN_CONTRACT_VERSION,
    "agents" to mutableListOf<Map<String, Any?>>(
      linkedMapOf(
        "agent" to "codex",
        "path" to "/home/user/.codex/skills",
        "source" to "manual",
      ),
    ),
    "platform_packs" to listOf(
      linkedMapOf(
        "slug" to "kotlin",
        "pack_root" to "/repo/platform-packs/kotlin",
        "selected" to false,
      ),
    ),
    "selected_platforms" to emptyList<String>(),
    "skills" to mutableListOf<MutableMap<String, Any?>>(
      linkedMapOf(
        "name" to "bill-code-review",
        "kind" to "base",
        "platform" to null,
        "source_dir" to "/repo/skills/bill-code-review",
      ),
    ),
    "staging_root" to "/home/user/.skill-bill/installed-skills",
    "staging" to mutableListOf<MutableMap<String, Any?>>(
      linkedMapOf(
        "skill_name" to "bill-code-review",
        "source_dir" to "/repo/skills/bill-code-review",
        "staging_dir" to "/home/user/.skill-bill/installed-skills/bill-code-review-abcdef",
        "content_hash" to "abcdef",
      ),
    ),
    "telemetry_level" to "anonymous",
    "mcp_registration" to linkedMapOf(
      "register" to true,
      "runtime_mcp_bin" to "/home/user/.skill-bill/runtime/runtime-mcp/bin/runtime-mcp",
      "agents" to listOf("codex"),
    ),
    "runtime_distribution" to linkedMapOf(
      "runtime_install_root" to "/home/user/.skill-bill/runtime",
      "runtime_cli_build_dir" to null,
      "runtime_mcp_build_dir" to null,
      "runtime_cli_install_dir" to null,
      "runtime_mcp_install_dir" to null,
      "runtime_launcher_bin_dir" to null,
    ),
    "windows_symlink_preflight" to linkedMapOf(
      "state" to "not_windows",
      "decision" to "not_required",
      "message" to "",
    ),
    "replace_existing_skill_bill_links" to false,
  )

  @Test
  fun `valid base wire map passes validation`() {
    // Sanity-check that the fixture is itself clean — otherwise every
    // violation test below would be ambiguous.
    InstallPlanSchemaValidator.validate(validBaseWireMap())
  }

  @Test
  fun `unknown skill kind value fails validation with skills field path`() {
    val wireMap = validBaseWireMap()

    @Suppress("UNCHECKED_CAST")
    val skills = wireMap["skills"] as MutableList<MutableMap<String, Any?>>
    skills[0]["kind"] = "bogus_kind"

    val error = assertFailsWith<InvalidInstallPlanSchemaError> { InstallPlanSchemaValidator.validate(wireMap) }
    assertContains(error.fieldPath, "skills")
    assertContains(error.fieldPath, "kind")
  }

  @Test
  fun `missing required staging_root fails validation with root field path`() {
    val wireMap = validBaseWireMap()
    wireMap.remove("staging_root")

    val error = assertFailsWith<InvalidInstallPlanSchemaError> { InstallPlanSchemaValidator.validate(wireMap) }
    // Required-property violations surface at the parent path; for a top-level
    // required key the parent is the root, which the validator maps to "".
    // Pin both fieldPath and reason so a future refactor that drops
    // `staging_root` from the message also trips this assertion.
    assertEquals("", error.fieldPath)
    assertContains(error.reason, "staging_root")
  }

  @Test
  fun `wrong contract_version value fails validation with contract_version field path`() {
    val wireMap = validBaseWireMap()
    wireMap["contract_version"] = "0.2"

    val error = assertFailsWith<InvalidInstallPlanSchemaError> { InstallPlanSchemaValidator.validate(wireMap) }
    assertContains(error.fieldPath, "contract_version")
  }

  @Test
  fun `unknown additional property fails validation`() {
    val wireMap = validBaseWireMap()
    wireMap["bogus_extra"] = true

    val error = assertFailsWith<InvalidInstallPlanSchemaError> { InstallPlanSchemaValidator.validate(wireMap) }
    // additionalProperties violations may report the parent path; the
    // reason MUST name the offending key so callers can pinpoint it.
    assertContains(error.reason, "bogus_extra")
  }

  @Test
  fun `invalid path shape empty string for staging source_dir fails validation`() {
    val wireMap = validBaseWireMap()

    @Suppress("UNCHECKED_CAST")
    val staging = wireMap["staging"] as MutableList<MutableMap<String, Any?>>
    staging[0]["source_dir"] = ""

    val error = assertFailsWith<InvalidInstallPlanSchemaError> { InstallPlanSchemaValidator.validate(wireMap) }
    assertContains(error.fieldPath, "staging")
    assertContains(error.fieldPath, "source_dir")
  }
}
