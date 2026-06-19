package skillbill.mcp.telemetry

/**
 * SKILL-48 Subtask 2d: pinned runtime-side mirror of the canonical
 * telemetry-event schema's `contract_version`. The parity test
 * `TelemetryEventSchemaContractVersionTest` fails the build if this
 * constant and the schema's `properties.contract_version.const`
 * diverge. To bump the contract, edit BOTH sites in the same change.
 *
 * The value mirrors the `contract_version` field that the runtime
 * stamps onto every emitted telemetry envelope, so every payload the
 * runtime produces validates clean against the canonical schema.
 */
const val TELEMETRY_EVENT_CONTRACT_VERSION: String = "1.1.0"

/**
 * SKILL-48 Subtask 2d: single source of truth for where the canonical
 * telemetry-event schema lives. Every runtime caller — including the
 * desktop browser, tests, and the Gradle copy task documented below —
 * should reference these constants so the canonical path appears
 * exactly once in the codebase.
 *
 * The Gradle Kotlin DSL cannot import runtime constants directly. The
 * copy task in `runtime-mcp/build.gradle.kts` MUST mirror these
 * values; if the paths ever drift,
 * `TelemetryEventSchemaValidator.loadSchema` will fail loudly at
 * runtime (the classpath resource will not be found at
 * [CLASSPATH_RESOURCE]) and any test that resolves
 * [REPO_RELATIVE_PATH] will report the missing file.
 */
object TelemetryEventSchemaPaths {
  /** Repo-relative path to the canonical schema YAML on disk. */
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/telemetry-event-schema.yaml"

  /** Classpath resource path where runtime-mcp bundles the schema for runtime loads. */
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/telemetry-event-schema.yaml"

  /**
   * SKILL-48 Subtask 2d (mirrors SKILL-47 C7): expected value of the
   * canonical schema's `$id`. The validator asserts that the schema
   * document loaded from the classpath / disk matches this value, so a
   * stale or shadowed copy fails loudly with
   * [skillbill.error.InvalidTelemetryEventSchemaError] instead of
   * silently validating against the wrong contract.
   */
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/telemetry-event-schema.yaml"
}
