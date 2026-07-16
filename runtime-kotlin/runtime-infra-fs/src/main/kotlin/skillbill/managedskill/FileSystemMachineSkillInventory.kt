package skillbill.managedskill

import me.tatarka.inject.annotations.Inject
import skillbill.managedskill.model.AgentSkillTargetState
import skillbill.managedskill.model.MachineSkillEntryKind
import skillbill.managedskill.model.MachineSkillHealth
import skillbill.managedskill.model.MachineSkillInventoryDiagnostic
import skillbill.managedskill.model.MachineSkillInventoryRow
import skillbill.managedskill.model.MachineSkillInventorySnapshot
import skillbill.managedskill.model.MachineSkillIssue
import skillbill.managedskill.model.MachineSkillIssueSeverity
import skillbill.managedskill.model.MachineSkillLinkHealth
import skillbill.managedskill.model.MachineSkillOccurrence
import skillbill.managedskill.model.MachineSkillOwnership
import skillbill.managedskill.model.MachineSkillTargetPresence
import skillbill.managedskill.model.ManagedSkillRecord
import skillbill.managedskill.model.normalizeManagedSkillName
import skillbill.ports.install.baseline.BaselineManifestPersistencePort
import skillbill.ports.install.baseline.model.ReadBaselineManifestRequest
import skillbill.ports.managedskill.MachineSkillInventoryPort
import skillbill.ports.managedskill.model.ReadMachineSkillInventoryRequest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

@Inject
class FileSystemMachineSkillInventory(
  private val baselinePort: BaselineManifestPersistencePort,
) : MachineSkillInventoryPort {
  @Suppress("LongMethod")
  override fun read(request: ReadMachineSkillInventoryRequest): MachineSkillInventorySnapshot {
    val stateRootResult = runCatching { FileManagedSkillRecordStore.openReadOnly(request.home) }
    val store = stateRootResult.getOrNull()
    val recordDiscoveries = store?.listRecords().orEmpty()
    val records = recordDiscoveries.mapNotNull(
      ManagedSkillRecordDiscovery::record,
    ).associateBy(ManagedSkillRecord::name)
    val diagnostics = recordDiscoveries.mapNotNull { discovery ->
      discovery.error?.let { MachineSkillInventoryDiagnostic("CORRUPT_RECORD", discovery.path, it) }
    }.toMutableList()
    stateRootResult.exceptionOrNull()?.let { error ->
      diagnostics += MachineSkillInventoryDiagnostic(
        "INVALID_STATE_ROOT",
        request.home.resolve(".skill-bill"),
        error.message ?: error::class.java.name,
      )
    }
    val baselineNames = baselinePort.readBaseline(ReadBaselineManifestRequest(request.home)).manifest.entries.keys
      .mapNotNull(::productNameFromBaselinePath).toSet() + ".bill-shared"
    val occurrences = request.targets.flatMap { target -> scanTarget(target.id, records, store, baselineNames) }
    val product = occurrences.filter { it.ownership == MachineSkillOwnership.PRODUCT }
    val nonProduct = occurrences.filterNot { it.ownership == MachineSkillOwnership.PRODUCT }
    val validGroups = nonProduct.groupBy { normalizeManagedSkillName(it.rawName) }.toMutableMap()
    records.keys.forEach { name -> validGroups.putIfAbsent(normalizeManagedSkillName(name), emptyList()) }
    validGroups[null].orEmpty().forEach { occurrence ->
      diagnostics += MachineSkillInventoryDiagnostic(
        "INVALID_NAME",
        occurrence.path,
        "Invalid skill name '${occurrence.rawName}'.",
      )
    }
    val rows = validGroups.filterKeys { it != null }.map { (name, group) ->
      aggregate(name!!, group, request.targets.map { it.id }, records[name])
    }.sortedBy(MachineSkillInventoryRow::normalizedName)
    val expectedSnapshots = records.values.mapNotNull { record ->
      store?.snapshotRoot(record.name, record.activeContentHash)
    }.toSet()
    store?.listSnapshotPaths().orEmpty().filter {
      it !in expectedSnapshots && !isVerifiedProductStaging(it)
    }.forEach { path ->
      diagnostics += MachineSkillInventoryDiagnostic(
        "ORPHAN_SNAPSHOT",
        path,
        "Snapshot is not referenced by a managed record.",
      )
    }
    val targetStates = request.targets.map { target ->
      val providerConflicts = request.targets.filter {
        it.id.skillsPath == target.id.skillsPath
      }.map { it.id.provider }.distinct()
      val issues = target.issues.map { MachineSkillIssue("DUPLICATE_TARGET", it) } +
        if (providerConflicts.size > 1) {
          listOf(
            MachineSkillIssue("PROVIDER_PATH_CONFLICT", "Path is shared by multiple providers."),
          )
        } else {
          emptyList()
        }
      AgentSkillTargetState(
        target.id,
        target.displayName,
        target.detected,
        target.selected,
        Files.isDirectory(target.id.skillsPath, NOFOLLOW_LINKS),
        issues,
      )
    }.sortedBy { it.id.stableIdentity }
    return MachineSkillInventorySnapshot(
      targetStates,
      rows,
      if (request.includeProductDiagnostics) product.sortedBy { it.path.toString() } else emptyList(),
      diagnostics.sortedWith(compareBy({ it.kind }, { it.path?.toString().orEmpty() })),
    )
  }
}

private fun scanTarget(
  target: skillbill.managedskill.model.AgentSkillTargetId,
  records: Map<String, ManagedSkillRecord>,
  store: FileManagedSkillRecordStore?,
  baselineNames: Set<String>,
): List<MachineSkillOccurrence> {
  if (!Files.isDirectory(target.skillsPath, NOFOLLOW_LINKS)) return emptyList()
  return Files.newDirectoryStream(target.skillsPath).use { entries ->
    entries.map { path ->
      scanOccurrence(
        target,
        path,
        records[normalizeManagedSkillName(path.fileName.toString())],
        store,
        baselineNames,
      )
    }
      .sortedBy { it.path.toString() }
  }
}

@Suppress("CyclomaticComplexMethod", "ComplexCondition", "LongMethod")
private fun scanOccurrence(
  target: skillbill.managedskill.model.AgentSkillTargetId,
  path: Path,
  record: ManagedSkillRecord?,
  store: FileManagedSkillRecordStore?,
  baselineNames: Set<String>,
): MachineSkillOccurrence {
  val rawName = path.fileName.toString()
  val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  val kind = when {
    attributes.isSymbolicLink -> MachineSkillEntryKind.SYMLINK
    attributes.isRegularFile -> MachineSkillEntryKind.FILE
    attributes.isDirectory -> MachineSkillEntryKind.DIRECTORY
    else -> MachineSkillEntryKind.OTHER
  }
  val resolved = if (kind == MachineSkillEntryKind.SYMLINK) {
    val rawTarget = Files.readSymbolicLink(path)
    (path.parent.resolve(rawTarget)).toAbsolutePath().normalize()
  } else {
    null
  }
  val expected = record?.let { store?.snapshotRoot(it.name, it.activeContentHash) }
  val product = rawName == ".bill-shared" || rawName in baselineNames ||
    (resolved != null && isVerifiedProductStaging(resolved, store, rawName))
  val exactExpectedLink = expected != null && resolved == expected
  val exists = resolved?.let { Files.exists(it, NOFOLLOW_LINKS) } ?: true
  val stableExpectedDirectory = exactExpectedLink && Files.isDirectory(expected, NOFOLLOW_LINKS) &&
    !Files.isSymbolicLink(expected) && runCatching { expected.toRealPath() == expected }.getOrDefault(false)
  val exactOwnedLink = exactExpectedLink && (!exists || stableExpectedDirectory)
  val linkHealth = when {
    kind != MachineSkillEntryKind.SYMLINK -> MachineSkillLinkHealth.NOT_A_LINK
    exactOwnedLink && !exists -> MachineSkillLinkHealth.EXPECTED_BROKEN
    exactOwnedLink -> MachineSkillLinkHealth.HEALTHY
    record != null -> MachineSkillLinkHealth.EXPECTED_MISMATCH
    !exists -> MachineSkillLinkHealth.BROKEN
    else -> MachineSkillLinkHealth.EXTERNAL
  }
  val ownership = when {
    product -> MachineSkillOwnership.PRODUCT
    exactOwnedLink -> MachineSkillOwnership.MANAGED
    record != null -> MachineSkillOwnership.CONFLICT
    else -> MachineSkillOwnership.UNMANAGED
  }
  val scanPath = resolved?.takeIf { Files.exists(it, NOFOLLOW_LINKS) } ?: path
  val scanResult = if (kind == MachineSkillEntryKind.OTHER || (resolved != null && !exists)) {
    null
  } else {
    runCatching { OpaqueSkillBundleScanner().scan(scanPath, emptySet()) }
  }
  val bundle = scanResult?.getOrNull()
  val issues = mutableListOf<MachineSkillIssue>()
  scanResult?.exceptionOrNull()?.let { error ->
    issues += MachineSkillIssue(
      "INVALID_BUNDLE",
      error.message ?: "Bundle scan failed.",
      MachineSkillIssueSeverity.ERROR,
    )
  }
  if (record != null) validateManagedSource(record, issues)
  if (record != null && bundle != null && exactOwnedLink && bundle.contentHash != record.activeContentHash) {
    issues += MachineSkillIssue(
      "HASH_MISMATCH",
      "Installed content does not match the managed record.",
      MachineSkillIssueSeverity.ERROR,
    )
  }
  if (linkHealth != MachineSkillLinkHealth.HEALTHY && linkHealth != MachineSkillLinkHealth.NOT_A_LINK) {
    issues += MachineSkillIssue("LINK_${linkHealth.name}", "Link health is ${linkHealth.name.lowercase()}.")
  }
  if (bundle != null && normalizeManagedSkillName(bundle.name) != normalizeManagedSkillName(rawName)) {
    issues += MachineSkillIssue("NAME_MISMATCH", "Entry name and bundle name differ.")
  }
  val provenance = setOfNotNull(
    if (record != null) "managed-record" else null,
    if (product) "product-evidence" else null,
  )
  return MachineSkillOccurrence(
    target,
    rawName,
    path.toAbsolutePath().normalize(),
    kind,
    ownership,
    linkHealth,
    bundle?.contentHash,
    provenance,
    issues,
  )
}

@Suppress("CyclomaticComplexMethod")
private fun aggregate(
  name: String,
  occurrences: List<MachineSkillOccurrence>,
  targets: List<skillbill.managedskill.model.AgentSkillTargetId>,
  record: ManagedSkillRecord?,
): MachineSkillInventoryRow {
  val hashes = occurrences.mapNotNull(MachineSkillOccurrence::contentHash).toSortedSet()
  val divergent = hashes.size > 1
  val collision = occurrences.map(MachineSkillOccurrence::rawName).distinct().size > 1
  val ownerships = occurrences.map(MachineSkillOccurrence::ownership).toSet()
  val ownership = when {
    MachineSkillOwnership.CONFLICT in ownerships || ownerships.size > 1 -> MachineSkillOwnership.CONFLICT
    ownerships.isEmpty() && record != null -> MachineSkillOwnership.MANAGED
    else -> ownerships.single()
  }
  val issues = occurrences.flatMap(MachineSkillOccurrence::issues).toMutableList()
  if (collision) issues += MachineSkillIssue("NAME_COLLISION", "Case or raw-name variants collide.")
  if (divergent) issues += MachineSkillIssue("DIVERGENT_CONTENT", "Same-name copies have different content hashes.")
  val health = when {
    occurrences.isEmpty() && record != null -> MachineSkillHealth.MISSING
    issues.any { it.code == "HASH_MISMATCH" || it.code == "SOURCE_HASH_MISMATCH" } -> MachineSkillHealth.HASH_MISMATCH
    occurrences.any {
      it.linkHealth == MachineSkillLinkHealth.EXPECTED_BROKEN || it.linkHealth == MachineSkillLinkHealth.BROKEN
    } -> MachineSkillHealth.BROKEN_LINK
    divergent -> MachineSkillHealth.DIVERGENT
    issues.any { it.severity == MachineSkillIssueSeverity.ERROR } -> MachineSkillHealth.CORRUPT
    else -> MachineSkillHealth.HEALTHY
  }
  val presence = targets.sortedBy { it.stableIdentity }.map { target ->
    val matches = occurrences.filter { it.target == target }
    MachineSkillTargetPresence(target, matches.isNotEmpty(), matches)
  }
  return MachineSkillInventoryRow(
    name,
    occurrences.minOfOrNull(MachineSkillOccurrence::rawName) ?: record?.name ?: name,
    ownership,
    health,
    presence,
    hashes,
    divergent,
    issues.distinct().sortedBy(MachineSkillIssue::code),
  )
}

private fun productNameFromBaselinePath(path: String): String? {
  val parts = path.replace('\\', '/').split('/').filter(String::isNotBlank)
  val skills = parts.indexOf("skills")
  return if (skills >= 0) parts.getOrNull(skills + 1) else null
}

private fun isVerifiedProductStaging(path: Path): Boolean = Files.isDirectory(path, NOFOLLOW_LINKS) &&
  !Files.isSymbolicLink(path) && Files.isRegularFile(path.resolve(".content-hash"), NOFOLLOW_LINKS)

private fun isVerifiedProductStaging(path: Path, store: FileManagedSkillRecordStore?, rawName: String): Boolean {
  if (store == null || !isVerifiedProductStaging(path)) return false
  val installedRoot = store.snapshotRoot("probe", "hash").parent
  if (path.parent != installedRoot || !path.fileName.toString().startsWith("$rawName-")) return false
  val suffix = path.fileName.toString().removePrefix("$rawName-")
  return runCatching { Files.readString(path.resolve(".content-hash")).trim() == suffix }.getOrDefault(false)
}

private fun validateManagedSource(record: ManagedSkillRecord, issues: MutableList<MachineSkillIssue>) {
  val attributes = runCatching {
    Files.readAttributes(record.sourcePath, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  }.getOrNull()
  if (attributes == null) {
    issues += MachineSkillIssue("MISSING_SOURCE", "Managed source is missing.", MachineSkillIssueSeverity.ERROR)
    return
  }
  if (attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile)) {
    issues += MachineSkillIssue(
      "INVALID_SOURCE",
      "Managed source must be a real regular file or directory.",
      MachineSkillIssueSeverity.ERROR,
    )
    return
  }
  runCatching { OpaqueSkillBundleScanner().scan(record.sourcePath, emptySet()) }.fold(
    onSuccess = { bundle ->
      if (bundle.contentHash != record.activeContentHash) {
        issues += MachineSkillIssue(
          "SOURCE_HASH_MISMATCH",
          "Managed source does not match the managed record.",
          MachineSkillIssueSeverity.ERROR,
        )
      }
    },
    onFailure = { error ->
      issues += MachineSkillIssue(
        "INVALID_SOURCE",
        error.message ?: "Managed source is invalid.",
        MachineSkillIssueSeverity.ERROR,
      )
    },
  )
}
