package skillbill.contracts.workflow

/**
 * SKILL-48 Subtask 2a: pinned runtime-side mirror of the canonical
 * workflow-state schema's `contract_version`. The parity test
 * `WorkflowStateSchemaContractVersionTest` fails the build if this
 * constant and the schema's `properties.contract_version.const`
 * diverge. To bump the contract, edit BOTH sites in the same change.
 *
 * The value mirrors the `contractVersion` field that the two shipped
 * `WorkflowDefinition`s (`FeatureImplement`/`FeatureVerify`) emit
 * today, so every snapshot the runtime produces validates clean
 * against the canonical schema.
 */
const val WORKFLOW_STATE_CONTRACT_VERSION: String = "0.1"

/**
 * SKILL-48 Subtask 2a: single source of truth for where the canonical
 * workflow-state schema lives. Every runtime caller — including the
 * desktop browser, tests, and the Gradle copy task documented below —
 * should reference these constants so the canonical path appears
 * exactly once in the codebase.
 *
 * The Gradle Kotlin DSL cannot import runtime constants directly. The
 * copy task in `runtime-infra-fs/build.gradle.kts` (SKILL-52.3: the
 * schema validators now live in `runtime-infra-fs`) MUST mirror these
 * values; if the paths ever drift, the canonical workflow-state schema
 * load will fail loudly at runtime (the classpath resource will not be
 * found at [CLASSPATH_RESOURCE]) and any test that resolves
 * [REPO_RELATIVE_PATH] will report the missing file.
 */
object WorkflowStateSchemaPaths {
  /** Repo-relative path to the canonical schema YAML on disk. */
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/workflow-state-schema.yaml"

  /** Classpath resource path where runtime-contracts bundles the schema for runtime loads. */
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/workflow-state-schema.yaml"

  /**
   * SKILL-48 Subtask 2a (mirrors SKILL-47 C7): expected value of the canonical
   * schema's `$id`. The validator asserts that the schema document loaded from
   * the classpath / disk matches this value, so a stale or shadowed copy fails
   * loudly with [skillbill.error.InvalidWorkflowStateSchemaError] instead of
   * silently validating against the wrong contract.
   */
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/workflow-state-schema.yaml"
}
