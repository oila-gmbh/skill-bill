package skillbill.scaffold.policy.model

/**
 * Built-in platform-pack preset describing display name, the strong routing signals to seed the
 * generated `platform.yaml`, and the tie-breaker hints to publish alongside.
 *
 * SKILL-52.1 subtask 2: lives in `skillbill.scaffold.policy.model` to satisfy the
 * runtime-domain rule that public data declarations live under `model` packages.
 */
data class PlatformPackPreset(
  val displayName: String,
  val strongSignals: List<String>,
  val tieBreakers: List<String>,
)

/**
 * Parsed view of the `subagent_specialists` / `no_subagents` payload fields. [suppressed] is true
 * when the payload explicitly opts out of subagent emission.
 */
data class OptionalSubagents(
  val specialists: List<String>,
  val suppressed: Boolean,
)

/** Selected code-review areas for a freshly scaffolded platform pack. */
data class PlatformPackSelection(
  val selectedAreas: List<String>,
)

/** Resolved routing/display defaults for a freshly scaffolded platform pack. */
data class PlatformPackDefaults(
  val displayName: String,
  val strongSignals: List<String>,
  val tieBreakers: List<String>,
  val presetUsed: Boolean,
)
