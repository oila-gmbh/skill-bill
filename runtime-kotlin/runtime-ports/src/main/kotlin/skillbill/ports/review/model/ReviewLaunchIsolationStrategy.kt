package skillbill.ports.review.model

enum class ReviewLaunchIsolationStrategy(val forkTurns: String?, val supported: Boolean) {
  CODEX_NATIVE_FORK_TURNS_NONE("none", true),
  FRESH_PROCESS(null, true),
  UNSUPPORTED(null, false),
}
