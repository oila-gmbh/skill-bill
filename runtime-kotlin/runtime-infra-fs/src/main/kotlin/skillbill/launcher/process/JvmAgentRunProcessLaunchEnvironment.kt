package skillbill.launcher.process

internal fun configureLaunchEnvironment(builder: ProcessBuilder, request: AgentRunProcessRequest) {
  if (!request.inheritEnvironment) {
    val isolated = isolatedLaunchEnvironment(
      builder.environment(),
      request.environment,
      request.environmentPassthroughKeys,
    )
    builder.environment().clear()
    builder.environment().putAll(isolated)
  } else {
    builder.environment().putAll(request.environment)
  }
}

internal fun isolatedLaunchEnvironment(
  parentEnvironment: Map<String, String>,
  overrides: Map<String, String>,
  additionalPassthroughKeys: Set<String> = emptySet(),
): Map<String, String> = parentEnvironment.filterKeys {
  it in ISOLATED_LAUNCH_PASSTHROUGH_KEYS || it in additionalPassthroughKeys
} + overrides

internal val ISOLATED_LAUNCH_PASSTHROUGH_KEYS: Set<String> = setOf(
  "HOME",
  "PATH",
  "USER",
  "LOGNAME",
  "SHELL",
  "LANG",
  "LC_ALL",
  "TMPDIR",
  "XDG_CONFIG_HOME",
  "XDG_DATA_HOME",
  "XDG_CACHE_HOME",
  "CLAUDE_CONFIG_DIR",
  "CODEX_HOME",
)
