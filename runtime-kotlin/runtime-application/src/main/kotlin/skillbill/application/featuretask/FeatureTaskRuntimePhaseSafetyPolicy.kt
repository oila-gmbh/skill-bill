package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

internal data class FeatureTaskRuntimePhaseFileManifest(
  val before: List<String>,
  val after: List<String>,
) {
  val introduced: List<String> = (after.toSet() - before.toSet()).sorted()
}

object FeatureTaskRuntimePhaseSafetyPolicy {
  fun lineSeparatedPaths(raw: String): List<String> = raw
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .sorted()
    .toList()

  /**
   * Paths still present in the tree after the phase: modifies, adds, untracked files, and rename
   * destinations. Pure deletes are excluded — they are not introductions and must not widen
   * [FeatureTaskRuntimePhaseFileManifest.introduced] or trip the outside-inventory checkpoint block
   * when a package move leaves ` D old` beside `?? new/`.
   */
  fun changedPaths(status: String): List<String> = porcelainEntries(status)
    .mapNotNull(PorcelainEntry::retainedPath)
    .filterNot(::isRuntimePrivatePath)
    .distinct()
    .sorted()

  /**
   * Paths removed from the tree: worktree/index deletes and rename sources. Checkpoint ownership
   * must absorb these so a package move can stage the delete half, not only the destination.
   */
  fun deletedPaths(status: String): List<String> = porcelainEntries(status)
    .mapNotNull(PorcelainEntry::removedPath)
    .filterNot(::isRuntimePrivatePath)
    .distinct()
    .sorted()

  fun dispositionForTerminalOutput(phaseId: String, output: Map<String, Any?>): FeatureTaskRuntimeFailureDisposition {
    val explicit = (output["failure_disposition"] as? String)
      ?.let(FeatureTaskRuntimeFailureDisposition::fromWireValue)
    if (explicit != null) return explicit
    return if (
      output["status"] == "failed" ||
      phaseId == "validate"
    ) {
      FeatureTaskRuntimeFailureDisposition.RETRYABLE
    } else {
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION
    }
  }

  private fun porcelainEntries(status: String): List<PorcelainEntry> = status
    .lineSequence()
    .map(String::trimEnd)
    .mapNotNull(::porcelainEntry)
    .toList()

  private fun porcelainEntry(line: String): PorcelainEntry? {
    if (line.length < PORCELAIN_PATH_OFFSET) return null
    val code = line.take(PORCELAIN_STATUS_WIDTH)
    val payload = line.substring(PORCELAIN_PATH_OFFSET).trim().trim('"')
    if (payload.isBlank()) return null
    return when {
      code == UNTRACKED || code == IGNORED -> PorcelainEntry(retainedPath = payload, removedPath = null)
      RENAME_SEPARATOR in payload -> {
        val source = payload.substringBeforeLast(RENAME_SEPARATOR).trim().trim('"')
        val destination = payload.substringAfterLast(RENAME_SEPARATOR).trim().trim('"')
        PorcelainEntry(
          retainedPath = destination.takeIf(String::isNotBlank),
          removedPath = source.takeIf(String::isNotBlank),
        )
      }
      code[0] == DELETE_STATUS || code[1] == DELETE_STATUS ->
        PorcelainEntry(retainedPath = null, removedPath = payload)
      else -> PorcelainEntry(retainedPath = payload, removedPath = null)
    }
  }

  private data class PorcelainEntry(
    val retainedPath: String?,
    val removedPath: String?,
  )

  private const val PORCELAIN_PATH_OFFSET: Int = 3
  private const val PORCELAIN_STATUS_WIDTH: Int = 2
  private const val DELETE_STATUS: Char = 'D'
  private const val UNTRACKED: String = "??"
  private const val IGNORED: String = "!!"
  private const val RENAME_SEPARATOR: String = " -> "
}
