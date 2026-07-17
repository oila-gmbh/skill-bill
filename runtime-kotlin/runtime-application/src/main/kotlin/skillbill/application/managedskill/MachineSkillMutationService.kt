package skillbill.application.managedskill

import skillbill.managedskill.MachineSkillMutationPolicy
import skillbill.managedskill.model.MachineSkillDesiredState
import skillbill.managedskill.model.MachineSkillMutationPlan

class MachineSkillMutationService {
  fun preview(request: MachineSkillDesiredState): MachineSkillMutationPlan = MachineSkillMutationPolicy.plan(request)
}
