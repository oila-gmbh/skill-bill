package skillbill.ports.review.model

import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.review.NativeReviewOperationProtocol
import skillbill.ports.review.ReviewEvidenceBroker
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Provider-native start request for a governed specialist. Implementations must disable native
 * repository and tool surfaces unless they synchronously authorize each operation through
 * [operations]. Adapting this request to an unrestricted process launch is a contract violation.
 */
data class NativeReviewWorkerRequest(
  val agentId: String,
  val issueKey: String,
  val repoRoot: Path,
  val prompt: String,
  val isolation: ReviewLaunchIsolationStrategy,
  val timeout: Duration,
  val modelOverride: String? = null,
  val broker: ReviewEvidenceBroker,
  val operations: NativeReviewOperationProtocol,
) {
  init {
    require(isolation.supported) { "A native review worker requires supported fresh-context isolation." }
    if (isolation == ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE) {
      require(isolation.forkTurns == "none") { "A Codex native review request requires fork_turns none." }
    }
  }
}
