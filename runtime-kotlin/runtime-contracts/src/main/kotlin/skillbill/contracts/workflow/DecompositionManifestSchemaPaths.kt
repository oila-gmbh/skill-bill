package skillbill.contracts.workflow

/**
 * Pinned runtime-side mirror of the canonical decomposition manifest
 * schema's `contract_version`. The parity test fails the build if this
 * constant and the schema's `properties.contract_version.const`
 * diverge. To bump the contract, edit BOTH sites in the same change.
 */
const val DECOMPOSITION_MANIFEST_CONTRACT_VERSION: String = "0.3"

/**
 * Single source of truth for where the canonical decomposition
 * manifest schema lives. The Gradle copy task in
 * `runtime-contracts/build.gradle.kts` must mirror these values because
 * Gradle's Kotlin DSL cannot import runtime constants directly.
 */
object DecompositionManifestSchemaPaths {
  /** Repo-relative path to the canonical schema YAML on disk. */
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/decomposition-manifest-schema.yaml"

  /** Classpath resource path where runtime-contracts bundles the schema for runtime loads. */
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/decomposition-manifest-schema.yaml"

  /**
   * Expected value of the canonical schema's `$id`. The validator
   * asserts this value on load so stale or shadowed resources fail
   * loudly at the parse seam.
   */
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/decomposition-manifest-schema.yaml"
}
