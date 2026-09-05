package skillbill.install.policy

import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPolicyInput
import java.nio.file.Path

internal fun requireNoDuplicateDefaultTargets(input: InstallPolicyInput) {
  // Keyed by (agent, normalized path) so a multi-root agent (e.g. claude across several profile
  // roots) emits several legal rows, while a true same-(agent,path) collision still fails.
  val duplicates = input.defaultAgentTargets
    .groupBy { target -> target.agent to target.path.toAbsolutePath().normalize() }
    .filterValues { targets -> targets.size > 1 }
    .keys
  require(duplicates.isEmpty()) {
    "Default agent target snapshots contain duplicate (agent, path): " +
      duplicates.map { (agent, path) -> "${agent.id} at $path" }.sorted().joinToString(", ") + "."
  }
}

internal fun requireNoDuplicateAgentTargets(label: String, targets: List<InstallAgentTarget>) {
  val duplicates = targets
    .groupBy { target -> target.agent to target.path.toAbsolutePath().normalize() }
    .filterValues { matchingTargets -> matchingTargets.size > 1 }
    .keys
  require(duplicates.isEmpty()) {
    "$label contains duplicate agent target(s): " +
      duplicates.map { (agent, path) -> "${agent.id} at $path" }.sorted().joinToString(", ") + "."
  }
}

internal fun requireNoDuplicateSkills(label: String, skills: List<InstallPlanSkill>) {
  val duplicates = duplicateSkills(skills)
  require(duplicates.isEmpty()) {
    duplicateSkillMessage("$label contains duplicate skill name(s): ", duplicates)
  }
}

internal fun requireUniqueSkillNames(skills: List<InstallPlanSkill>) {
  val duplicates = duplicateSkills(skills)
  require(duplicates.isEmpty()) {
    duplicateSkillMessage("Install plan contains duplicate skill name(s): ", duplicates)
  }
}

internal fun validatePath(label: String, path: Path) {
  require(path.toString().isNotBlank()) {
    "$label must not be blank."
  }
}

private fun duplicateSkills(skills: List<InstallPlanSkill>): Map<String, List<InstallPlanSkill>> = skills
  .groupBy(InstallPlanSkill::name)
  .filterValues { matchingSkills -> matchingSkills.size > 1 }

private fun duplicateSkillMessage(prefix: String, duplicates: Map<String, List<InstallPlanSkill>>): String =
  duplicates.entries.joinToString(
    prefix = prefix,
    separator = "; ",
  ) { (name, matchingSkills) ->
    "$name at ${matchingSkills.joinToString(", ") { skill -> skill.sourceDir.toString() }}"
  }
