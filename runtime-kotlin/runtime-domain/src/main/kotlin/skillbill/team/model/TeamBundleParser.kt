@file:Suppress("TooManyFunctions")

package skillbill.team.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidTeamBundleSchemaError

object TeamBundleParser {
  @OpenBoundaryMap("Team bundle wire map at the typed model parse seam")
  fun parse(bundle: Map<String, Any?>, sourceLabel: String): TeamBundle = TeamBundle(
    contractVersion = requiredString(bundle, "contract_version", sourceLabel),
    metadata = TeamBundleMetadata(
      bundleId = requiredString(bundle, "bundle_id", sourceLabel),
      version = requiredString(bundle, "version", sourceLabel),
      channel = requiredEnum(
        TeamBundleChannel.fromWireValue(requiredString(bundle, "channel", sourceLabel)),
        "channel",
        sourceLabel,
      ),
      createdAt = requiredString(bundle, "created_at", sourceLabel),
      createdBy = requiredString(bundle, "created_by", sourceLabel),
      sourceRepo = requiredString(bundle, "source_repo", sourceLabel),
      sourceRef = requiredString(bundle, "source_ref", sourceLabel),
      sourceCommit = optionalString(bundle, "source_commit", sourceLabel),
    ),
    hashes = TeamBundleHashes(
      contentHash = requiredString(bundle, "content_hash", sourceLabel),
      manifestHashes = stringMap(requiredMap(bundle, "manifest_hashes", sourceLabel), "manifest_hashes", sourceLabel),
      bundleChecksum = requiredString(bundle, "bundle_checksum", sourceLabel),
    ),
    sources = sourceEntries(requiredList(bundle, "sources", sourceLabel), sourceLabel),
    compatibility = compatibility(requiredMap(bundle, "compatibility", sourceLabel), sourceLabel),
    telemetryDefaults = telemetryDefaults(requiredMap(bundle, "telemetry_defaults", sourceLabel), sourceLabel),
    privacyDefaults = privacyDefaults(requiredMap(bundle, "privacy_defaults", sourceLabel), sourceLabel),
    teamMetadata = optionalMap(bundle, "team_metadata", sourceLabel)?.let { teamMetadata(it, sourceLabel) },
    exclusions = exclusions(requiredMap(bundle, "exclusions", sourceLabel), sourceLabel),
  )

  private fun sourceEntries(entries: List<*>, sourceLabel: String): List<TeamBundleSourceEntry> =
    entries.mapIndexed { index, raw ->
      val fieldPath = "sources[$index]"
      val entry = raw as? Map<*, *> ?: invalid(sourceLabel, fieldPath, "source entry must be an object.")
      TeamBundleSourceEntry(
        category = requiredEnum(
          TeamBundleSourceCategory.fromWireValue(requiredString(entry, "category", sourceLabel, fieldPath)),
          "$fieldPath.category",
          sourceLabel,
        ),
        path = requiredString(entry, "path", sourceLabel, fieldPath),
        contentHash = requiredString(entry, "content_hash", sourceLabel, fieldPath),
        manifestHashKey = optionalString(entry, "manifest_hash_key", sourceLabel, fieldPath),
      )
    }

  private fun compatibility(raw: Map<*, *>, sourceLabel: String): TeamBundleCompatibility = TeamBundleCompatibility(
    minSkillBillVersion = requiredString(raw, "min_skill_bill_version", sourceLabel, "compatibility"),
    maxSkillBillVersion = optionalString(raw, "max_skill_bill_version", sourceLabel, "compatibility"),
    shellContractVersion = requiredString(raw, "shell_contract_version", sourceLabel, "compatibility"),
    platformPackContractVersion = optionalString(raw, "platform_pack_contract_version", sourceLabel, "compatibility"),
  )

  private fun telemetryDefaults(raw: Map<*, *>, sourceLabel: String): TeamBundleTelemetryDefaults =
    TeamBundleTelemetryDefaults(
      enabled = requiredBoolean(raw, "enabled", sourceLabel, "telemetry_defaults"),
      level = requiredEnum(
        TeamBundlePrivacyLevel.fromWireValue(requiredString(raw, "level", sourceLabel, "telemetry_defaults")),
        "telemetry_defaults.level",
        sourceLabel,
      ),
    )

  private fun privacyDefaults(raw: Map<*, *>, sourceLabel: String): TeamBundlePrivacyDefaults =
    TeamBundlePrivacyDefaults(
      telemetry = requiredPrivacy(raw, "telemetry", sourceLabel, "privacy_defaults"),
      sourcePaths = requiredPrivacy(raw, "source_paths", sourceLabel, "privacy_defaults"),
      authorIdentity = requiredPrivacy(raw, "author_identity", sourceLabel, "privacy_defaults"),
    )

  private fun teamMetadata(raw: Map<*, *>, sourceLabel: String): TeamBundleTeamMetadata = TeamBundleTeamMetadata(
    teamId = requiredString(raw, "team_id", sourceLabel, "team_metadata"),
    name = requiredString(raw, "name", sourceLabel, "team_metadata"),
    description = optionalString(raw, "description", sourceLabel, "team_metadata"),
  )

  private fun exclusions(raw: Map<*, *>, sourceLabel: String): TeamBundleExclusions = TeamBundleExclusions(
    paths = stringList(requiredList(raw, "paths", sourceLabel, "exclusions"), "exclusions.paths", sourceLabel),
    reasons = stringMap(requiredMap(raw, "reasons", sourceLabel, "exclusions"), "exclusions.reasons", sourceLabel),
  )

  private fun requiredPrivacy(
    raw: Map<*, *>,
    key: String,
    sourceLabel: String,
    parentPath: String,
  ): TeamBundlePrivacyLevel = requiredEnum(
    TeamBundlePrivacyLevel.fromWireValue(requiredString(raw, key, sourceLabel, parentPath)),
    "$parentPath.$key",
    sourceLabel,
  )

  private fun requiredString(raw: Map<*, *>, key: String, sourceLabel: String, parentPath: String? = null): String {
    val fieldPath = fieldPath(parentPath, key)
    return raw[key] as? String ?: invalid(sourceLabel, fieldPath, "must be a string.")
  }

  private fun optionalString(raw: Map<*, *>, key: String, sourceLabel: String, parentPath: String? = null): String? {
    val value = raw[key] ?: return null
    return value as? String ?: invalid(sourceLabel, fieldPath(parentPath, key), "must be a string or null.")
  }

  private fun requiredBoolean(raw: Map<*, *>, key: String, sourceLabel: String, parentPath: String? = null): Boolean {
    val fieldPath = fieldPath(parentPath, key)
    return raw[key] as? Boolean ?: invalid(sourceLabel, fieldPath, "must be a boolean.")
  }

  private fun requiredMap(raw: Map<*, *>, key: String, sourceLabel: String, parentPath: String? = null): Map<*, *> {
    val fieldPath = fieldPath(parentPath, key)
    return raw[key] as? Map<*, *> ?: invalid(sourceLabel, fieldPath, "must be an object.")
  }

  private fun optionalMap(raw: Map<*, *>, key: String, sourceLabel: String, parentPath: String? = null): Map<*, *>? {
    val value = raw[key] ?: return null
    return value as? Map<*, *> ?: invalid(sourceLabel, fieldPath(parentPath, key), "must be an object or null.")
  }

  private fun requiredList(raw: Map<*, *>, key: String, sourceLabel: String, parentPath: String? = null): List<*> {
    val fieldPath = fieldPath(parentPath, key)
    return raw[key] as? List<*> ?: invalid(sourceLabel, fieldPath, "must be an array.")
  }

  private fun stringMap(raw: Map<*, *>, fieldPath: String, sourceLabel: String): Map<String, String> =
    raw.mapKeys { (key, _) ->
      key as? String ?: invalid(sourceLabel, fieldPath, "all keys must be strings.")
    }.mapValues { (_, value) ->
      value as? String ?: invalid(sourceLabel, fieldPath, "all values must be strings.")
    }

  private fun stringList(raw: List<*>, fieldPath: String, sourceLabel: String): List<String> =
    raw.mapIndexed { index, value ->
      value as? String ?: invalid(sourceLabel, "$fieldPath[$index]", "must be a string.")
    }

  private fun <T> requiredEnum(value: T?, fieldPath: String, sourceLabel: String): T =
    value ?: invalid(sourceLabel, fieldPath, "contains an unknown enum value.")

  private fun fieldPath(parentPath: String?, key: String): String =
    if (parentPath.isNullOrBlank()) key else "$parentPath.$key"

  private fun invalid(sourceLabel: String, fieldPath: String, reason: String): Nothing =
    throw InvalidTeamBundleSchemaError(sourceLabel, fieldPath, reason)
}
