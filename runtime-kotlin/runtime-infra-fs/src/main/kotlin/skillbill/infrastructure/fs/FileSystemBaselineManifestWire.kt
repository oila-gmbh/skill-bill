package skillbill.infrastructure.fs

import skillbill.contracts.JsonSupport
import skillbill.error.UnreadableBaselineManifestError
import skillbill.install.model.BaselineManifest
import java.nio.file.Path

/**
 * SKILL-76 Subtask 2: wire codec for the baseline manifest. Mirrors
 * [FileSystemInstallSelectionWire] — `JsonSupport.mapToJsonString`, a
 * `contract_version`, and SORTED keys so writes are byte-stable and a no-change
 * reinstall produces a byte-identical file (AC-9 idempotency). The persistence
 * adapter round-trips the rendered JSON through [parseBaselineManifestPayload]
 * before committing so a malformed write loud-fails before touching disk.
 */
internal fun BaselineManifest.toBaselineManifestJson(): String = JsonSupport.mapToJsonString(toWireMap())

private fun BaselineManifest.toWireMap(): Map<String, Any?> = linkedMapOf(
  "contract_version" to BASELINE_MANIFEST_CONTRACT_VERSION,
  // Sorted keys give a deterministic, byte-stable serialization for idempotent writes.
  "baselines" to LinkedHashMap<String, Any?>(entries.toSortedMap()),
)

internal fun parseBaselineManifestPayload(path: Path, rawPayload: String): BaselineManifest {
  val payload = JsonSupport.anyToStringAnyMap(
    JsonSupport.parseObjectOrNull(rawPayload)?.let(JsonSupport::jsonElementToValue),
  ) ?: throw unreadableBaseline(path, "Root value must be a JSON object.")
  return runCatching { payload.toBaselineManifest(path) }
    .getOrElse { error -> throw error.toUnreadableBaseline(path) }
}

private fun Map<String, Any?>.toBaselineManifest(path: Path): BaselineManifest {
  requireBaselineKeys(path, keys, BASELINE_MANIFEST_KEYS)
  requireBaselineContractVersion(path, requireBaselineString(path, "contract_version"))
  val rawBaselines = JsonSupport.anyToStringAnyMap(get("baselines"))
    ?: throw unreadableBaseline(path, "Field 'baselines' must be an object.")
  val entries = rawBaselines.entries.associate { (skillRelativePath, rawHash) ->
    if (skillRelativePath.isBlank()) {
      throw unreadableBaseline(path, "Baseline keys (skill-relative paths) must not be blank.")
    }
    skillRelativePath to validatedBaselineHash(path, skillRelativePath, rawHash)
  }
  return BaselineManifest.of(BASELINE_MANIFEST_CONTRACT_VERSION, entries)
}

private fun validatedBaselineHash(path: Path, skillRelativePath: String, rawHash: Any?): String {
  val hash = rawHash as? String
    ?: throw unreadableBaseline(path, "Baseline hash for '$skillRelativePath' must be a string.")
  if (!hash.matches(BASELINE_HASH_REGEX)) {
    throw unreadableBaseline(
      path,
      "Baseline hash for '$skillRelativePath' must be $BASELINE_HASH_HEX_LENGTH lowercase hex chars, got '$hash'.",
    )
  }
  return hash
}

private fun requireBaselineKeys(path: Path, actualKeys: Set<String>, expectedKeys: Set<String>) {
  val unknownKeys = actualKeys - expectedKeys
  if (unknownKeys.isNotEmpty()) {
    throw unreadableBaseline(path, "Manifest contains unknown keys: ${unknownKeys.sorted()}.")
  }
  val missingKeys = expectedKeys - actualKeys
  if (missingKeys.isNotEmpty()) {
    throw unreadableBaseline(path, "Manifest is missing keys: ${missingKeys.sorted()}.")
  }
}

private fun Map<String, Any?>.requireBaselineString(path: Path, key: String): String = get(key) as? String
  ?: throw unreadableBaseline(path, "Field '$key' must be a string.")

private fun requireBaselineContractVersion(path: Path, version: String) {
  if (version != BASELINE_MANIFEST_CONTRACT_VERSION) {
    throw unreadableBaseline(
      path,
      "Unsupported contract_version '$version'; expected '$BASELINE_MANIFEST_CONTRACT_VERSION'",
    )
  }
}

internal fun Throwable.toUnreadableBaseline(path: Path): UnreadableBaselineManifestError =
  this as? UnreadableBaselineManifestError ?: unreadableBaseline(path, message.orEmpty(), this)

internal fun unreadableBaseline(
  path: Path,
  reason: String,
  cause: Throwable? = null,
): UnreadableBaselineManifestError = UnreadableBaselineManifestError(
  path = path.toString(),
  reason = reason.ifBlank { "No reason provided" },
  cause = cause,
)

internal const val BASELINE_MANIFEST_CONTRACT_VERSION = "1.0"
internal const val BASELINE_MANIFEST_FILE_NAME = "baseline-manifest.json"
private const val BASELINE_HASH_HEX_LENGTH = 16
private val BASELINE_HASH_REGEX = Regex("^[0-9a-f]{$BASELINE_HASH_HEX_LENGTH}$")
private val BASELINE_MANIFEST_KEYS = setOf("contract_version", "baselines")
