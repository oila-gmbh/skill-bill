package skillbill.workflow.taskruntime.model

/**
 * Opaque private-diagnostic identity. Safe for operator and prompt fallback text: identifiers only,
 * never retained bytes, database paths, or value-bearing validator text.
 */
data class CorrectiveRepairDiagnosticLocator(
  val identity: String,
) {
  init {
    require(identity.isNotBlank()) {
      "CorrectiveRepairDiagnosticLocator.identity must be non-blank."
    }
    require(OPAQUE_IDENTITY_PATTERN.matches(identity)) {
      "CorrectiveRepairDiagnosticLocator.identity must be an opaque identifier " +
        "(letters, digits, '.', '_', ':', '-'; length 1..128); paths, whitespace, and " +
        "value-bearing text are rejected."
    }
  }

  /** Validated opaque identity only — never a path, multiline secret, or value-bearing excerpt. */
  val sanitizedIdentity: String
    get() = identity

  /** Payload-free guidance naming the authorized lookup mechanism without embedding raw content. */
  fun authorizedLookupGuidance(): String =
    "Use the private diagnostic locator '$sanitizedIdentity' only through the existing authorized " +
      "private-diagnostic mechanism. Do not invent an excerpt of the rejected response."

  companion object {
    /** Production `rod_<sha256>` and synthetic opaque test ids; rejects paths and free text. */
    private val OPAQUE_IDENTITY_PATTERN: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
  }
}

/**
 * Captured-response classification. Only [Exact] exposes a body; every other state is payload-free
 * while still retaining byte count and digest metadata when known.
 */
sealed class CorrectiveRepairCapturedResponse {
  abstract val availability: CorrectiveRepairResponseAvailability
  abstract val utf8ByteCount: Int
  abstract val digestSha256: String
  abstract val inclusionReason: CorrectiveRepairInclusionReason

  /** Unchanged response within the response budget; [body] is the complete capture. */
  data class Exact(
    val body: String,
    override val utf8ByteCount: Int,
    override val digestSha256: String,
  ) : CorrectiveRepairCapturedResponse() {
    override val availability: CorrectiveRepairResponseAvailability =
      CorrectiveRepairResponseAvailability.EXACT_RESPONSE_INCLUDED
    override val inclusionReason: CorrectiveRepairInclusionReason =
      CorrectiveRepairInclusionReason.EXACT_WITHIN_BUDGET

    init {
      require(utf8ByteCount >= 0) {
        "CorrectiveRepairCapturedResponse.Exact.utf8ByteCount must be non-negative, was $utf8ByteCount."
      }
      require(digestSha256.matches(DIGEST_PATTERN)) {
        "CorrectiveRepairCapturedResponse.Exact.digestSha256 must be a lowercase SHA-256 hex digest."
      }
      val actualBytes = body.toByteArray(Charsets.UTF_8)
      require(actualBytes.size == utf8ByteCount) {
        "CorrectiveRepairCapturedResponse.Exact utf8ByteCount $utf8ByteCount does not match the body " +
          "(${actualBytes.size} UTF-8 bytes)."
      }
      require(sha256Hex(actualBytes) == digestSha256) {
        "CorrectiveRepairCapturedResponse.Exact digest does not match the body bytes."
      }
    }
  }

  data class AlreadyTruncated(
    override val utf8ByteCount: Int,
    override val digestSha256: String,
  ) : CorrectiveRepairCapturedResponse() {
    override val availability: CorrectiveRepairResponseAvailability =
      CorrectiveRepairResponseAvailability.RESPONSE_ALREADY_TRUNCATED
    override val inclusionReason: CorrectiveRepairInclusionReason =
      CorrectiveRepairInclusionReason.CAPTURE_ALREADY_TRUNCATED

    init {
      requireMetadata(utf8ByteCount, digestSha256, "AlreadyTruncated")
    }
  }

  data class ExceedsBudget(
    override val utf8ByteCount: Int,
    override val digestSha256: String,
  ) : CorrectiveRepairCapturedResponse() {
    override val availability: CorrectiveRepairResponseAvailability =
      CorrectiveRepairResponseAvailability.RESPONSE_EXCEEDS_REPAIR_BUDGET
    override val inclusionReason: CorrectiveRepairInclusionReason =
      CorrectiveRepairInclusionReason.CAPTURE_EXCEEDS_RESPONSE_BUDGET

    init {
      requireMetadata(utf8ByteCount, digestSha256, "ExceedsBudget")
    }
  }

  data class Unavailable(
    override val utf8ByteCount: Int,
    override val digestSha256: String,
  ) : CorrectiveRepairCapturedResponse() {
    override val availability: CorrectiveRepairResponseAvailability =
      CorrectiveRepairResponseAvailability.RESPONSE_UNAVAILABLE
    override val inclusionReason: CorrectiveRepairInclusionReason =
      CorrectiveRepairInclusionReason.CAPTURE_UNAVAILABLE

    init {
      requireMetadata(utf8ByteCount, digestSha256, "Unavailable")
    }
  }

  companion object {
    private val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")

    private fun requireMetadata(utf8ByteCount: Int, digestSha256: String, label: String) {
      require(utf8ByteCount >= 0) {
        "CorrectiveRepairCapturedResponse.$label.utf8ByteCount must be non-negative, was $utf8ByteCount."
      }
      require(digestSha256.matches(DIGEST_PATTERN)) {
        "CorrectiveRepairCapturedResponse.$label.digestSha256 must be a lowercase SHA-256 hex digest."
      }
    }

    /**
     * Classifies a capture without silently truncating. Only an unchanged body within
     * [budget.maxResponseUtf8Bytes] becomes [Exact]; truncated, oversized, and missing bodies stay
     * payload-free while preserving digest and byte metadata when supplied.
     */
    fun classify(
      body: String?,
      alreadyTruncated: Boolean,
      budget: FeatureTaskRuntimeCorrectiveRepairBudget = FeatureTaskRuntimeCorrectiveRepairBudget.DEFAULT,
      knownUtf8ByteCount: Int? = null,
      knownDigestSha256: String? = null,
    ): CorrectiveRepairCapturedResponse {
      if (body == null) {
        return Unavailable(
          utf8ByteCount = knownUtf8ByteCount ?: 0,
          digestSha256 = knownDigestSha256 ?: EMPTY_DIGEST,
        )
      }
      val bytes = body.toByteArray(Charsets.UTF_8)
      val digest = knownDigestSha256 ?: sha256Hex(bytes)
      val byteCount = knownUtf8ByteCount ?: bytes.size
      require(byteCount == bytes.size) {
        "knownUtf8ByteCount $byteCount does not match the supplied body (${bytes.size} UTF-8 bytes)."
      }
      require(digest == sha256Hex(bytes)) {
        "knownDigestSha256 does not match the supplied body bytes."
      }
      if (alreadyTruncated) {
        return AlreadyTruncated(utf8ByteCount = byteCount, digestSha256 = digest)
      }
      if (byteCount > budget.maxResponseUtf8Bytes) {
        return ExceedsBudget(utf8ByteCount = byteCount, digestSha256 = digest)
      }
      return Exact(body = body, utf8ByteCount = byteCount, digestSha256 = digest)
    }
  }
}

/**
 * Versioned corrective-repair context carried into a schema-invalid retry. Distinct from
 * retryable-terminal and incomplete-work continuation paths.
 */
