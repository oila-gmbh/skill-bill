package skillbill.desktop.core.data.service.mapper

import skillbill.ports.scaffold.catalog.model.ScaffoldListResult

/**
 * SKILL-52.2 subtask 5 — Adapter-side mapper from the typed
 * `ScaffoldListResult` into a typed list of authored-skill metadata entries.
 *
 * Today `ScaffoldListResult` lifts only `repoRoot` and `skillCount` as typed
 * scalars and retains the documented `@OpenBoundaryMap` `payload: Map<String, Any?>`
 * (`skillbill.ports.scaffold.catalog.model.ScaffoldListResult.payload` is on the
 * raw-map open-boundary allow-list). The desktop repo browser used to read
 * `payload["skills"]` and the per-skill string fields directly inside the
 * service implementation. That raw-map indexing has moved here so:
 *
 *  - service code consumes a typed `List<AuthoredSkillEntry>`, and
 *  - the boundary between typed gateway result ↔ legacy raw-shape decoding is a
 *    single named seam the boundary inventory can locate.
 *
 * If `ScaffoldListResult` ever grows typed entries (e.g. a
 * `List<ScaffoldListSkillEntry>` field), this mapper collapses to a 1:1 copy
 * and the `.payload` read disappears entirely.
 */
internal data class AuthoredSkillEntry(
  val skillName: String,
  val platform: String,
  val family: String,
  val area: String,
  val contentFile: String,
  val completionStatus: String,
  val packageName: String?,
)

internal fun ScaffoldListResult.authoredSkillEntries(): List<AuthoredSkillEntry> {
  val rawSkills = payload["skills"] as? List<*> ?: return emptyList()
  return rawSkills.filterIsInstance<Map<*, *>>().mapNotNull { raw ->
    val skillName = raw.string("skill_name") ?: return@mapNotNull null
    AuthoredSkillEntry(
      skillName = skillName,
      platform = raw.string("platform").orEmpty(),
      family = raw.string("family").orEmpty(),
      area = raw.string("area").orEmpty(),
      contentFile = raw.string("content_file").orEmpty(),
      completionStatus = raw.string("completion_status").orEmpty(),
      packageName = raw.string("package"),
    )
  }
}

private fun Map<*, *>.string(key: String): String? = this[key] as? String
