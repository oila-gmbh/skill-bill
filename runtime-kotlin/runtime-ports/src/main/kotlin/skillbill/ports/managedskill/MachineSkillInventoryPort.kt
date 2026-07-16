package skillbill.ports.managedskill

import skillbill.managedskill.model.MachineSkillInventorySnapshot
import skillbill.ports.managedskill.model.ReadMachineSkillInventoryRequest

interface MachineSkillInventoryPort {
  fun read(request: ReadMachineSkillInventoryRequest): MachineSkillInventorySnapshot
}
