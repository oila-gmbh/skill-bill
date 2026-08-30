package skillbill.infrastructure.fs

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.decomposition.DecompositionManifestFileDiscoveryStore
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemDecompositionManifestFileStore(
  private val bundleJournal: DecompositionManifestBundleJournal = DecompositionManifestBundleJournal(),
) : DecompositionManifestFileStore,
  DecompositionManifestFileDiscoveryStore by FileSystemDecompositionManifestFileStoreDiscovery(bundleJournal) {
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }

  override fun readText(path: Path): String {
    return withDecompositionManifestBundleLock(path.parent) {
      bundleJournal.recoverPendingUnlocked(path.parent)
      Files.readString(path)
    }
  }

  override fun readTextWithoutRecovery(path: Path): String {
    bundleJournal.failIfPending(path.parent)
    return Files.readString(path)
  }

  override fun isRegularFile(path: Path): Boolean {
    return withDecompositionManifestBundleLock(path.parent) {
      bundleJournal.recoverPendingUnlocked(path.parent)
      Files.isRegularFile(path)
    }
  }

  override fun isRegularFileWithoutRecovery(path: Path): Boolean {
    bundleJournal.failIfPending(path.parent)
    return Files.isRegularFile(path)
  }

  override fun <T> writeBundleAtomically(writes: List<Pair<Path, String>>, verify: () -> T): T {
    val distinctWrites = writes.distinctBy { (path, _) -> path.toAbsolutePath().normalize() }
    val parents = distinctWrites.map { (path, _) -> path.toAbsolutePath().normalize().parent }.distinct()
    if (parents.size != 1 || distinctWrites.isEmpty()) {
      return super<DecompositionManifestFileStore>.writeBundleAtomically(writes, verify)
    }

    val parent = requireNotNull(parents.single())
    return withDecompositionManifestBundleLock(parent) {
      bundleJournal.recoverPendingUnlocked(parent)
      val snapshots = distinctWrites.map { (path, _) ->
        val normalized = path.toAbsolutePath().normalize()
        val existed = Files.isRegularFile(normalized)
        DecompositionManifestBundleSnapshot(normalized, existed, if (existed) Files.readString(normalized) else null)
      }
      val transaction = bundleJournal.create(parent, distinctWrites)
      runCatching {
        bundleJournal.apply(transaction)
        val result = verify()
        bundleJournal.cleanup(transaction)
        result
      }.getOrElse { failure ->
        snapshots.asReversed().forEach { snapshot ->
          runCatching {
            if (snapshot.existed) {
              bundleJournal.writeAtomically(snapshot.path, requireNotNull(snapshot.content))
            } else {
              Files.deleteIfExists(snapshot.path)
            }
          }.onFailure(failure::addSuppressed)
        }
        runCatching { bundleJournal.cleanup(transaction) }.onFailure(failure::addSuppressed)
        throw failure
      }
    }
  }

  override fun encodeManifestYaml(wireMap: Map<String, Any?>): String = yamlMapper.writeValueAsString(wireMap)

  override fun deleteIfExists(target: Path) {
    withDecompositionManifestBundleLock(target.parent) {
      bundleJournal.recoverPendingUnlocked(target.parent)
      Files.deleteIfExists(target)
    }
  }

  override fun writeTextAtomically(target: Path, content: String) {
    withDecompositionManifestBundleLock(target.parent) {
      bundleJournal.recoverPendingUnlocked(target.parent)
      bundleJournal.writeAtomically(target, content)
    }
  }
}
