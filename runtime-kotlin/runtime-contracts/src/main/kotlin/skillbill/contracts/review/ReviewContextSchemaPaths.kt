package skillbill.contracts.review

const val REVIEW_CONTEXT_CONTRACT_VERSION: String = "0.6"
const val REVIEW_CONTEXT_SCHEMA_RESOURCE: String = "skillbill/contracts/review-context-schema.yaml"

object ReviewContextSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/review-context-schema.yaml"

  const val CLASSPATH_RESOURCE: String = REVIEW_CONTEXT_SCHEMA_RESOURCE

  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/review-context-schema.yaml"
}
