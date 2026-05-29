package dev.skillbill.runtime.buildlogic

// SKILL-55 subtask 1 (F-005): canonical per-OS/arch target tokens + host detection
// for the self-contained runtime images, hoisted out of the off-convention
// `apply(from = gradle/runtime-targets.gradle.kts)` script into a TYPED build-logic
// source so runtime-cli / runtime-mcp (and subtasks 3/4) consume a typed API instead
// of `extra["hostRuntimeToken"] as String`.
//
// Locally only the current host's image is buildable: jlink/jpackage cannot
// cross-compile a runtime for a foreign OS/arch. The macos-* and windows-x64
// targets are produced on matching CI runners (subtask 3); on a Linux host only
// `linux-x64` is reproducible.

/**
 * Immutable canonical token list. Order is stable so consumers can index/iterate
 * deterministically. Subtask 4's OS/arch detection looks these exact tokens up.
 */
val runtimeTargetTokens: List<String> =
  listOf(
    "macos-arm64",
    "macos-x64",
    "windows-x64",
    "linux-x64",
  )

/**
 * Map the running JVM's os.name / os.arch to exactly one canonical token, or `null`
 * when the host is not a supported image target (e.g. arm64 Linux is an OPTIONAL
 * known-gap target — F-001). Callers MUST treat `null` as a non-fatal known-gap at
 * configuration time and only fail loudly if an image task is actually invoked.
 *
 * os family: mac/darwin -> macos, windows -> windows, linux -> linux.
 * arch: aarch64/arm64 -> arm64, x86_64/amd64 -> x64.
 */
fun resolveHostRuntimeToken(
  osName: String = System.getProperty("os.name").orEmpty(),
  osArch: String = System.getProperty("os.arch").orEmpty(),
): String? {
  val osFamily = resolveOsFamily(osName.lowercase())
  val arch = resolveArch(osArch.lowercase())
  if (osFamily == null || arch == null) return null
  return "$osFamily-$arch".takeIf { it in runtimeTargetTokens }
}

private fun resolveOsFamily(normalizedName: String): String? = when {
  normalizedName.contains("mac") || normalizedName.contains("darwin") -> "macos"
  normalizedName.contains("windows") -> "windows"
  normalizedName.contains("linux") -> "linux"
  else -> null
}

private fun resolveArch(normalizedArch: String): String? = when {
  normalizedArch.contains("aarch64") || normalizedArch.contains("arm64") -> "arm64"
  normalizedArch.contains("x86_64") || normalizedArch.contains("amd64") -> "x64"
  else -> null
}
