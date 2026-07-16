package skillbill.ports.managedskill

import skillbill.managedskill.model.MachineSkillApplyResult
import skillbill.managedskill.model.MachineSkillMutationPlan
import skillbill.managedskill.model.MachineSkillPreconditions
import skillbill.managedskill.model.PathObservation
import skillbill.managedskill.model.SymlinkCapability
import skillbill.ports.managedskill.model.SnapshotReferenceDiscovery
import java.nio.file.Path

interface MachineSkillMutationInspectorPort {
  fun observe(paths: Collection<Path>): List<PathObservation>
  fun symlinkCapability(): SymlinkCapability
  fun snapshotReferences(): SnapshotReferenceDiscovery
}

interface MachineSkillTransactionPort {
  fun currentPreconditions(plan: MachineSkillMutationPlan): MachineSkillPreconditions
  fun apply(plan: MachineSkillMutationPlan): MachineSkillApplyResult
  fun recoverIncompleteTransactions(): List<String>
}
