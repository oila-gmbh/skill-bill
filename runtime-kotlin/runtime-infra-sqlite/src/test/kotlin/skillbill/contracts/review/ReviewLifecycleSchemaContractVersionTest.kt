package skillbill.contracts.review

import skillbill.error.InvalidReviewLifecycleSchemaError
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewLifecycleSchemaContractVersionTest {
  @Test
  fun `provider matrix covers every required capability dimension`() {
    ReviewLifecycleSchemaValidator.validate(
      mapOf(
        "contract_version" to REVIEW_LIFECYCLE_CONTRACT_VERSION,
        "kind" to "provider_capability_matrix",
        "providers" to listOf(
          mapOf(
            "provider_id" to "codex",
            "status" to "experimental",
            "capabilities" to mapOf(
              "fresh_context_isolation" to true,
              "worker_tracking" to true,
              "output_capture" to true,
              "declared_progress" to true,
              "cancellation" to true,
              "timeout" to true,
              "token_reporting" to true,
              "terminal_result" to true,
            ),
            "rationale" to "Bounded adapter and process evidence.",
          ),
        ),
      ),
      "provider-matrix",
    )
  }

  @Test
  fun `unsupported capability combinations fail loudly`() {
    val unsupportedCombination = mapOf(
      "contract_version" to REVIEW_LIFECYCLE_CONTRACT_VERSION,
      "kind" to "provider_capability_matrix",
      "providers" to listOf(
        mapOf(
          "provider_id" to "broken",
          "status" to "supported",
          "capabilities" to mapOf(
            "fresh_context_isolation" to false,
            "worker_tracking" to true,
            "output_capture" to true,
            "declared_progress" to true,
            "cancellation" to true,
            "timeout" to true,
            "token_reporting" to true,
            "terminal_result" to true,
          ),
          "rationale" to "Missing isolation.",
        ),
      ),
    )
    assertFailsWith<InvalidReviewLifecycleSchemaError> {
      ReviewLifecycleSchemaValidator.validate(unsupportedCombination, "unsupported-combination")
    }
    val unsupportedButComplete = mapOf(
      "contract_version" to REVIEW_LIFECYCLE_CONTRACT_VERSION,
      "kind" to "provider_capability_matrix",
      "providers" to listOf(
        mapOf(
          "provider_id" to "broken",
          "status" to "unsupported",
          "capabilities" to mapOf(
            "fresh_context_isolation" to true,
            "worker_tracking" to true,
            "output_capture" to true,
            "declared_progress" to true,
            "cancellation" to true,
            "timeout" to true,
            "token_reporting" to true,
            "terminal_result" to true,
          ),
          "rationale" to "All dimensions are present, so unsupported is not credible.",
        ),
      ),
    )
    assertFailsWith<InvalidReviewLifecycleSchemaError> {
      ReviewLifecycleSchemaValidator.validate(unsupportedButComplete, "unsupported-complete")
    }
  }

  @Test
  fun `capability matrix contract drift fails loudly`() {
    assertFailsWith<InvalidReviewLifecycleSchemaError> {
      ReviewLifecycleSchemaValidator.validate(
        mapOf(
          "contract_version" to "0.0",
          "kind" to "provider_capability_matrix",
          "providers" to emptyList<Any>(),
        ),
        "stale-version",
      )
    }
  }

  @Test
  fun `lifecycle projection rejects raw transcript fields`() {
    val payload = mapOf(
      "contract_version" to REVIEW_LIFECYCLE_CONTRACT_VERSION,
      "kind" to "delegated_review_lifecycle",
      "review_id" to "review",
      "packet_digest" to "a".repeat(64),
      "selected_area_count" to 0,
      "predicted_wave_count" to 0,
      "actual_wave_count" to 0,
      "coordinator_slots" to 1,
      "workers" to emptyList<Any>(),
      "waves" to emptyList<Any>(),
      "deadlines" to emptyList<Any>(),
      "metrics" to mapOf(
        "elapsed_ms" to 0,
        "total_tokens" to 0,
        "process_count" to 0,
        "mcp_startup_count" to 0,
        "selected_area_count" to 0,
        "completed_area_count" to 0,
        "lost_worker_count" to 0,
      ),
      "transcript" to "not retained",
    )
    val failure = assertFailsWith<InvalidReviewLifecycleSchemaError> {
      ReviewLifecycleSchemaValidator.validate(payload, "raw-content")
    }
    assertTrue(failure.reason.isNotBlank())
  }
}
