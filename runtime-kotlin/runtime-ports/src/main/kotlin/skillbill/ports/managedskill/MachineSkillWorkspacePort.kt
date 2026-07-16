package skillbill.ports.managedskill

import skillbill.managedskill.model.ManagedSkillRecord
import skillbill.managedskill.model.OpaqueSkillBundle
import java.nio.file.Path
import skillbill.managedskill.model.SymlinkCapability
import skillbill.ports.managedskill.model.SnapshotReferenceDiscovery

interface MachineSkillWorkspacePort {
  fun capture(source: Path): OpaqueSkillBundle
  fun captureEditedSource(name: String, expectedSourceHash: String, skillMarkdown: String): OpaqueSkillBundle
  fun readRecord(name: String): ManagedSkillRecord?
  fun recordDigest(name: String): String?
  fun sourceRoot(name: String): Path
  fun recordPath(name: String): Path
  fun snapshotRoot(name: String, contentHash: String): Path
  fun observe(paths: Collection<Path>): List<skillbill.managedskill.model.PathObservation>
  fun snapshotHealthy(bundle: OpaqueSkillBundle): Boolean
  fun symlinkCapability(): SymlinkCapability
  fun snapshotReferences(): SnapshotReferenceDiscovery
}
