package skillbill.scaffold.validation

internal const val MARKDOWN_HEADING_PREFIX_LENGTH = 3
internal val allowedSeverities = setOf("Blocker", "Major", "Minor")
internal const val MINIMUM_CONCRETE_FOCUS_TERMS = 1
internal val vagueFocusTerms = setOf(
  "area", "checks", "code", "concerns", "custom", "focus", "general", "generic", "review", "risks",
  "specialist", "specific", "tailored", "unique",
)
internal val generatedSidecarNames = setOf(
  "review-orchestrator.md",
  "review-delegation.md",
  "review-scope.md",
  "shell-ceremony.md",
  "specialist-contract.md",
  "stack-routing.md",
  "telemetry-contract.md",
)
