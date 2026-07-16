package skillbill.ports.managedskill

import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.ManagedSkillRecord
import skillbill.managedskill.model.OpaqueSkillBundle
import skillbill.managedskill.model.PathObservation
import skillbill.managedskill.model.SymlinkCapability
import skillbill.ports.managedskill.model.SnapshotReferenceDiscovery
import java.nio.file.Path

interface MachineSkillWorkspacePort {
  val bundles: MachineSkillBundleWorkspacePort
  val records: MachineSkillRecordWorkspacePort
  val snapshots: MachineSkillSnapshotWorkspacePort
  val targets: MachineSkillTargetWorkspacePort
}

interface MachineSkillBundleWorkspacePort {
  fun capture(source: Path): OpaqueSkillBundle
  fun captureEditedSource(name: String, expectedSourceHash: String, skillMarkdown: String): OpaqueSkillBundle
}

interface MachineSkillRecordWorkspacePort {
  fun readRecord(name: String): ManagedSkillRecord?
  fun recordDigest(name: String): String?
  fun sourceRoot(name: String): Path
  fun recordPath(name: String): Path
}

interface MachineSkillSnapshotWorkspacePort {
  fun snapshotRoot(name: String, contentHash: String): Path
  fun snapshotHealthy(bundle: OpaqueSkillBundle): Boolean
  fun orphanSnapshots(): Set<Path>
  fun snapshotUnreferencedAfterDelete(name: String, snapshot: Path, ownedLinks: Set<Path>): Boolean
  fun snapshotReferences(): SnapshotReferenceDiscovery
}

interface MachineSkillTargetWorkspacePort {
  fun observe(paths: Collection<Path>): List<PathObservation>
  fun isDiscoveredTarget(target: AgentSkillTargetId): Boolean
  fun ownedLinkPaths(name: String, expectedSnapshot: Path): Set<Path>
  fun symlinkCapability(): SymlinkCapability
}
