package skillbill.ports.review

import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Provider-native start boundary for a governed specialist. Implementations must expose [broker]
 * as the worker's only repository/tool surface; adapting this request to an unrestricted process
 * launch is a contract violation.
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
) {
  init {
    require(isolation.supported) { "A native review worker requires supported fresh-context isolation." }
    if (isolation == ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE) {
      require(isolation.forkTurns == "none") { "A Codex native review request requires fork_turns none." }
    }
  }
}

fun interface NativeReviewWorkerLauncher {
  fun launch(request: NativeReviewWorkerRequest): AgentRunLaunchOutcome
}
