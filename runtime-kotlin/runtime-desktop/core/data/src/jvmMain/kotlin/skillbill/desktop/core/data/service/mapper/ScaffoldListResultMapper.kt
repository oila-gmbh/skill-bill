package skillbill.desktop.core.data.service.mapper

import skillbill.ports.scaffold.catalog.model.ScaffoldListResult

/**
 * SKILL-52.3 subtask 3 — Adapter-side mapper from the typed `ScaffoldListResult`
 * into a typed list of authored-skill metadata entries.
 *
 * SKILL-52.3 subtask 3 retired the `@OpenBoundaryMap` `payload: Map<String, Any?>`
 * field on `ScaffoldListResult`; the result now carries a typed
 * `List<ScaffoldSkillStatus>`. As anticipated in SKILL-52.2 subtask 5, this mapper
 * collapses to a 1:1 typed copy and no longer indexes any raw map.
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

internal fun ScaffoldListResult.authoredSkillEntries(): List<AuthoredSkillEntry> = skills.map { skill ->
  AuthoredSkillEntry(
    skillName = skill.skillName,
    platform = skill.platform,
    family = skill.family,
    area = skill.area,
    contentFile = skill.contentFile,
    completionStatus = skill.completionStatus,
    packageName = skill.packageName,
  )
}
