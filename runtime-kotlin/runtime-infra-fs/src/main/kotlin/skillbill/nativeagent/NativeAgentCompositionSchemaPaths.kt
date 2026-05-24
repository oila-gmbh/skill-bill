package skillbill.nativeagent

/**
 * SKILL-48 Subtask 2c: pinned runtime-side mirror of the canonical
 * native-agent composition schema's `contract_version`. The parity
 * test `NativeAgentCompositionSchemaContractVersionTest` fails the
 * build if this constant and the schema's
 * `$defs.contractVersion.const` diverge. To bump the contract, edit
 * BOTH sites in the same change.
 *
 * On-disk fixtures may omit a top-level `contract_version` key — the
 * schema keeps the field OPTIONAL on every envelope. When present, the
 * value MUST equal this constant so the runtime cannot silently
 * validate against a stale contract.
 */
const val NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION: String = "0.1"

/**
 * SKILL-48 Subtask 2c: single source of truth for where the canonical
 * native-agent composition schema lives. Every runtime caller —
 * including the desktop browser, tests, and the Gradle copy task
 * documented below — should reference these constants so the canonical
 * path appears exactly once in the codebase.
 *
 * The Gradle Kotlin DSL cannot import runtime constants directly. The
 * copy task in `runtime-core/build.gradle.kts` MUST mirror these
 * values; if the paths ever drift,
 * `NativeAgentCompositionSchemaValidator.loadSchema` will fail loudly
 * at runtime (the classpath resource will not be found at
 * [CLASSPATH_RESOURCE]) and any test that resolves
 * [REPO_RELATIVE_PATH] will report the missing file.
 */
object NativeAgentCompositionSchemaPaths {
  /** Repo-relative path to the canonical schema YAML on disk. */
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/native-agent-composition-schema.yaml"

  /** Classpath resource path where runtime-core bundles the schema for runtime loads. */
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/native-agent-composition-schema.yaml"

  /**
   * SKILL-48 Subtask 2c (mirrors SKILL-47 C7): expected value of the
   * canonical schema's `$id`. The validator asserts that the schema
   * document loaded from the classpath / disk matches this value, so a
   * stale or shadowed copy fails loudly with
   * [skillbill.error.InvalidNativeAgentCompositionSchemaError] instead
   * of silently validating against the wrong contract.
   */
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/native-agent-composition-schema.yaml"
}
