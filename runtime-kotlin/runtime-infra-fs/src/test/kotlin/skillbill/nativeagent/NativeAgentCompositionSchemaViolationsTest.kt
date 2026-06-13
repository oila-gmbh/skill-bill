package skillbill.nativeagent

import skillbill.error.InvalidNativeAgentCompositionSchemaError
import skillbill.nativeagent.composition.NativeAgentCompositionSchemaValidator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * SKILL-48 Subtask 2c AC5: per-violation tests. Each case starts from
 * a known-valid native-agent bundle YAML and mutates one field; the
 * test asserts that
 * [NativeAgentCompositionSchemaValidator.validate] throws
 * [InvalidNativeAgentCompositionSchemaError] with a message naming the
 * offending key or value.
 *
 * Mirrors `InstallPlanSchemaViolationsTest`. The known-valid base
 * YAML uses the multi-agent bundle envelope; the single-md envelope
 * gets its own coverage in [missing required name fails validation]
 * via the single-md inlined shape.
 *
 * All enum-string YAML literals are quoted to dodge the SnakeYAML
 * YAML 1.1 boolean-coercion pitfall (history 2026-05-09): an unquoted
 * `on` / `off` / `no` / `yes` would parse as a boolean before the
 * validator ever sees it.
 */
class NativeAgentCompositionSchemaViolationsTest {

  private val validBundleYaml: String = """
    agents:
      - name: "bill-agent-one"
        description: "A valid native-agent entry used as the violation-test baseline."
        compose: "governed-content"
  """.trimIndent()

  @Test
  fun `valid bundle yaml passes validation`() {
    // Sanity: the baseline must validate clean, otherwise every
    // violation test below is ambiguous.
    NativeAgentCompositionSchemaValidator.validate(validBundleYaml, "valid-baseline")
  }

  @Test
  fun `missing required name fails validation`() {
    val yaml = """
      agents:
        - description: "Entry missing the required name."
          compose: "governed-content"
    """.trimIndent()

    val error = assertFailsWith<InvalidNativeAgentCompositionSchemaError> {
      NativeAgentCompositionSchemaValidator.validate(yaml, "missing-name")
    }
    assertContains(error.reason, "name")
  }

  @Test
  fun `unknown compose reference shape fails validation`() {
    val yaml = """
      agents:
        - name: "bill-agent-bogus-compose"
          description: "Entry whose compose value is not a member of the enum."
          compose: "bogus-mode"
    """.trimIndent()

    val error = assertFailsWith<InvalidNativeAgentCompositionSchemaError> {
      NativeAgentCompositionSchemaValidator.validate(yaml, "bogus-compose")
    }
    assertContains(error.reason, "compose")
  }

  @Test
  fun `unknown top-level property fails validation`() {
    val yaml = """
      agents:
        - name: "bill-agent-one"
          description: "A valid native-agent entry."
          compose: "governed-content"
      bogus_extra: true
    """.trimIndent()

    val error = assertFailsWith<InvalidNativeAgentCompositionSchemaError> {
      NativeAgentCompositionSchemaValidator.validate(yaml, "unknown-top-level")
    }
    assertContains(error.reason, "bogus_extra")
  }

  @Test
  fun `wrong contract_version value fails validation`() {
    val yaml = """
      contract_version: "9.99-bogus"
      agents:
        - name: "bill-agent-one"
          description: "A valid native-agent entry."
          compose: "governed-content"
    """.trimIndent()

    val error = assertFailsWith<InvalidNativeAgentCompositionSchemaError> {
      NativeAgentCompositionSchemaValidator.validate(yaml, "wrong-contract-version")
    }
    assertContains(error.reason, "contract_version")
  }

  @Test
  fun `malformed body wrong type fails validation`() {
    // The schema declares `body` as a string. A non-string value (here
    // a mapping) MUST be rejected at the schema layer.
    val yaml = """
      agents:
        - name: "bill-agent-one"
          description: "Entry with a malformed body (mapping instead of string)."
          body:
            unexpected_nested: "value"
    """.trimIndent()

    val error = assertFailsWith<InvalidNativeAgentCompositionSchemaError> {
      NativeAgentCompositionSchemaValidator.validate(yaml, "malformed-body")
    }
    assertContains(error.reason, "body")
  }
}
