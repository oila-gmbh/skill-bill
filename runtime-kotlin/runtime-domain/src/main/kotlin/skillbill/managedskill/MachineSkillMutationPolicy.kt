package skillbill.managedskill

import skillbill.managedskill.model.MachineSkillConflict
import skillbill.managedskill.model.MachineSkillDesiredState
import skillbill.managedskill.model.MachineSkillMutationPlan
import skillbill.managedskill.model.MachineSkillOutcome
import skillbill.managedskill.model.NoFollowEntryKind

object MachineSkillMutationPolicy {
  fun plan(request: MachineSkillDesiredState): MachineSkillMutationPlan {
    val replacements = request.adoptionReplacements.associateBy { it.path }
    val conflicts = mutableListOf<MachineSkillConflict>()
    val mutations = request.desiredMutations.map { desired ->
      val observation = request.preconditions.observations.single { it.path == desired.path }
      val unmanagedCollision =
        observation.kind != NoFollowEntryKind.ABSENT &&
          request.preconditions.ownershipProofs[desired.path]?.managed != true &&
          replacements[desired.path]?.identity != observation.identity
      if (unmanagedCollision) {
        conflicts +=
          MachineSkillConflict(
            "unmanaged-collision",
            desired.path,
            "Existing unmanaged entry is preserved",
          )
        desired.copy(outcome = MachineSkillOutcome.CONFLICT)
      } else {
        desired
      }
    }
    return MachineSkillMutationPlan(
      request.kind,
      request.skillName,
      mutations,
      request.preconditions,
      conflicts,
      request.warnings,
    )
  }
}
