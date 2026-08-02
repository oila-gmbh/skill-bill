package skillbill.contracts.review

const val REVIEW_LIFECYCLE_CONTRACT_VERSION: String = "0.1"
const val REVIEW_LIFECYCLE_SCHEMA_RESOURCE: String =
  "skillbill/contracts/review-lifecycle-schema.yaml"

object ReviewLifecycleSchemaPaths {
  const val REPO_RELATIVE_PATH: String = "orchestration/contracts/review-lifecycle-schema.yaml"
  const val CLASSPATH_RESOURCE: String = REVIEW_LIFECYCLE_SCHEMA_RESOURCE
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/review-lifecycle-schema.yaml"
}
