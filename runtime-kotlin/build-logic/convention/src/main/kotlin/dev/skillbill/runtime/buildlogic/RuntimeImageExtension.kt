package dev.skillbill.runtime.buildlogic

import org.gradle.api.provider.Property

/**
 * SKILL-55 subtask 1 (F-004/F-005): typed contract for the `skillbill.runtime-image`
 * convention plugin. Replaces the stringly-typed `extra["hostRuntimeToken"] as String`
 * coupling subtasks 3/4 would otherwise build on.
 *
 * - [imageBaseName] is the ONLY varying input between modules ("runtime-cli" vs
 *   "runtime-mcp"); it drives the launcher / versioned archive name. Required.
 * - [runtimeTargetTokens] is the canonical per-OS/arch token list (typed, not extra).
 * - [hostRuntimeToken] is the resolved host token, or `null` on an unsupported host
 *   (known-gap target such as arm64 Linux — F-001). Image tasks only operate when
 *   this is non-null; invoking an image task on a null host fails at EXECUTION time.
 */
abstract class RuntimeImageExtension {
  /** Launcher + archive base name, e.g. "runtime-cli" / "runtime-mcp". */
  abstract val imageBaseName: Property<String>

  /** Canonical per-OS/arch target tokens (typed contract for subtasks 3/4). */
  val runtimeTargetTokens: List<String> = dev.skillbill.runtime.buildlogic.runtimeTargetTokens

  /** Resolved host token, or `null` for an unsupported known-gap host (F-001). */
  val hostRuntimeToken: String? = resolveHostRuntimeToken()
}
