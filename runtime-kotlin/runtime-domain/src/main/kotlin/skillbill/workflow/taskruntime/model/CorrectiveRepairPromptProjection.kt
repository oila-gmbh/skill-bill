package skillbill.workflow.taskruntime.model

data class CorrectiveRepairPromptProjection(
  val availability: CorrectiveRepairResponseAvailability,
  val inclusionReason: CorrectiveRepairInclusionReason,
  val utf8ByteCount: Int,
  val digestSha256: String,
  val diagnosticLocator: CorrectiveRepairDiagnosticLocator?,
  val exactResponseBody: String?,
  val diagnosticDegradationClass: FeatureTaskRuntimeDiagnosticFailureClass? = null,
) {
  init {
    when (availability) {
      CorrectiveRepairResponseAvailability.EXACT_RESPONSE_INCLUDED -> {
        require(exactResponseBody != null) {
          "Exact repair projection must carry the complete response body."
        }
        require(inclusionReason == CorrectiveRepairInclusionReason.EXACT_WITHIN_BUDGET) {
          "Exact repair projection must use inclusion reason exact_within_budget."
        }
      }
      CorrectiveRepairResponseAvailability.RESPONSE_ALREADY_TRUNCATED,
      CorrectiveRepairResponseAvailability.RESPONSE_EXCEEDS_REPAIR_BUDGET,
      CorrectiveRepairResponseAvailability.RESPONSE_UNAVAILABLE,
      -> {
        require(exactResponseBody == null) {
          "Non-exact repair projection must not carry a response body or excerpt."
        }
      }
    }
  }

  val includesExactBody: Boolean
    get() = availability == CorrectiveRepairResponseAvailability.EXACT_RESPONSE_INCLUDED

  /**
   * Renders the authorized repair section only. Payload-free failure guidance and the required
   * output contract stay outside this section at the composer seam.
   */
  fun renderAuthorizedRepairSection(): String = if (includesExactBody) {
    renderExactUntrustedSection(
      body = requireNotNull(exactResponseBody),
      utf8ByteCount = utf8ByteCount,
      digestSha256 = digestSha256,
    )
  } else {
    renderPayloadFreeFallbackSection()
  }

  private fun renderPayloadFreeFallbackSection(): String {
    val locatorLine = diagnosticLocator?.authorizedLookupGuidance()
      ?: (
        "Private diagnostic write degraded (${requireNotNull(diagnosticDegradationClass).wireValue}); " +
          "no resolvable locator."
        )
    return """
    ## Rejected response body not included in this prompt
    availability: ${availability.wireValue}
    inclusion_reason: ${inclusionReason.wireValue}
    utf8_bytes: $utf8ByteCount
    digest: $digestSha256
    $locatorLine
    """.trimIndent()
  }

  companion object {
    fun from(context: FeatureTaskRuntimeCorrectiveRepairContext): CorrectiveRepairPromptProjection {
      // One captured response is one collection item. Enforce before any body framing so an
      // undersized collection budget cannot silently omit or truncate projection entries.
      context.budget.requireCollectionWithinLimit(itemCount = 1)
      val captured = context.captured
      if (captured is CorrectiveRepairCapturedResponse.Exact) {
        val framed = renderExactUntrustedSection(
          body = captured.body,
          utf8ByteCount = captured.utf8ByteCount,
          digestSha256 = captured.digestSha256,
        )
        val framedBytes = framed.toByteArray(Charsets.UTF_8).size
        if (framedBytes > context.budget.maxPromptUtf8Bytes) {
          return requireWithinPromptBudget(
            CorrectiveRepairPromptProjection(
              availability = CorrectiveRepairResponseAvailability.RESPONSE_EXCEEDS_REPAIR_BUDGET,
              inclusionReason = CorrectiveRepairInclusionReason.PROMPT_FRAMING_EXCEEDS_BUDGET,
              utf8ByteCount = captured.utf8ByteCount,
              digestSha256 = captured.digestSha256,
              diagnosticLocator = context.diagnosticLocator,
              exactResponseBody = null,
              diagnosticDegradationClass = context.diagnosticDegradationClass,
            ),
            context.budget,
          )
        }
        return CorrectiveRepairPromptProjection(
          availability = captured.availability,
          inclusionReason = captured.inclusionReason,
          utf8ByteCount = captured.utf8ByteCount,
          digestSha256 = captured.digestSha256,
          diagnosticLocator = context.diagnosticLocator,
          exactResponseBody = captured.body,
          diagnosticDegradationClass = context.diagnosticDegradationClass,
        )
      }
      // Already-truncated / exceeds-budget / unavailable paths also render a payload-free section;
      // measure that section against the prompt budget so a tiny maxPromptUtf8Bytes cannot ship
      // an over-budget fallback.
      return requireWithinPromptBudget(
        CorrectiveRepairPromptProjection(
          availability = captured.availability,
          inclusionReason = captured.inclusionReason,
          utf8ByteCount = captured.utf8ByteCount,
          digestSha256 = captured.digestSha256,
          diagnosticLocator = context.diagnosticLocator,
          exactResponseBody = null,
          diagnosticDegradationClass = context.diagnosticDegradationClass,
        ),
        context.budget,
      )
    }

    private fun requireWithinPromptBudget(
      projection: CorrectiveRepairPromptProjection,
      budget: FeatureTaskRuntimeCorrectiveRepairBudget,
    ): CorrectiveRepairPromptProjection {
      val renderedBytes = projection.renderAuthorizedRepairSection().toByteArray(Charsets.UTF_8).size
      require(renderedBytes <= budget.maxPromptUtf8Bytes) {
        "CorrectiveRepairPromptProjection fallback is $renderedBytes UTF-8 bytes against the " +
          "${budget.maxPromptUtf8Bytes}-byte prompt budget; the runtime rejects rather than " +
          "emitting an over-budget payload-free section."
      }
      return projection
    }
  }
}
