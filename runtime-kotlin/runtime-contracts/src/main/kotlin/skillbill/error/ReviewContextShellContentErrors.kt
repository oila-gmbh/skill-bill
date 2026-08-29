package skillbill.error

class InvalidSkillContentIdentityError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Skill content identity '${sourceLabel.ifBlank { "<unknown>" }}' is invalid: $reason",
  cause,
)

class SkillContentIdentityMismatchError(
  val suppliedIdentity: String,
  val installedIdentity: String,
) : ShellContentContractException(
  "Skill content identity mismatch: supplied source '$suppliedIdentity'; " +
    "installed source '$installedIdentity'.",
)

class InvalidReviewContextSchemaError(
  val sourceLabel: String,
  val reason: String,
  val definitionName: String? = null,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Review context '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation" +
    definitionName?.takeIf { it.isNotBlank() }?.let { " for definition '$it'" }.orEmpty() +
    ": $reason",
  cause,
)

const val REVIEW_HUNK_EVIDENCE_LOCATOR_MISSING: String = "review_hunk_evidence_locator_missing"

const val REVIEW_HUNK_EVIDENCE_LOCATOR_UNREADABLE: String = "review_hunk_evidence_locator_unreadable"

const val REVIEW_HUNK_EVIDENCE_INTEGRITY: String = "review_hunk_evidence_integrity"

class ReviewHunkEvidenceLocatorMissingError(
  val storePath: String,
) : ShellContentContractException(
  "$REVIEW_HUNK_EVIDENCE_LOCATOR_MISSING: store_path '$storePath' is missing; refusing to compose or launch.",
)

class ReviewHunkEvidenceLocatorUnreadableError(
  val storePath: String,
  val reason: String,
) : ShellContentContractException(
  "$REVIEW_HUNK_EVIDENCE_LOCATOR_UNREADABLE: store_path '$storePath' is unreadable ($reason); " +
    "refusing to compose or launch.",
)

class ReviewHunkEvidenceIntegrityError(
  val storePath: String,
  val expectedDigest: String,
  val observedDigest: String,
) : ShellContentContractException(
  "$REVIEW_HUNK_EVIDENCE_INTEGRITY: store_path '$storePath' body digest '$observedDigest' does not match " +
    "locator digest '$expectedDigest'; refusing to compose or launch.",
)

class UnreadableSpecIntentProjectionError(
  val specPath: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Projection 'spec_intent_projection' could not be read from '${specPath.ifBlank { "<unknown>" }}': $reason",
  cause,
)

/**
 * Surfaced when delegated-review aggregation is handed a lane set it cannot merge honestly: a
 * selected lane with no result, two results for one lane, or a result minted against a different
 * commit sequence. Merging any of these would silently under-report coverage, so aggregation fails
 * loudly with the offending lanes named instead.
 */
class ReviewAggregationIntegrityError(
  val reason: String,
  val lanes: List<String> = emptyList(),
) : ShellContentContractException(
  "Delegated review aggregation rejected the lane results: $reason" +
    lanes.takeIf { it.isNotEmpty() }?.let { " (${it.sorted().joinToString(", ")})" }.orEmpty(),
)
