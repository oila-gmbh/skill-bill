package skillbill.install.policy

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPolicyInput
import java.nio.file.Path

internal fun requireNoDuplicateDefaultTargets(input: InstallPolicyInput) {
  val duplicates = input.defaultAgentTargets
    .groupBy { target -> target.agent }
    .filterValues { targets -> targets.size > 1 }
    .keys
  require(duplicates.isEmpty()) {
    "Default agent target snapshots contain duplicate agent(s): " +
      duplicates.map(InstallAgent::id).sorted().joinToString(", ") + "."
  }
}

internal fun requireNoDuplicateAgentTargets(label: String, targets: List<InstallAgentTarget>) {
  val duplicates = targets
    .groupBy(InstallAgentTarget::agent)
    .filterValues { matchingTargets -> matchingTargets.size > 1 }
    .keys
  require(duplicates.isEmpty()) {
    "$label contains duplicate agent target(s): ${duplicates.map(InstallAgent::id).sorted().joinToString(", ")}."
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
