package skillbill.contracts.review

const val REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION: String = "0.2"
const val REVIEW_LIFECYCLE_EVIDENCE_SCHEMA_RESOURCE: String =
  "skillbill/contracts/review-lifecycle-evidence-schema.yaml"

object ReviewLifecycleEvidenceSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/review-lifecycle-evidence-schema.yaml"

  const val CLASSPATH_RESOURCE: String = REVIEW_LIFECYCLE_EVIDENCE_SCHEMA_RESOURCE

  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/review-lifecycle-evidence-schema.yaml"
}
